package ByteBufPoolTest;

import io.netty.buffer.ByteBuf;
import yy.code.io.cosokcet.bytebuf.pool.GlobalByteBufPool;

/**
 * Created by ${good-yy} on 2018/11/12.
 */
public class TestPool {
    public static void main(String[] args) {
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> {
                test();
            });
            thread.setDaemon(false);
            thread.start();
        }
    }


    public static void test()  {
        while (true) {
            ByteBuf buf = GlobalByteBufPool.getThreadRandomGroup().applyDirect();
            buf.release();
        }
    }
}
