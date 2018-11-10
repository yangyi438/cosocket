package yy.code.io.cosocket;

import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.channel.nio.CoSocketRegisterUtils;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public abstract class AbstractNioChannelEventHandler implements EventHandler {

    private static InternalLogger logger = InternalLoggerFactory.getInstance(AbstractNioChannelEventHandler.class);

    protected SelectionKey selectionKey;
    protected SocketChannel socketChannel;
    protected CoSocketEventLoop eventLoop;

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    public void setSocketChannel(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    public void setEventLoop(CoSocketEventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public CoSocketEventLoop getEventLoop() {
        return eventLoop;
    }

    public AbstractNioChannelEventHandler(SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        this.selectionKey = selectionKey;
        this.socketChannel = socketChannel;
        this.eventLoop = eventLoop;
    }

    protected abstract WriteEventHandler getWriteHandler();

    protected abstract ConnectEventHandler getConnectHandler();

    protected abstract ReadEventHandler getReadHandler();

    protected abstract CloseEventHandler getCloseHandler();

    @Override
    public void writeActive() {
        WriteEventHandler writeEventHandler = getWriteHandler();
        writeEventHandler.writeEventHandler(selectionKey, socketChannel, eventLoop);
    }


    @Override
    public void connectActive() {
        ConnectEventHandler connectEventHandler = getConnectHandler();
        connectEventHandler.connectEventHandler(selectionKey, socketChannel, eventLoop);
    }


    @Override
    public void readActive() {
        ReadEventHandler readEventHandler = getReadHandler();
        readEventHandler.readEventHandler(selectionKey, socketChannel, eventLoop);
    }


    @Override
    public void closeActive() {
        CloseEventHandler closeEventHandler = getCloseHandler();
        closeEventHandler.closeEventHandler(selectionKey, socketChannel, eventLoop);
    }

    protected static void SafeCloseEventLoopNioChannel(SelectionKey selectionKey, CoSocketEventLoop eventLoop, SocketChannel channel) {
        if (eventLoop.inEventLoop()) {
            SafeCloseEventLoopNioChannel0(selectionKey, eventLoop, channel);
        } else {
            eventLoop.execute(() -> {
                SafeCloseEventLoopNioChannel0(selectionKey, eventLoop, channel);
            });
        }
    }

    private static void SafeCloseEventLoopNioChannel0(SelectionKey selectionKey, CoSocketEventLoop eventLoop, SocketChannel channel) {
        Executor executor = null;
        try {
            //对于SoLinger有特殊的处理的方法
            if (channel.isOpen() && channel.socket().getSoLinger() > 0) {
                if (selectionKey != null) {
                    CoSocketRegisterUtils.deRegister(eventLoop,selectionKey);
                }
            }
            executor = GlobalEventExecutor.INSTANCE;
        } catch (Throwable ignore) {

        }
        if (executor == null) {
            safeClose(channel);
        } else {
            //另外一个线程来执行这个方法,netty是这样做的,不是很明白,可能是close方法可能会线程阻塞
            //先按照netty的来
            executor.execute(() -> safeClose(channel));
        }
    }

    private static void safeClose(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            if (logger.isTraceEnabled()) {
                logger.trace("close the channel happen error.", e);
            }
        }
    }

}
