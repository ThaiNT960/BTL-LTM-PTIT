package server;

import common.Message;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.security.SecureRandom;

/** Same Socket / ClientHandler / GameRoom architecture as PenaltyShootoutClient. */
public final class Server implements AutoCloseable {
    private static final Logger LOG=Logger.getLogger(Server.class.getName());
    private final Store db;
    private final ServerSocket listener;
    private final Map<String,ClientHandler> online=new HashMap<>();
    private final Set<ClientHandler> connections=new HashSet<>();
    private final Map<String,Invitation> invitations=new HashMap<>();
    private final Map<Long,GameRoom> rooms=new HashMap<>();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private final SecureRandom random=new SecureRandom();
    private final long turnMillis;
    private volatile boolean closing;
    private record Invitation(String id,ClientHandler from,ClientHandler to,int range,long match,long expires) {}

    public Server(Store db,int port,long turnMillis) throws Exception {
        this.db=db;this.turnMillis=turnMillis;listener=new ServerSocket(port);
        scheduler.scheduleAtFixedRate(this::tick,100,100,TimeUnit.MILLISECONDS);
    }
    public int port(){return listener.getLocalPort();}
    public void listen() throws Exception {
        while(!closing) {
            Socket socket=listener.accept();socket.setTcpNoDelay(true);
            ClientHandler client=new ClientHandler(socket,this);
            synchronized(this){if(connections.size()>=200){socket.close();continue;}connections.add(client);}
            Thread t=new Thread(client,"client-"+socket.getPort());t.setDaemon(true);t.start();
        }
    }
    synchronized void receive(ClientHandler c,Message m) {
        if(closing||c.closed.get())return;
        try {
            switch(m.type()) {
                case "PING" -> c.send(Message.of("PONG"));
                case "REGISTER" -> {
                    require(c.user==null,"Bạn đã đăng nhập.");
                    String name=m.get("username").trim();validateName(name);validatePassword(m.get("password"));
                    require(db.find(name)==null,"Tên tài khoản đã tồn tại.");
                    db.register(name,Passwords.hash(m.get("password")));c.send(Message.of("REGISTERED","text","Đăng ký thành công."));
                }
                case "LOGIN" -> {
                    require(c.user==null,"Bạn đã đăng nhập.");
                    var u=db.find(m.get("username").trim());
                    require(u!=null && Passwords.verify(m.get("password"),u.hash()),"Sai tài khoản hoặc mật khẩu.");
                    require(!u.blocked(),"Tài khoản đã bị khóa.");
                    require(!online.containsKey(u.username()),"Tài khoản đang đăng nhập ở nơi khác.");
                    c.user=u;online.put(u.username(),c);
                    c.send(Message.of("LOGIN_OK","username",u.username(),"role",u.role()));lobby(c);broadcastUsers();
                }
                default -> {require(c.user!=null,"Bạn cần đăng nhập.");authenticated(c,m);}
            }
        } catch(IllegalArgumentException ex) {c.send(Message.of("ERROR","request",m.type(),"text",ex.getMessage()));}
        catch(Exception ex) {
            LOG.log(Level.SEVERE,"Request failed: "+m.type(),ex);
            c.send(Message.of("ERROR","request",m.type(),"text","Không thể xử lý yêu cầu hoặc lưu dữ liệu. Hãy thử lại."));
        }
    }
    private void authenticated(ClientHandler c,Message m) throws Exception {
        switch(m.type()) {
            case "LOBBY" -> {require(c.room==null,"Bạn vẫn đang trong trận.");cancelInvitations(c);c.lastMatch=0;lobby(c);}
            case "LOGOUT" -> {leave(c,"QUIT");require(c.room==null,"Đang lưu kết quả trận đấu, hãy thử lại.");cancelInvitations(c);online.remove(c.user.username());c.user=null;c.lastMatch=0;c.send(Message.of("LOGOUT_OK"));broadcastUsers();}
            case "PROFILE" -> {
                var u=db.find(c.user.username());
                var stats=db.ranking().stream().filter(r->r.get("username").equals(u.username())).findFirst().orElse(new HashMap<>());
                Message result=Message.of("PROFILE","displayName",u.displayName());result.data().putAll(stats);c.send(result);
            }
            case "PROFILE_UPDATE" -> {
                String name=m.get("displayName").trim();require(!name.isEmpty()&&name.length()<=80,"Tên hiển thị dài từ 1 đến 80 ký tự.");
                String hash=null;
                if(!m.get("password").isEmpty()) {require(Passwords.verify(m.get("oldPassword"),db.find(c.user.username()).hash()),"Mật khẩu hiện tại không đúng.");validatePassword(m.get("password"));hash=Passwords.hash(m.get("password"));}
                db.profile(c.user.id(),name,hash);c.user=db.find(c.user.username());c.send(Message.of("INFO","text","Đã cập nhật hồ sơ."));broadcastUsers();
            }
            case "INVITE","REMATCH" -> invite(c,m);
            case "INVITE_REPLY" -> reply(c,m);
            case "SECRET" -> {
                GameRoom r=room(c,m);require(r.current==null,"Trận đã bắt đầu.");int n=number(m,r.range);
                if(c==r.a){require(r.secretA==null,"Đã chọn số bí mật.");r.secretA=n;}
                else {require(r.secretB==null,"Đã chọn số bí mật.");r.secretB=n;}
                c.send(Message.of("SECRET_OK"));
                if(r.secretA!=null&&r.secretB!=null){r.current=random.nextBoolean()?r.a:r.b;startTurn(r);}
            }
            case "GUESS" -> {
                GameRoom r=room(c,m);require(r.current==c,"Chưa đến lượt bạn.");require(m.number("turn")==r.turn,"Lượt đoán đã hết hiệu lực.");
                if(System.nanoTime()>=r.deadlineNanos){timeout(r);throw new IllegalArgumentException("Đã hết thời gian của lượt này.");}
                int n=number(m,r.range),target=c==r.a?r.secretB:r.secretA;
                String hint=n==target?"EXACT":n<target?"HIGHER":"LOWER";
                db.guess(r.id,r.turn,c.user.id(),n,hint);
                // Freeze an exact guess before persistence retry: no timeout may award the other player.
                if(n==target){r.ended=true;pendingFinish.put(r.id,new Completion(r,c,"EXACT"));}
                both(r,Message.of("GUESS_RESULT","match",""+r.id,"turn",""+r.turn,"player",c.user.username(),"number",""+n,"hint",hint));
                if(n==target)complete(r,c,"EXACT");else{r.current=r.other(c);startTurn(r);}
            }
            case "CHAT" -> {
                GameRoom r=room(c,m);String text=m.get("text").trim();require(!text.isEmpty()&&text.length()<=1000,"Tin nhắn dài từ 1 đến 1000 ký tự.");
                db.chat(r.id,c.user.id(),text);both(r,Message.of("CHAT","match",""+r.id,"player",c.user.username(),"text",text));
            }
            case "LEAVE" -> {leave(c,"QUIT");require(c.room==null,"Đang lưu kết quả trận đấu, hãy thử lại.");cancelInvitations(c);c.lastMatch=0;c.send(Message.of("LEFT"));lobby(c);}
            case "HISTORY" -> c.send(Message.rows("HISTORY",db.history(c.user.id())));
            case "DETAILS" -> c.send(Message.rows("DETAILS",db.details(m.number("match"),c.user.id(),c.user.role().equals("ADMIN"))));
            case "RANKING" -> c.send(Message.rows("RANKING",db.ranking()));
            case "ADMIN_STATS","ADMIN_USERS","ADMIN_LOGS","ADMIN_BLOCK" -> admin(c,m);
            default -> throw new IllegalArgumentException("Lệnh không hợp lệ.");
        }
    }
    private void admin(ClientHandler c,Message m) throws Exception {
        require(c.user.role().equals("ADMIN"),"Không có quyền quản trị.");
        switch(m.type()) {
            case "ADMIN_STATS" -> {var data=db.statistics();data.put("online",""+online.size());data.put("activeMatches",""+rooms.size());c.send(new Message("ADMIN_STATS",data,new ArrayList<>()));}
            case "ADMIN_USERS" -> c.send(Message.rows("ADMIN_USERS",db.users()));
            case "ADMIN_LOGS" -> c.send(Message.rows("ADMIN_LOGS",db.logs()));
            case "ADMIN_BLOCK" -> {
                long target=m.number("user");boolean blocked=Boolean.parseBoolean(m.get("blocked"));db.block(c.user.id(),target,blocked);
                for(ClientHandler other:new ArrayList<>(online.values()))if(other.user.id()==target && blocked)other.close();
                c.send(Message.of("INFO","text","Đã cập nhật tài khoản."));
            }
        }
    }
    private void invite(ClientHandler c,Message m) throws Exception {
        require(c.room==null,"Bạn đang chơi.");ClientHandler to=online.get(m.get("opponent"));
        require(to!=null&&to!=c&&!to.closed.get()&&to.room==null,"Đối thủ không sẵn sàng.");
        long rawRange=m.number("range");require(rawRange==100||rawRange==1000||rawRange==10000,"Phạm vi không hợp lệ.");int range=(int)rawRange;
        long match=0;
        if(m.type().equals("REMATCH")) {match=m.number("match");require(match>0&&c.lastMatch==match&&to.lastMatch==match&&c.lastOpponent.equals(to.user.username()),"Đối thủ đã rời phòng hoặc trận không hợp lệ.");}
        require(invitations.values().stream().noneMatch(i->i.from==c||i.to==c||i.from==to||i.to==to),"Một người đang có lời mời chờ phản hồi.");
        String id=UUID.randomUUID().toString();invitations.put(id,new Invitation(id,c,to,range,match,System.nanoTime()+TimeUnit.SECONDS.toNanos(60)));
        to.send(Message.of("INVITED","id",id,"opponent",c.user.username(),"range",""+range,"rematch",""+(match>0)));
        c.send(Message.of("INVITE_SENT","text","Đã gửi yêu cầu tới "+to.user.username()));
    }
    private void reply(ClientHandler c,Message m) throws Exception {
        Invitation i=invitations.get(m.get("id"));require(i!=null&&i.to==c,"Lời mời không còn hiệu lực.");
        require(System.nanoTime()<i.expires,"Lời mời đã hết hạn.");
        if(!m.get("accept").equals("true")){invitations.remove(i.id);i.from.send(Message.of("INFO","text","Đối thủ từ chối lời mời."));return;}
        require(i.from.user!=null&&!i.from.closed.get()&&i.from.room==null&&c.room==null,"Người chơi không còn sẵn sàng.");
        long id=db.createMatch(i.from.user.id(),c.user.id(),i.range);
        GameRoom r=new GameRoom(id,i.from,c,i.range);rooms.put(id,r);i.from.room=r;c.room=r;
        invitations.remove(i.id);cancelInvitations(c);cancelInvitations(i.from);
        for(ClientHandler p:List.of(r.a,r.b))p.send(Message.of("MATCH_CREATED","match",""+id,"range",""+r.range,"opponent",r.other(p).user.username()));
        broadcastUsers();
    }
    private GameRoom room(ClientHandler c,Message m) {
        require(c.room!=null&&!c.room.ended&&c.room.id==m.number("match"),"Trận đấu không còn hoạt động.");return c.room;
    }
    private int number(Message m,int range) {long n=m.number("number");require(n>=1&&n<=range,"Nhập số nguyên từ 1 đến "+range);return (int)n;}
    private void startTurn(GameRoom r) {
        r.turn++;r.deadlineNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(turnMillis);
        both(r,Message.of("TURN","match",""+r.id,"turn",""+r.turn,"player",r.current.user.username(),"remainingMillis",""+turnMillis));
    }
    private void timeout(GameRoom r) throws Exception {
        db.guess(r.id,r.turn,r.current.user.id(),null,"TIMEOUT");
        both(r,Message.of("GUESS_RESULT","match",""+r.id,"turn",""+r.turn,"player",r.current.user.username(),"number","—","hint","TIMEOUT"));
        r.current=r.other(r.current);startTurn(r);
    }
    private record Completion(GameRoom room,ClientHandler winner,String reason) {}
    private final Map<Long,Completion> pendingFinish=new HashMap<>();
    private void complete(GameRoom r,ClientHandler winner,String reason) throws Exception {
        r.ended=true;pendingFinish.putIfAbsent(r.id,new Completion(r,winner,reason));
        db.finish(r.id,winner.user.id(),reason);
        both(r,Message.of("MATCH_END","match",""+r.id,"winner",winner.user.username(),"reason",reason));
        for(ClientHandler p:List.of(r.a,r.b)){p.lastMatch=r.id;p.lastOpponent=r.other(p).user.username();p.room=null;}
        pendingFinish.remove(r.id);rooms.remove(r.id);broadcastUsers();
        LOG.info("Match "+r.id+" finished: "+reason);
    }
    private void leave(ClientHandler c,String reason) throws Exception {if(c.room!=null&&!c.room.ended)complete(c.room,c.room.other(c),reason);}
    private void both(GameRoom r,Message m){r.a.send(m);r.b.send(m);}
    private void lobby(ClientHandler c) throws Exception {c.send(Message.rows("RANKING",db.ranking()));c.send(Message.rows("HISTORY",db.history(c.user.id())));broadcastUsers();}
    private void broadcastUsers() throws Exception {
        Map<String,String> wins=new HashMap<>();for(var row:db.ranking())wins.put(row.get("username"),row.get("wins"));
        List<HashMap<String,String>> rows=new ArrayList<>();
        for(var c:online.values())if(!c.closed.get())rows.add(Message.of("","username",c.user.username(),"wins",wins.getOrDefault(c.user.username(),"0"),"state",c.room==null?"Rảnh":"Đang chơi").data());
        rows.sort(Comparator.comparing(r->r.get("username")));Message m=Message.rows("USERS",rows);
        for(var c:new ArrayList<>(online.values()))c.send(m);
    }
    private void cancelInvitations(ClientHandler c) {
        var expired=invitations.values().stream().filter(i->i.from==c||i.to==c).toList();
        for(var i:expired){invitations.remove(i.id);i.from.send(Message.of("INVITE_CANCELLED","id",i.id));i.to.send(Message.of("INVITE_CANCELLED","id",i.id));}
    }
    synchronized void disconnected(ClientHandler c) {
        connections.remove(c);cancelInvitations(c);
        if(c.user!=null) {
            try{leave(c,"DISCONNECT");}catch(Exception ex){LOG.log(Level.SEVERE,"Result pending persistence",ex);}
            online.remove(c.user.username(),c);
            try{broadcastUsers();}catch(Exception ex){LOG.log(Level.WARNING,"Cannot refresh users",ex);}
        }
    }
    private synchronized void tick() {
        if(closing)return;
        try {
            for(var completion:new ArrayList<>(pendingFinish.values()))complete(completion.room,completion.winner,completion.reason);
            for(var r:new ArrayList<>(rooms.values()))if(!r.ended&&r.current!=null&&System.nanoTime()>=r.deadlineNanos)timeout(r);
            for(var i:new ArrayList<>(invitations.values()))if(System.nanoTime()>=i.expires)cancelInvitations(i.from);
        }catch(Exception ex){LOG.log(Level.WARNING,"Scheduled operation will retry",ex);}
    }
    static void require(boolean condition,String text){if(!condition)throw new IllegalArgumentException(text);}
    private static void validateName(String name){require(name.matches("[A-Za-z0-9_]{3,32}"),"Tên tài khoản gồm 3–32 chữ, số hoặc dấu gạch dưới.");}
    private static void validatePassword(String password){require(password.length()>=6&&password.length()<=128,"Mật khẩu dài từ 6 đến 128 ký tự.");}
    public synchronized void close() {
        closing=true;scheduler.shutdownNow();try{listener.close();}catch(Exception ignored){}
        for(var c:new ArrayList<>(connections)) {c.closed.set(true);try{c.socket.close();}catch(Exception ignored){}}
    }
    public static void main(String[] args) throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("logs"));
        FileHandler log=new FileHandler("logs/server-%g.log",1000000,5,true);log.setFormatter(new SimpleFormatter());LOG.addHandler(log);
        Server server=new Server(new DatabaseManager(),Integer.getInteger("server.port",12345),30000);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Number Guessing TCP Server: "+server.port());server.listen();
    }
}
