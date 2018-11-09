package socket;

import yy.code.io.cosocket.CoSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Random;

/**
 * Created by ${good-yy} on 2018/11/4.
 */
public class CoSocketUtils {
    public static void startClient(CoSocket coSocket) throws IOException {
        coSocket.setInitialFlushBlockMilliSeconds(100000);
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
                } catch (SocketTimeoutException e2) {
                    e2.printStackTrace();
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
