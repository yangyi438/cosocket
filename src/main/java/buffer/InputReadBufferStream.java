package buffer;

import io.netty.buffer.ByteBuf;
import io.netty.util.internal.shaded.org.jctools.queues.SpscLinkedQueue;
import yy.code.io.cosocket.config.CoSocketConfig;

/**
 * Created by ${good-yy} on 2018/10/4.
 * 单生产者,单消费者的buffer,生产者一般只为netty的io线程
 *
 */
public class InputReadBufferStream {

    public void init(CoSocketConfig option) {
        this.maxBufferSize = option.getMaxInputBufferSize();
    }


    //我们的io线程总读取的字节数,总数量目前不考虑超过Long.MAX
    private long readTotal;

    private long maxBufferSize;

    //我们的使用者读取的buffer总数,
    //只要 inputBuffers 获取一个小的ByteBuf 就立即更新这个变量
    private volatile long consumerTotal;

    private final SpscLinkedQueue<ByteBuf> inputBuffers = new SpscLinkedQueue<ByteBuf>();


    public void appendBuffer(ByteBuf buf) {
        readTotal += buf.readableBytes();
        inputBuffers.offer(buf);
    }

    public ByteBuf getNextBuf() {
        ByteBuf poll = inputBuffers.poll();
        if (poll != null) {
            consumerTotal += poll.readableBytes();
        }
        return poll;
    }

    public boolean isFull() {
        long bufferTotal = readTotal - consumerTotal;
        return bufferTotal > maxBufferSize;
    }

    long getBufferSize() {
        return readTotal - consumerTotal;
    }

}
