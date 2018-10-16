package yy.code.io.cosokcet.bytebuf.pool;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.Log4J2LoggerFactory;
import io.netty.util.internal.shaded.org.jctools.util.Pow2;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by ${good-yy} on 2018/9/2.
 */
//nett的池化ByteBuf和线程变量相相关联,自定义一个全局的和线程变量无关的池化ByteBuf
//不使用ThreadLocal 一定不能使用threadLocal.使用者一定要注意release我们的buf
public class GlobalByteBufPool {


    //从netty的Io线程里面读取的数据是directBuf的,我们传递给io线程也是原来的直接内存的话,这样就非常容易造成内存泄露
    //没有回收掉相关的bytebuf 而且是在netty的io线程里面回收的,
    //所以简单实现自己的堆内存池,然后将直接内存copy成堆内存传递给后端业务线程去工作

    public static final InternalLogger logger = Log4J2LoggerFactory.getInstance(GlobalByteBufPool.class);
    static final int perBufferSize = Integer.getInteger("GlobalByteBufPool.perBufferSize.size", 4096);  //4k

    private static final int poolArrayLength = Integer.getInteger("GlobalByteBufPool.poolArrayLength.size", 16384);

    //通过当前线程hash获取其中一个的group
    public static ConcurrentGroupByteBufPool getThreadHashGroup() {
        int i = Thread.currentThread().hashCode();
        int index = i & (GROUP_SIZE - 1);
        return GROUP[index];
    }

    private static final UnpooledByteBufAllocator allocator;

    static final int GROUP_SIZE;

    static UnpooledByteBufAllocator UnpooledDirectLeakByteBuf = new UnpooledByteBufAllocator(true, false, false);

    static final ConcurrentGroupByteBufPool[] GROUP;


    private static final AtomicInteger count = new AtomicInteger(0);

    //简单的自己内置部分内存池,可恶的netty内存池和线程变量关联了,真的很蛋疼
    //同时我们采用了netty的内存泄露检测机制
    static {
        Integer integer = Integer.getInteger("GlobalByteBufPool.group.length");
        if (integer == null) {
            integer = Runtime.getRuntime().availableProcessors();
        }
        if (integer < 2) {
            integer = 2;
        }
        GROUP_SIZE = Pow2.roundToPowerOfTwo(integer);
        //也开启泄露检测把,多检测点也好
        allocator = new UnpooledByteBufAllocator(true, false, false);
        int groupPerSize = poolArrayLength / GROUP_SIZE;
        GROUP = new ConcurrentGroupByteBufPool[(int) GROUP_SIZE];
        for (int i = 0; i < GROUP_SIZE; i++) {
            GROUP[i] = new ConcurrentGroupByteBufPool(i, groupPerSize + 1);
        }

        for (ConcurrentGroupByteBufPool pool : GROUP) {
            for (int i = 0; i < groupPerSize; i++) {
                ByteBuf buf = allocator.directBuffer(perBufferSize, perBufferSize);
                pool.returnBuf(new OwnWrappedByteBuf(buf));
            }
        }
        int i = groupPerSize * GROUP_SIZE;
        int last = poolArrayLength - i;
        if (last > 0) {
            int count = 0;
            while (true) {
                ByteBuf buf = allocator.directBuffer(perBufferSize, perBufferSize);
                GROUP[count].byteBufs.offer(new OwnWrappedByteBuf(buf));
                count++;
                count = count & (GROUP_SIZE - 1);
                last--;
                if (last == 0) {
                    break;
                }
            }
        }
    }

    public static ConcurrentGroupByteBufPool getOneGroupPool() {
        int andIncrement = count.getAndIncrement();
        long group = andIncrement % GROUP_SIZE;
        return GROUP[(int) group];
    }

}




