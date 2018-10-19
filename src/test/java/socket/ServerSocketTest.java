package socket;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;

/**
 * Created by ${good-yy} on 2018/10/2.
 */

public class ServerSocketTest {
    public static void main(String[] args) throws IOException {
        Socket socket = new Socket();

        socket.getKeepAlive();
        System.out.println(123);
        ServerSocket serverSocket = new ServerSocket(8080);
        ServerSocketChannel channel = serverSocket.getChannel();
    }
}
