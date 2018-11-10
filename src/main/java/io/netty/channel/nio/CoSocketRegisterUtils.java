package io.netty.channel.nio;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.AbstractNioChannelEventHandler;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Created by ${good-yy} on 2018/11/10.
 */
public final class CoSocketRegisterUtils {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(CoSocketRegisterUtils.class);

    public static SelectionKey register(CoSocketEventLoop eventLoop, SocketChannel channel,int ops,AbstractNioChannelEventHandler attachment) throws Exception {
        checkRunInEvent(eventLoop);
        if (eventLoop.isShutdown() || eventLoop.isShuttingDown()) {
            String message = "eventLoop is shutdown or shutting Down";
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(message);
            }
            throw new Exception(message);
        }
        SelectionKey key = channel.register(eventLoop.unwrappedSelector(), ops, attachment);
        eventLoop.incrementEventCounter();
        return key;
    }

    private static  void checkRunInEvent(CoSocketEventLoop eventLoop) {
        if (!eventLoop.inEventLoop()) {
            throw new IllegalStateException("must call in eventLoop Thread");
        }
    }

    public static void deRegister(CoSocketEventLoop eventLoop, SelectionKey selectionKey) {
        checkRunInEvent(eventLoop);
        Object attach = selectionKey.attachment();
        if (attach instanceof AbstractNioChannelEventHandler) {
            String errInfo = "select key,attachment not instanceof AbstractNioChannelEventHandler";
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(errInfo);
            }
            throw new IllegalStateException(errInfo);
        }
        eventLoop.cancel(selectionKey);
        eventLoop.decrementEventCounter();
    }

}
