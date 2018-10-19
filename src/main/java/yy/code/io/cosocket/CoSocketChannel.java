package yy.code.io.cosocket;

import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import io.netty.channel.nio.CoSocketEventLoop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;

/**
 * Created by ${good-yy} on 2018/10/13.
 */

/**
 * 写发生异常我们就直接关闭channel连接,保守性的解决方案
 */
public final class CoSocketChannel {

    private static InternalLogger logger = InternalLoggerFactory.getInstance(CoSocketChannel.class);

    public CoSocketConfig getConfig() {
        return config;
    }

    private final CoSocketConfig config;

    private final CoSocketEventLoop eventLoop;

    boolean isEof = false;

    //标志有没有调用了close的方法
    private boolean isClose = false;


    public CoSocketChannel(SocketChannel channel, CoSocketConfig config, CoSocket coSocket, CoSocketEventLoop eventLoop) {
        this.innerCoSocket = coSocket;
        this.channel = channel;
        this.config = config;
        this.eventLoop = eventLoop;
    }

    public CoSocketEventLoop eventLoop() {
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
    public SelectionKey selectionKey;

    public SocketChannel getSocketChannel() {
        return channel;
    }

    public CoSocket getInnerCoSocket() {
        return innerCoSocket;
    }

    //real channel
    private final SocketChannel channel;

    public static boolean isActive(SocketChannel channel) {
        return channel.isOpen() && channel.isConnected();
    }

    public void finishConnect() {
        try {
            if (!channel.finishConnect()) {
                throw new IOException("finishConnect happen error");
            }
            //连接成功
            innerCoSocket.successConnect();
            int ops = selectionKey.interestOps();
            //关闭连接侦听位
            selectionKey.interestOps(ops & ~SelectionKey.OP_CONNECT);
        } catch (IOException e) {
            try {
                //fixme 关闭selectKey一定要使用这个方法
                eventLoop.cancel(selectionKey);
                channel.close();
            } catch (IOException error) {
                if (logger.isTraceEnabled()) {
                    logger.trace("connection channel error then close channel error{}", error);
                }
            }
            innerCoSocket.errorConnect(e);
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

    //关闭当前的socket连接,不能抛出异常
    public void close() {
        if (eventLoop().inEventLoop()) {
            closeAndRelease();
        } else {
            eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    closeAndRelease();
                }
            });
        }
    }

    //关闭而且释放资源
    private void closeAndRelease() {
        //todo 添加其他释放资源的操作
        if (isClose) {
            //防止重复进行close的方法了
            return;
        }
        Executor executor = null;
        try {
            //对于SoLinger有特殊的处理的方法
            if (channel.isOpen() && channel.socket().getSoLinger() > 0) {
                if (selectionKey != null) {
                    eventLoop.cancel(selectionKey);
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
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    safeClose(channel);
                }
            });
        }
        this.isClose = true;
        //todo 添加可能的需要唤醒功能,在rebuildSelector的时候
        //fixme 确保万一
    }


    private static void safeClose(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            if (logger.isInfoEnabled()) {
                logger.info("close the channel happen error{}", e);
            }
        }
    }

    //fixme netty里面关闭OutPut是需要 cancel selectKey的 我们要考虑这个怎么实现,会不会触发select 不堵塞
    public static void shutdownOutput(SocketChannel channel) throws IOException {
        if (PlatformDependent.javaVersion() >= 7) {
            channel.shutdownOutput();
        } else {
            channel.socket().shutdownOutput();
        }
    }

    //关闭输入,由于netty的处理,读到eof或者读的时候发生异常,我们就要
    //关闭整个连接,或者关闭整个input
    //在我们的实现中,出现发生异常就要关闭整个连接
    //eof就话就报告eof,
    //fixme
    public static void shutdownInput(SocketChannel channel) throws IOException {
        if (PlatformDependent.javaVersion() >= 7) {
            channel.shutdownInput();
        } else {
            channel.socket().shutdownInput();
        }
    }

    public static boolean isShutdown(SocketChannel channel) {
        Socket socket = channel.socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive(channel);
    }


    public boolean isInputShutdown() {
        return channel.socket().isInputShutdown() || !isActive(channel);
    }

    public boolean isOutputShutdown() {
        return channel.socket().isOutputShutdown() || !isActive(channel);
    }

    //仅仅由CoSocket来调用,用来去连接对面,注意的是,我们这里的连接超时不是精确的
    //会生成一个队列到事件循环队列里面.等候执行,超时的取消task也是在netty的调度下,延迟执行的
    public void connect(InetSocketAddress remote) {
        eventLoop().register(this, SelectionKey.OP_CONNECT, RegisterHandler.CONNECTION_HANDLER);
    }

}
