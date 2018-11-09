package socket;

import CoSocketUtils.ServerUtils;
import yy.code.io.cosocket.CoSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Random;

/**
 * Created by ${good-yy} on 2018/10/2.
 */

public class ServerSocketTest {
    //测试发送数据和读数据是否相同
    public static void main(String[] args) throws IOException {
        ServerUtils.StartCoServerAndAccept1Rw(8080);
        try {
            startClient();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private static void startClient() throws IOException {
        CoSocket coSocket = new CoSocket();
        coSocket.setInitialFlushBlockMilliSeconds(100000);
        coSocket.connect(new InetSocketAddress("127.0.0.1", 8080));
        System.out.println("end connect");
        byte[] bytes = new byte[102400];
        byte[] bytes2 = new byte[102400];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
            bytes2[i] = (byte) i;
        }
        while (true) {
            coSocket.write(bytes, 0, bytes.length, true);
            coSocket.flush(true);
            System.out.println("-----");

            int count = 0;
            while (true) {
              //  System.out.println(System.currentTimeMillis());
                int read = 0;
                try {
                     read = coSocket.read(bytes2, count, bytes.length - count, true);
                } catch (SocketTimeoutException e) {
                    e.printStackTrace();
                  //  System.out.println(System.currentTimeMillis());
                    System.exit(0);
                }
                count += read;
                if (count == bytes.length) {
                    break;
                }
            }
            checkBytes(bytes, bytes2);
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) new Random().nextInt();
            }
        }
    }

    private static void checkBytes(byte[] bytes, byte[] bytes2) {
        for (int i = 0; i < bytes.length; i++) {
            if(bytes[i]!=bytes2[i]){
                 System.out.println("error");
                System.exit(0);
            }

        }
    }
}
