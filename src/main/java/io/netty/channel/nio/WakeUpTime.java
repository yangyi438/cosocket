package io.netty.channel.nio;

/**
 * Created by ${good-yy} on 2018/10/21.
 */
public final class WakeUpTime {

    private long currentNanoTime;

    public long getCurrentNanoTime() {
        return currentNanoTime;
    }

    public void setCurrentNanoTime(long currentNanoTime) {
        this.currentNanoTime = currentNanoTime;
    }
}

