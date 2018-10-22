package yy.code.io.cosocket;

/**
 * Created by ${good-yy} on 2018/10/20.
 * 防止读的时候由于对放发送的数据由于网络堵塞等原因,卡顿般的发送数据,导致我们使用CoSocket的线程花费大量cpu 挂起 恢复 读 挂起
 * 这样的流程,在这个中途收到读的事件之后稍微的等待一下, 在恢复和读之间给一个稍微的延迟,防止cpu的浪费,
 * 目前只是初步简单版本,使用netty的延迟调度,所以延迟会更高,但是可以确定这个是毫秒级别,对应用应该是不影响的,整体的响应事件还是取决于对面发送方传输的整体时间
 * 如果使用场景是高频率的需要发送,读取,而且读取和发送有相关连的关系,那么COSocket就不合适这样的场景
 * http这样的请求响应模式就是一个非常合适的场景,那种需要多次握手之类的协议就不是合适的场景
 * 在内网和外网环境中,这种延迟似乎有很大差距的,这都是以后需要解决的问题
 * 同时写操作也会发生超时的问题,控制写阻塞的控制程度
 * 总体上记录下读写有没有发生堵塞,之间的行为,适当修改读写堵塞之后,
 * 读写就绪之后,释放修改读写执行的时间
 */
public final class BlockingReadWriteControl {
    private static final long minBlockInterval = 1000 * 1000;
    //如果一毫秒以内读阻塞发生了两次,我们就挂起当前线程(协程)两毫秒,我们会最多增加这一次原计划读的两毫秒(大多情况),
    //对于请求响应模式的请求,基于tcp之上的协议,这个是非常有意义的
    // 发送方实在是太慢的原因
    //较小的延迟,恰当避免发送方的
    //同时我们要注意,tcp的慢启动的问题,这体现在我们connect之后,立刻读数据,对方还没有来得及发或者发足够的数据,
    //这个时候时候很容易读阻塞了,

    private long firstReadBlockNanoTime = 0;
    private long secondReadBlockNanoTime = 0;
    private long firstWriteBlockNanoTime = 0;
    private long secondWriteBlockNanoTime = 0;
    //private int sendBufferSize;
    //private int receiveBufferSize;

    //返回希望延迟唤醒使用CoSocket协程(线程)的时间
    public long reportReadActive(long wakeUpTime) {

        long currentTime = System.nanoTime();

        if (secondWriteBlockNanoTime == 0) {
            return nextReadTime(wakeUpTime, currentTime,500000);
        }

        long l = secondReadBlockNanoTime - firstReadBlockNanoTime;
        if (l < minBlockInterval) {
            resetRead();
            //延迟2毫秒,比较厉害的惩罚力度,
            return nextReadTime(wakeUpTime,currentTime,2000000);
        } else {
            resetRead();
            return nextReadTime(wakeUpTime, currentTime, 500000);
        }
    }

    private long nextReadTime(long wakeUpTime, long currentTime, long delayNanoTime) {
        long time = currentTime - wakeUpTime;
        //阻塞读有0.5毫秒了,就发现有新数据了,数据应该是很少的,延迟0.5毫秒读,等待更多的数据,可以被读取
        if (time > delayNanoTime) {
            return 0;
        }
        //补足0.5毫秒的时间
        long delay = delayNanoTime - time;
        if (delay < 2000) {
            //小于2微妙就忽略了,不需要延迟了,直接执行
            return 0;
        }
        return delay;
    }

    public long reportWriteAtive() {
        return 0L;
    }


    private void resetRead() {
        firstReadBlockNanoTime = secondReadBlockNanoTime;
        secondReadBlockNanoTime = 0;
    }

    //读了多少数据没有堵塞,isEmptyReceiveBuffer代表tcp接受缓存中的数据是不是已经读空了,读空了的话
    //我们的接受ByteBuff的数据没有被填充完毕
    public void reportReadNoneBlocking(int noneBlockingRead,boolean isEmptyReceiveBuffer) {

    }

    //写数据没有发生堵塞
    public void reportWriteNoneBlocking() {

    }

    //写数据写了0的数据
    public void reportWriteBlock() {
        if (firstWriteBlockNanoTime == 0) {
            firstWriteBlockNanoTime = System.nanoTime();
            return;
        }
        if (secondWriteBlockNanoTime == 0) {
            secondWriteBlockNanoTime = System.nanoTime();
        }
    }

    //读数据读到了0的数据
    public void reportReadBlock() {
        if (firstReadBlockNanoTime == 0) {
            firstReadBlockNanoTime = System.nanoTime();
            return;
        }
        if (secondReadBlockNanoTime == 0) {
            secondReadBlockNanoTime = System.nanoTime();
        }
    }

    //初始化两个缓存区的大小
    public void initSRBufferSizes(int sendBufferSize, int receiveBufferSize) {
        //  this.sendBufferSize = sendBufferSize;
        //   this.receiveBufferSize = receiveBufferSize;
    }

    public void onSendBufferChange(int sendBuffer) {
        //fixme
    }

    public void onReceiveBufferChange(int receiveBufferSize) {
        //fixme
    }


}
