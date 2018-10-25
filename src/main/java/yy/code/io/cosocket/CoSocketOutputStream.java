package yy.code.io.cosocket;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by ${good-yy} on 2018/10/22.
 */
public class CoSocketOutputStream extends OutputStream {

    private CoSocket socket;

    public CoSocketOutputStream(CoSocket socket) {
        this.socket = socket;
    }

    public void write(int b) throws IOException {
        socket.write(b, true);
    }


    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }


    public void write(byte b[], int off, int len) throws IOException {
        socket.write(b, off, len, true);
    }


    public void flush() throws IOException {
        socket.flush(true);
    }


    public void close() throws IOException {
        //关闭之前手动刷新一波
        socket.flush(true);
        socket.shutdownOutput();
    }
}
