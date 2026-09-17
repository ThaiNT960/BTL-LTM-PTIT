package server;

import java.util.*;

/** Persistence boundary; the production implementation uses MySQL/JDBC. */
public interface Store {
    record Account(long id,String username,String displayName,String hash,String role,boolean blocked) {}
    Account find(String username) throws Exception;
    void register(String username,String hash) throws Exception;
    void profile(long id,String displayName,String hash) throws Exception;
    long createMatch(long a,long b,int range) throws Exception;
    void guess(long match,int turn,long player,Integer number,String hint) throws Exception;
    void finish(long match,long winner,String reason) throws Exception;
    void chat(long match,long sender,String text) throws Exception;
    List<HashMap<String,String>> ranking() throws Exception;
    List<HashMap<String,String>> history(long player) throws Exception;
    List<HashMap<String,String>> details(long match,long player,boolean admin) throws Exception;
    List<HashMap<String,String>> users() throws Exception;
    List<HashMap<String,String>> logs() throws Exception;
    void block(long admin,long target,boolean blocked) throws Exception;
    HashMap<String,String> statistics() throws Exception;
}
