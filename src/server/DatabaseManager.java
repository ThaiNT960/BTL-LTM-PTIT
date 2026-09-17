package server;
import java.sql.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;

public class DatabaseManager implements Store {
    private final String url,user,password;
    public DatabaseManager() throws Exception {
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(Path.of("server.properties"))) {p.load(in);}
        url=System.getProperty("db.url",p.getProperty("db.url"));
        user=System.getProperty("db.user",p.getProperty("db.user"));
        password=System.getenv().getOrDefault("NUMBER_GAME_DB_PASSWORD",p.getProperty("db.password",""));
        try(Connection c=open(); Statement s=c.createStatement()) {
            // Interrupted sessions cannot count as wins/losses after a server restart.
            s.executeUpdate("UPDATE matches SET status='ABORTED',end_reason='SERVER_RESTART',ended_at=CURRENT_TIMESTAMP WHERE status='ACTIVE'");
        }
    }
    private Connection open() throws SQLException {return DriverManager.getConnection(url,user,password);}
    private void bind(PreparedStatement s,Object...args) throws SQLException {
        for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
    }
    private List<HashMap<String,String>> query(String sql,Object...args) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)) {
            bind(s,args); try(ResultSet r=s.executeQuery()) {
                List<HashMap<String,String>> rows=new ArrayList<>();
                while(r.next()) {HashMap<String,String> row=new HashMap<>();
                    for(int i=1;i<=r.getMetaData().getColumnCount();i++) row.put(r.getMetaData().getColumnLabel(i),Objects.toString(r.getObject(i),""));
                    rows.add(row);
                } return rows;
            }
        }
    }
    private void update(String sql,Object...args) throws SQLException {
        try(Connection c=open();PreparedStatement s=c.prepareStatement(sql)) {bind(s,args);s.executeUpdate();}
    }
    public Account find(String name) throws Exception {
        var rows=query("SELECT * FROM users WHERE username=?",name);
        if(rows.isEmpty())return null;var r=rows.get(0);
        return new Account(Long.parseLong(r.get("id")),r.get("username"),r.get("display_name"),r.get("password_hash"),r.get("role"),!r.get("blocked").equals("0")&&!r.get("blocked").equals("false"));
    }
    public void register(String name,String hash) throws Exception {
        update("INSERT INTO users(username,display_name,password_hash) VALUES(?,?,?)",name,name,hash);
    }
    public void profile(long id,String name,String hash) throws Exception {
        if(hash==null) update("UPDATE users SET display_name=? WHERE id=?",name,id);
        else update("UPDATE users SET display_name=?,password_hash=? WHERE id=?",name,hash,id);
    }
    public long createMatch(long a,long b,int range) throws Exception {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("INSERT INTO matches(player1_id,player2_id,range_max) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            bind(s,a,b,range);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public void guess(long match,int turn,long player,Integer number,String hint) throws Exception {
        update("INSERT INTO guesses(match_id,turn_no,player_id,number,hint) VALUES(?,?,?,?,?)",match,turn,player,number,hint);
    }
    public void finish(long match,long winner,String reason) throws Exception {
        // One conditional write; statistics are derived from matches, never incremented twice.
        update("UPDATE matches SET status='FINISHED',winner_id=?,end_reason=?,ended_at=CURRENT_TIMESTAMP WHERE id=? AND status='ACTIVE'",winner,reason,match);
    }
    public void chat(long match,long sender,String text) throws Exception {update("INSERT INTO chat_messages(match_id,sender_id,content) VALUES(?,?,?)",match,sender,text);}
    public List<HashMap<String,String>> ranking() throws Exception {
        return query("SELECT u.id,u.username,u.display_name,COUNT(m.id) total,COALESCE(SUM(m.winner_id=u.id),0) wins,COUNT(m.id)-COALESCE(SUM(m.winner_id=u.id),0) losses,COALESCE(ROUND(100.0*SUM(m.winner_id=u.id)/NULLIF(COUNT(m.id),0),1),0) rate FROM users u LEFT JOIN matches m ON (m.player1_id=u.id OR m.player2_id=u.id) AND m.status='FINISHED' GROUP BY u.id ORDER BY wins DESC,rate DESC,u.username");
    }
    public List<HashMap<String,String>> history(long id) throws Exception {
        return query("SELECT m.id,m.started_at,m.range_max,m.end_reason,u.username opponent,IF(m.winner_id=?,'Thắng','Thua') result FROM matches m JOIN users u ON u.id=IF(m.player1_id=?,m.player2_id,m.player1_id) WHERE (m.player1_id=? OR m.player2_id=?) AND m.status='FINISHED' ORDER BY m.id DESC LIMIT 500",id,id,id,id);
    }
    public List<HashMap<String,String>> details(long match,long player,boolean admin) throws Exception {
        if(!admin && query("SELECT id FROM matches WHERE id=? AND (player1_id=? OR player2_id=?)",match,player,player).isEmpty()) throw new IllegalArgumentException("Không có quyền xem trận này.");
        return query("SELECT g.turn_no,u.username,g.number,g.hint,g.created_at FROM guesses g JOIN users u ON u.id=g.player_id WHERE g.match_id=? ORDER BY g.turn_no",match);
    }
    public List<HashMap<String,String>> users() throws Exception {return query("SELECT id,username,display_name,role,blocked FROM users ORDER BY id");}
    public List<HashMap<String,String>> logs() throws Exception {return query("SELECT id,player1_id,player2_id,range_max,status,winner_id,end_reason,started_at,ended_at FROM matches ORDER BY id DESC LIMIT 500");}
    public HashMap<String,String> statistics() throws Exception {return query("SELECT (SELECT COUNT(*) FROM users) users,(SELECT COUNT(*) FROM matches) matches,(SELECT COUNT(*) FROM users WHERE blocked=1) blocked").get(0);}
    public void block(long admin,long target,boolean blocked) throws Exception {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try(PreparedStatement s=c.prepareStatement("UPDATE users SET blocked=? WHERE id=? AND role<>'ADMIN'");
                PreparedStatement log=c.prepareStatement("INSERT INTO admin_logs(admin_id,target_id,action) VALUES(?,?,?)")) {
                bind(s,blocked,target);if(s.executeUpdate()!=1) throw new IllegalArgumentException("Không tìm thấy tài khoản hoặc tài khoản là ADMIN.");
                bind(log,admin,target,blocked?"BLOCK":"UNBLOCK");log.executeUpdate();c.commit();
            } catch(Exception ex){c.rollback();throw ex;}
        }
    }
}
