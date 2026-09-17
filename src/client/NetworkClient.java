package client;
import common.Message;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Blocking socket work stays off the JavaFX application thread. */
public final class NetworkClient implements AutoCloseable {
    private final Consumer<Message> receiver;
    private final Consumer<String> failure;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"network-writer");t.setDaemon(true);return t;});
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"heartbeat");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private volatile Socket socket;
    private ObjectOutputStream output;
    public NetworkClient(Consumer<Message> receiver,Consumer<String> failure) {
        this.receiver=receiver;this.failure=failure;
        heartbeat.scheduleAtFixedRate(()->{if(socket!=null&&!closed)send(Message.of("PING"));},3,3,TimeUnit.SECONDS);
    }
    public void send(Message message) {
        if(closed)return;
        writer.submit(()->{
            try {
                if(socket==null)connect();
                output.writeObject(message);output.reset();output.flush();
            }catch(Exception ex){failed();}
        });
    }
    private void connect() throws Exception {
        Socket s=new Socket();socket=s;s.connect(new InetSocketAddress(System.getProperty("server.host","127.0.0.1"),Integer.getInteger("server.port",12345)),5000);
        s.setSoTimeout(15000);s.setTcpNoDelay(true);
        output=new ObjectOutputStream(s.getOutputStream());output.flush();
        ObjectInputStream input=Message.input(s.getInputStream());socket=s;
        Thread reader=new Thread(()->{
            try {while(!closed){Object obj=input.readObject();if(obj instanceof Message m)receiver.accept(m);else throw new IOException("Invalid message");}}
            catch(Exception ex){failed();}
        },"network-reader");reader.setDaemon(true);reader.start();
    }
    private synchronized void failed() {if(!closed){close();failure.accept("Không kết nối được hoặc đã mất kết nối với server. Kiểm tra server, IP và cổng TCP.");}}
    public synchronized void close(){closed=true;heartbeat.shutdownNow();writer.shutdownNow();if(socket!=null)try{socket.close();}catch(IOException ignored){}}
}
