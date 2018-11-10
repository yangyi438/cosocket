package yy.code.io.cosocket.config;


/**
 * Created by ${good-yy} on 2018/10/7.
 */
public final class CoSocketConfig {


    public int getMaxWriteSizePerOnce() {
        return maxWriteSizePerOnce;
    }

    public void setMaxWriteSizePerOnce(int maxWriteSizePerOnce) {
        assert maxWriteSizePerOnce > 0;
        this.maxWriteSizePerOnce = maxWriteSizePerOnce;
    }
    //我们的io线程负责处理写的任务的时候,不堵塞的情况下,一次最多写次数,
    //超过这个次数,做成一个task.延迟下执行,方便执行其他任务
    private int maxWriteSizePerOnce = 16;
    //连接超时默认就给3秒的时间了,我们的连接是必须要给连接超时的,不允许无限的不超时的连接
    private int connectionMilliSeconds = 3 * 1024;
    //三秒读超时的事件
    private int soTimeout = 3000;
    private int sendBufferSize;
    private int receiveBufferSize;

    //最少写阻塞一秒之后才会抛出SocketTimeOutException
    private int initialBlockMilliSeconds = 1000;
    //200毫秒一k的数据,默认数据
    private int milliSecondPer1024B =200;


    public void setConnectionMilliSeconds(int connectionMilliSeconds) {
        this.connectionMilliSeconds = connectionMilliSeconds;
    }

    public int getConnectionMilliSeconds() {
        return connectionMilliSeconds;
    }

    public void setSoTimeout(int soTimeout) {
        this.soTimeout = soTimeout;
    }

    public int getSoTimeout() {
        return soTimeout;
    }

    public void setSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public void setReceiveBufferSize(int receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setInitialBlockMilliSeconds(int initialBlockMilliSeconds) {
        if (initialBlockMilliSeconds <= 0) {
            throw new IllegalArgumentException();
        }
        this.initialBlockMilliSeconds = initialBlockMilliSeconds;
    }

    public int getInitialBlockMilliSeconds() {
        return initialBlockMilliSeconds;
    }

    public void setMilliSecondPer1024B(int milliSecondPer1024B) {
        if (milliSecondPer1024B <= 0) {
            throw new IllegalArgumentException();
        }
        this.milliSecondPer1024B = milliSecondPer1024B;
    }

    public int getMilliSecondPer1024B() {
        return milliSecondPer1024B;
    }
}
