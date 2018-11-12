package yy.code.io.cosokcet.bytebuf.pool;

import io.netty.buffer.ByteBuf;
import org.jctools.queues.MpmcArrayQueue;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by ${good-yy} on 2018/9/2.
 */

//调用allo获取Bytebuf 调用release释放bytebuf,
//不使用线程变量,和GlobalByteBufPool相关联合使用
public class ConcurrentGroupByteBufPool {

    //配合下面使用,为true才计数
    private static final boolean shouldCount = Boolean.getBoolean("global.should.count");
    //如果我们的内存池不够用了,就count++这个变量,用来计数,确定我们的内存池的大小合不合适
    protected static final AtomicLong needCount = new AtomicLong(0);

    private static void incrementCount() {
        needCount.incrementAndGet();
    }

    private final int myNumber;
    final MpmcArrayQueue<OwnWrappedByteBuf> byteBufs;
    //申请的内存的大小目前就是固定的,不做其他修改,同时,
    //我们注意的是,我们使用了两个ByteBuf内存的控制,一个是没有内存泄露检查
    public ByteBuf applyDirect() {
        OwnWrappedByteBuf poll = byteBufs.poll();
        if (poll != null) {
            //设定owner
            poll.setOwnPool(this);
            poll.reInit();
            return poll;
        } else {
            int number = this.myNumber;
            while (true) {
                number++;
                int i = number & (GlobalByteBufPool.GROUP_SIZE-1);
                if (i == myNumber) {
                    if (shouldCount) {
                        incrementCount();
                    }
                    //那就只能使用Unpool的内存池了,我们设定的目标是Buffer池数量是完全够用的
                    //一旦不够用的话,(有可能内存泄露了)就使用内存泄露检测模式的ByteBuf,用来确保是否发生了内存泄露
                    return GlobalByteBufPool.UnpooledDirectLeakByteBuf.directBuffer(GlobalByteBufPool.perBufferSize, GlobalByteBufPool.perBufferSize);
                }
                OwnWrappedByteBuf other = GlobalByteBufPool.GROUP[i].byteBufs.poll();
                if (other == null) {
                    continue;
                }
                //从别的组抢过来的,就放到自己这里了
                other.setOwnPool(this);
                other.reInit();
                return other;
            }
        }
    }

    void returnBuf(OwnWrappedByteBuf buf) {
        boolean success = byteBufs.offer(buf);
        if (!success) {
            int number = this.myNumber;
            while (true) {
                number++;
                int i = number & (GlobalByteBufPool.GROUP_SIZE-1);
                boolean isSuccess = GlobalByteBufPool.GROUP[i].byteBufs.offer(buf);
                if (isSuccess) {
                    return;
                } else {
                    continue;
                }
            }
        }
    }

     ConcurrentGroupByteBufPool( int myNumber,int queueSize) {
        this.byteBufs = new MpmcArrayQueue<OwnWrappedByteBuf>(queueSize);
        this.myNumber = myNumber;
    }

}
