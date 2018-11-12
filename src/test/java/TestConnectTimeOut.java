import CoSocketUtils.ServerUtils;
import socket.CoSocketUtils;
import yy.code.io.cosocket.CoSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * Created by ${good-yy} on 2018/11/4.
 */
public class TestConnectTimeOut {
    public static void main(String[] args) throws IOException {
        CoSocket coSocket = new CoSocket();
        InetSocketAddress endpoint = new InetSocketAddress("10.24.24.1", 8080);
        try {
            System.out.println(System.currentTimeMillis());
            coSocket.connect(endpoint, 2000);

        } catch (SocketTimeoutException e) {
            System.out.println(System.currentTimeMillis());
            e.printStackTrace();
        }
        ServerUtils.StartCoServerAndAccept1Rw(8080);

        coSocket.connect(endpoint);
        CoSocketUtils.startClient(coSocket);
    }
}
