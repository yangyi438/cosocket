package yy.code.io.cosocket.config;


/**
 * Created by ${good-yy} on 2018/10/7.
 */
public final class CoSocketConfig {


    //default 64k
    private long maxInputBufferSize = 64*1024;
    private int maxReadSizePerOnce;

    public void setMaxWriteBufferSIze(long maxWriteBufferSIze) {
        this.maxWriteBufferSIze = maxWriteBufferSIze;
    }

    private long maxWriteBufferSIze = 64 * 1024;
    public long getMaxInputBufferSize() {
        return maxInputBufferSize;
    }

    public void setMaxInputBufferSize(long maxInputBufferSize) {
        this.maxInputBufferSize = maxInputBufferSize;
    }

    public long getMaxWriteBufferSize() {
        return maxWriteBufferSIze;
    }

    public int getMaxReadSizePerOnce() {
        return maxReadSizePerOnce;
    }

    public void setMaxReadSizePerOnce(int maxReadSizePerOnce) {
        this.maxReadSizePerOnce = maxReadSizePerOnce;
    }
}
