package yy.code.io.cosocket.config;


/**
 * Created by ${good-yy} on 2018/10/7.
 */
public final class CoSocketConfig {


    //default 64k
    private long maxInputBufferSize = 64*1024;
    private int maxReadSizePerOnce;
    //连接超时默认就给3秒的时间了,我们的连接是必须要给连接超时的
    private int connectionMilliSeconds = 3 * 1024;

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

    public void setConnectionMilliSeconds(int connectionMilliSeconds) {
        this.connectionMilliSeconds = connectionMilliSeconds;
    }

    public int getConnectionMilliSeconds() {
        return connectionMilliSeconds;
    }
}
