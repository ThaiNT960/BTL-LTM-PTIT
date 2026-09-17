package server;
import common.Message;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientHandler implements Runnable {
    final Socket socket;
    final Server server;
    final BlockingQueue<Message> outbound=new ArrayBlockingQueue<>(256);
    final AtomicBoolean closed=new AtomicBoolean();
    volatile long lastSeen=System.nanoTime();
    Store.Account user;
    GameRoom room;
    long lastMatch;
    String lastOpponent="";
    ClientHandler(Socket socket,Server server) {this.socket=socket;this.server=server;}
    void send(Message message) {if(!closed.get()&&!outbound.offer(message))close();}
    public void run() {
        try {
            socket.setSoTimeout(15000);
            ObjectOutputStream out=new ObjectOutputStream(socket.getOutputStream());out.flush();
            ObjectInputStream in=Message.input(socket.getInputStream());
            Thread writer=new Thread(()->{
                try {while(!closed.get()) {Message m=outbound.poll(1,TimeUnit.SECONDS);if(m!=null){out.writeObject(m);out.reset();out.flush();}}}
                catch(Exception ex){close();}
            },"writer-"+socket.getPort());writer.setDaemon(true);writer.start();
            while(!closed.get()) {
                Object obj=in.readObject();lastSeen=System.nanoTime();
                if(!(obj instanceof Message m))throw new IOException("Invalid message");
                if(m.type()==null||m.data()==null||m.rows()==null||m.data().size()>20||!m.rows().isEmpty())throw new IOException("Invalid payload");
                for(var e:m.data().entrySet()) if(e.getKey()==null||e.getValue()==null||e.getValue().length()>4096)throw new IOException("Invalid field");
                server.receive(this,m);
            }
        } catch(Exception ex) { /* EOF and read timeout are connection loss. */ }
        finally {close();}
    }
    void close() {
        if(closed.compareAndSet(false,true)) {
            try{socket.close();}catch(IOException ignored){}
            server.disconnected(this);
        }
    }
}
