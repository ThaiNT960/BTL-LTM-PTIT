package client;
import common.Message;
import java.io.*;
import java.util.*;

/** Separate admin entry point so the existing JavaFX game UI stays unchanged. */
public final class AdminConsole {
    public static void main(String[] args) throws Exception {
        NetworkClient network=new NetworkClient(m->{
            if(!m.type().equals("PONG")){System.out.println(m.type()+" "+m.data());for(var r:m.rows())System.out.println(r);}
        },System.out::println);
        Scanner scanner=new Scanner(System.in);
        System.out.print("Admin username: ");String user=scanner.nextLine();
        Console console=System.console();String password;
        if(console!=null)password=new String(console.readPassword("Password: "));
        else {System.out.print("Password (visible in IDE console): ");password=scanner.nextLine();}
        network.send(Message.of("LOGIN","username",user,"password",password));
        System.out.println("Commands: stats | users | logs | details MATCH_ID | block USER_ID | unblock USER_ID | quit");
        while(scanner.hasNextLine()) {
            String[] p=scanner.nextLine().trim().split("\\s+");
            try {
                switch(p[0]) {
                    case "stats" -> network.send(Message.of("ADMIN_STATS"));
                    case "users" -> network.send(Message.of("ADMIN_USERS"));
                    case "logs" -> network.send(Message.of("ADMIN_LOGS"));
                    case "details" -> network.send(Message.of("DETAILS","match",p[1]));
                    case "block","unblock" -> network.send(Message.of("ADMIN_BLOCK","user",p[1],"blocked",""+p[0].equals("block")));
                    case "quit" -> {network.close();return;}
                    default -> System.out.println("Unknown command");
                }
            }catch(RuntimeException ex){System.out.println("Command needs a numeric ID.");}
        }network.close();
    }
}
