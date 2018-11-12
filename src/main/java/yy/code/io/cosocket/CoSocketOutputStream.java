package yy.code.io.cosocket;

import co.paralleluniverse.fibers.Suspendable;

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
    @Suspendable
    public void write(int b) throws IOException {
        socket.write(b, true);
    }

    @Suspendable
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }

    @Suspendable
    public void write(byte b[], int off, int len) throws IOException {
        socket.write(b, off, len, true);
    }

    @Suspendable
    public void flush() throws IOException {
        socket.flush(true);
    }


    public void close() throws IOException {
        //取消手动刷新了 调用者需要自己手动调用flush 操作
        socket.shutdownOutput();
    }
}
