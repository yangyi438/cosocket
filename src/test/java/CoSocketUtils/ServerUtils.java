package CoSocketUtils;

import co.paralleluniverse.fibers.Suspendable;
import yy.code.io.cosocket.CoSocket;
import yy.code.io.cosocket.ServerCoSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Created by ${good-yy} on 2018/11/4.
 */
public class ServerUtils {

    @Suspendable
    public static void StartCoServerAndAccept1Rw(int port) throws IOException {
        ServerCoSocket serverSocket = new ServerCoSocket(port);
        new Thread(() -> {
            CoSocket accept = null;
            try {
                while (true) {
                    accept = serverSocket.accept();
                    accept.setInitialFlushBlockMilliSeconds(10000);
                    accept.setSoTimeout(1000);
                    InputStream inputStream = accept.getInputStream();
                    OutputStream outputStream = accept.getOutputStream();
                    int i;
                    byte[] reads = new byte[1024];
                    while ((i = inputStream.read(reads)) != -1) {
                        outputStream.write(reads,0,i);
                        outputStream.flush();
                        i = -1;
                    }
                }
            } catch (IOException e) {
                if (accept != null) {
                    try {
                        accept.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                e.printStackTrace();
            }
        }).start();
    }

    public static void StartServerAndAccept1Rw(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        new Thread(() -> {
            Socket accept = null;
            try {
                accept = serverSocket.accept();
                byte[] reads = new byte[2048];
                InputStream inputStream = accept.getInputStream();
                OutputStream outputStream = accept.getOutputStream();
                int i;
                while ((i = inputStream.read(reads)) != -1) {
                    outputStream.write(reads,0,i);
                    outputStream.flush();
                }
            } catch (IOException e) {
                if (accept != null) {
                    try {
                        accept.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                e.printStackTrace();
            }
        }).start();
    }
}
