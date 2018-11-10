package yy.code.io.cosocket;


import yy.code.io.cosocket.eventloop.CoSocketEventLoopGroup;

/**
 * Created by ${good-yy} on 2018/10/15.
 */
public final class CoSocketFactory {

    private static final int globalLoopSize = Integer.getInteger("yy.io.cosocket.eventloop.size", 0);

    static final CoSocketEventLoopGroup globalEventLoop;

    static{
        if (globalLoopSize == 0) {
            int i = Runtime.getRuntime().availableProcessors();
            i = (i + 1) / 2;
            globalEventLoop = new CoSocketEventLoopGroup(i);
        } else {
            globalEventLoop = new CoSocketEventLoopGroup(globalLoopSize);
        }
    }

    private CoSocketFactory() {

    }


}
