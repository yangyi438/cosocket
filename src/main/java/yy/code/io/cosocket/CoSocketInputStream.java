package yy.code.io.cosocket;

import java.io.IOException;
import java.io.InputStream;


public final class CoSocketInputStream extends InputStream
{

    private final CoSocket socket;

    CoSocketInputStream(CoSocket impl) throws IOException {
        assert impl != null;
        socket = impl;
    }



    public int read(byte b[]) throws IOException {
        return read(b, 0, b.length);
    }


    public int read(byte b[], int off, int length) throws IOException {
        return socket.read(b, off, length,true);
    }


    public int read() throws IOException {
        return socket.readBytes();
    }

    public long skip(long numHopeSkip) throws IOException {
        return socket.skip(numHopeSkip);
    }


    public int available() throws IOException {
        return socket.available();
    }

    /**
     * Closes input the stream.
     */
    private boolean closing = false;

    public void close() throws IOException {
        CoSocket socket = this.socket;
        socket.shutdownInput();
    }

}
