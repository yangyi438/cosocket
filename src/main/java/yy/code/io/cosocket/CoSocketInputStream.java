package yy.code.io.cosocket;

import co.paralleluniverse.fibers.Suspendable;

import java.io.IOException;
import java.io.InputStream;


public final class CoSocketInputStream extends InputStream
{

    private final CoSocket socket;

    CoSocketInputStream(CoSocket impl) throws IOException {
        assert impl != null;
        socket = impl;
    }


    @Suspendable
    public int read(byte b[]) throws IOException {
        return read(b, 0, b.length);
    }

    @Suspendable
    public int read(byte b[], int off, int length) throws IOException {
        return socket.read(b, off, length,true);
    }

    @Suspendable
    public int read() throws IOException {
        return socket.readBytes();
    }

    public long skip(long numHopeSkip) throws IOException {
        return socket.skip(numHopeSkip);
    }


    public int available() throws IOException {
        return socket.available();
    }


    public void close() throws IOException {
        CoSocket socket = this.socket;
        socket.shutdownInput();
    }

}
