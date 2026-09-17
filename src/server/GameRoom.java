package server;

/** Accessed only while holding Server's state lock. Secrets never enter outbound messages. */
final class GameRoom {
    final long id;
    final ClientHandler a,b;
    final int range;
    Integer secretA,secretB;
    ClientHandler current;
    int turn;
    long deadlineNanos;
    boolean ended;
    GameRoom(long id,ClientHandler a,ClientHandler b,int range) {this.id=id;this.a=a;this.b=b;this.range=range;}
    ClientHandler other(ClientHandler c) {if(c==a)return b;if(c==b)return a;throw new IllegalArgumentException("Không thuộc trận.");}
}
