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
    /**
     * Reads a single byte from the socket.
     */
    public int read() throws IOException {
        if (eof) {
            return -1;
        }
        temp = new byte[1];
        int n = read(temp, 0, 1);
        if (n <= 0) {
            return -1;
        }
        return temp[0] & 0xff;
    }

    /**
     * Skips n bytes of input.
     * @param numbytes the number of bytes to skip
     * @return  the actual number of bytes skipped.
     * @exception IOException If an I/O error has occurred.
     */
    public long skip(long numbytes) throws IOException {
        if (numbytes <= 0) {
            return 0;
        }
        long n = numbytes;
        int buflen = (int) Math.min(1024, n);
        byte data[] = new byte[buflen];
        while (n > 0) {
            int r = read(data, 0, (int) Math.min((long) buflen, n));
            if (r < 0) {
                break;
            }
            n -= r;
        }
        return numbytes - n;
    }

    /**
     * Returns the number of bytes that can be read without blocking.
     * @return the number of immediately available bytes
     */
    public int available() throws IOException {
        return impl.available();
    }

    /**
     * Closes the stream.
     */
    private boolean closing = false;
    public void close() throws IOException {
        // Prevent recursion. See BugId 4484411
        //todo
    }

}
