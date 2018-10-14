package yy.code.io.cosocket.eventloop;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.client.CoSocket;
import yy.code.io.cosocket.config.CoSocketConfig;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledFuture;

/**
 * Created by ${good-yy} on 2018/10/13.
 */
public final class CoSocketChannel {


    private static InternalLogger logger = InternalLoggerFactory.getInstance(CoSocketChannel.class);

    CoSocketConfig config;

    CoSocketEventLoop eventLoop;

    boolean isEof = false;

    CoSocketEventLoop eventLoop() {
        return eventLoop;
    }

    CoSocket innerCoSocket;
    Runnable readRunable;
    Runnable writeRunable;
    Runnable connectRunable;
    //连接超时的task todo
    ScheduledFuture<?> connectTimeoutFuture;
    ScheduledFuture<?> readTimeoutFuture;
    ScheduledFuture<?> writeTimeoutFuture;
    SelectionKey selectionKey;

    //真实的channel nio的channel
    SocketChannel channel;

    boolean isActive() {
        SocketChannel ch = channel;
        return ch.isOpen() && ch.isConnected();
    }

    public void finishConnect() {
        try {
            if (!channel.finishConnect()) {
                throw new Error("could not happen ");
            }
            //连接成功
            innerCoSocket.successConnect();
            int ops = selectionKey.interestOps();
            //关闭连接侦听位
            selectionKey.interestOps(ops & ~SelectionKey.OP_CONNECT);
        } catch (Throwable e) {
            try {
                //fixme 关闭selectKey一定要使用这个方法
                eventLoop.cancel(selectionKey);
                channel.close();
            } catch (Throwable error) {
                if (logger.isTraceEnabled()) {
                    logger.trace("connection channel error then close channel error{}", error);
                }
            }
            if (e instanceof IOException) {
                //连接异常了
                innerCoSocket.errorConnect((IOException) e);
            } else {
                if (logger.isWarnEnabled()) {
                    logger.warn("error finishConnect not IOException {} ", e);
                }
                //语义上我们要确定抛出的异常是io异常
                IOException ioException = new IOException(e);
                innerCoSocket.errorConnect(ioException);
            }
        } finally {
            if (connectTimeoutFuture != null) {
                //取消连接超时检测,或者其他的检测
                //todo 添加以后其他的task 取消
                connectTimeoutFuture.cancel(false);
            }
        }
    }

    /**
     * 读事件触发了,唤醒我们的io线程,或者等待一会在通知我们的io线程,
     */

    public void readActive() {
        boolean closeRead = innerCoSocket.handlerReadActive();
        if (closeRead) {
            //关闭读监听
            closeReadListen();
        }
    }

    /**
     * 写事件触发了,触发事件到socket里面,可能我们可能有buffer,等待我们去写
     */

    public void writeActive() {
        boolean closeWrite = innerCoSocket.handlerWriteActive();
        if (closeWrite) {
            //关闭写监听
            closeWriteListen();
        }
    }

    void startWriteListen() {
        SelectionKey selectionKey = this.selectionKey;
        int interestOps = selectionKey.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            selectionKey.interestOps(interestOps & SelectionKey.OP_READ);
        }
    }

    void closeWriteListen() {
        SelectionKey selectionKey = this.selectionKey;
        int interestOps = selectionKey.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            selectionKey.interestOps(interestOps & ~SelectionKey.OP_READ);
        }
    }

    void closeReadListen() {
        SelectionKey selectionKey = this.selectionKey;
        int interestOps = selectionKey.interestOps();
        //关闭自动读的 位
        if ((interestOps & SelectionKey.OP_READ) != 0) {
            selectionKey.interestOps(interestOps & ~SelectionKey.OP_READ);
        }
    }

    void startReadListen() {
        SelectionKey selectionKey = this.selectionKey;
        int interestOps = selectionKey.interestOps();
        //打开自动读
        if ((interestOps & SelectionKey.OP_READ) == 0) {
            selectionKey.interestOps(interestOps & SelectionKey.OP_READ);
        }
    }

    //关闭当前的socket连接
    public void close() {

    }
}
