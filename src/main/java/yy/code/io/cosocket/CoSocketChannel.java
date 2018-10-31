package yy.code.io.cosocket;

import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import io.netty.channel.nio.CoSocketEventLoop;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
                //CoSocketChannel的相关的部分的资源已经释放完毕了
                innerCoSocket.isCoChannelRelease = true;
            } catch (IOException error) {
                if (logger.isTraceEnabled()) {
                    logger.trace("connection channel error then close channel error{}", error);
                }
            }
            innerCoSocket.errorConnect(e);
        } finally {
            if (connectTimeoutFuture != null) {
                //取消连接超时检测,或者其他的检测
                connectTimeoutFuture.cancel(false);
                connectTimeoutFuture = null;
            }
        }
    }

    /**
     * 读事件触发了,唤醒我们的io线程,或者等待一会在通知我们的io线程,
     * 取决于我们的socket的handlerReaActive的实现
     */
    public void readActive() {
        if (readTimeoutFuture != null) {
            //取消读超时的task
            readTimeoutFuture.cancel(false);
        }
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
        if (eventLoop().inEventLoop()) {
            startWriteListen0();
        } else {
            eventLoop().execute(this::startReadListen0);
        }
    }

    void startWriteListen0() {
        SelectionKey selectionKey = this.selectionKey;
        if (selectionKey == null) {
            try {
                this.selectionKey = this.getSocketChannel().register(eventLoop().unwrappedSelector(),SelectionKey.OP_READ);
            } catch (ClosedChannelException e) {
                e.printStackTrace();
            }
            return;
        }
            int interestOps = selectionKey.interestOps();
            if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                selectionKey.interestOps(interestOps & SelectionKey.OP_WRITE);
            }

    }

    void closeWriteListen() {
        if (eventLoop().inEventLoop()) {
            closeWriteListen0();
        } else {
            eventLoop().execute(this::closeWriteListen0);
        }
    }

    void closeWriteListen0() {
        SelectionKey selectionKey = this.selectionKey;
        int interestOps = selectionKey.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            selectionKey.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }

    void closeReadListen() {
        if (eventLoop().inEventLoop()) {
            closeReadListen0();
        } else {
            eventLoop().execute(this::closeReadListen0);
        }
    }

    private void closeReadListen0() {
        SelectionKey selectionKey = this.selectionKey;
        if (selectionKey != null) {
            int interestOps = selectionKey.interestOps();
            //关闭自动读的 位
            if ((interestOps & SelectionKey.OP_READ) != 0) {
                selectionKey.interestOps(interestOps & ~SelectionKey.OP_READ);
            }
        }
    }

    void startReadListen() {
        if (eventLoop().inEventLoop()) {
            startReadListen0();
        } else {
            eventLoop().execute(this::startReadListen0);
        }
    }

    private void startReadListen0() {
        SelectionKey selectionKey = this.selectionKey;
        //有可能还没有注册我们的channel到selector上面,所以selectionKey为null
        if (selectionKey == null) {
          //  this.selectionKey = this.getSocketChannel().register(eventLoop().register();)
            //todo
        }
        int interestOps = selectionKey.interestOps();
        //打开自动读
        if ((interestOps & SelectionKey.OP_READ) == 0) {
            selectionKey.interestOps(interestOps & SelectionKey.OP_READ);
        }
    }

    //关闭当前的socket连接,不能抛出异常 加上同步,已避免潜在的并发close竞争
    public void close() {
        //close的时候如果我们的业务线程正在等待读或者写的话 我们需要唤醒使用CoSocket的线程
        if (eventLoop().inEventLoop()) {
            closeAndRelease();
        } else {
            eventLoop().execute(this::closeAndRelease);
        }
    }

    //关闭而且释放资源
    private void closeAndRelease() {
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
            executor.execute(() -> safeClose(channel));
        }
        this.isClose = true;
        if (connectTimeoutFuture != null) {
            connectTimeoutFuture.cancel(false);
        }
        if (readTimeoutFuture != null) {
            readTimeoutFuture.cancel(false);
        }
        if (writeTimeoutFuture != null) {
            writeTimeoutFuture.cancel(false);
        }
        //我们取消所有相关的task了,也未必以后不会有task进来,因为添加 ***TimeOutFuture 是异步的
        //添加的task还在线程待执行的队列中
        AtomicInteger status = innerCoSocket.status;
        while (true) {
            int now = status.get();
            int update = now;
            boolean flag = false;
            if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_WRITE)) {
                flag = true;
                update = BitIntStatusUtils.convertStatus(update, CoSocketStatus.PARK_FOR_WRITE, CoSocketStatus.RUNNING);
                update = BitIntStatusUtils.addStatus(update, CoSocketStatus.WRITE_EXCEPTION);
            } else if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_READ)) {
                flag = true;
                update = BitIntStatusUtils.convertStatus(update, CoSocketStatus.PARK_FOR_READ, CoSocketStatus.RUNNING);
                update = BitIntStatusUtils.addStatus(update, CoSocketStatus.READ_EXCEPTION);
            } else if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_CONNECT)) {
                flag = true;
                update = BitIntStatusUtils.convertStatus(update, CoSocketStatus.PARK_FOR_CONNECT, CoSocketStatus.RUNNING);
                update = BitIntStatusUtils.addStatus(update, CoSocketStatus.CONNECT_EXCEPTION);
            }
            if (flag) {
                innerCoSocket.exception = new SocketException("channel close now");
            }
            update = BitIntStatusUtils.addStatus(update, CoSocketStatus.CLOSE);
            if (status.compareAndSet(now, update)) {
                if (flag) {
                    //唤醒
                    innerCoSocket.ssSupport.beContinue();
                }
                break;
            } else {
                continue;
            }
        }
        isClose = true;
    }


    private static void safeClose(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            if (logger.isInfoEnabled()) {
                logger.info("close the channel happen error.", e);
            }
        }
    }


    private void shutdownOutput0() {
        if (isClose) {
            //已经close就跳过了
            return;
        }
        try {
            closeWriteListen();
            if (PlatformDependent.javaVersion() >= 7) {
                channel.shutdownOutput();
            } else {
                channel.socket().shutdownOutput();
            }
        } catch (IOException e) {
            //忽略关闭的时候发生的异常
            if (logger.isTraceEnabled()) {
                logger.trace("close outPutStream happen exception .", e);
            }
        }

    }

    void shutdownOutput() {
        if (eventLoop().inEventLoop()) {
            shutdownOutput0();
        } else {
            eventLoop().execute(this::shutdownOutput0);
        }
    }


    void shutdownInput() {
        if (eventLoop().inEventLoop()) {
            shutdownInput0();
        } else {
            eventLoop().execute(this::shutdownInput0);
        }
    }

    private void shutdownInput0() {
        if (isClose) {
            return;
        }
        try {
            closeReadListen();
            SocketChannel channel = this.channel;
            if (PlatformDependent.javaVersion() >= 7) {
                channel.shutdownInput();
            } else {
                channel.socket().shutdownInput();
            }
        } catch (IOException e) {
            //忽略关闭的时候发生的异常
            if (logger.isTraceEnabled()) {
                logger.trace("close inputStream happen exception .", e);
            }
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
    public void connect() {
        eventLoop().register(this, SelectionKey.OP_CONNECT, RegisterHandler.CONNECTION_HANDLER);
    }

    //由CoSocket来调用,block一定的时间,直到阻塞时间到了,或者等待可读时间发生了
    // 我们要处理发送放网络堵塞的原因,一直发慢包,那我们使用者线程就会频繁的因为读数据而挂起,这样会影响效率
    //比如说 读几百b 就挂起一下,我们唤醒的时候,可以释放延后一下,防止这种循环式的等待读,然后挂起
    //但是这样也会造成延迟的问题,我们要均衡这些情况,平衡我们读数据的速率,和发送方发送的速率.
    //但是也要兼顾这样造成的延迟问题
    public void waitForRead() {
        if (eventLoop.inEventLoop()) {
            waitForRead0();
        } else {
            eventLoop().execute(this::waitForRead0);
        }
    }

    //监听读事件的发生
    private void waitForRead0() {
        if (innerCoSocket.readTimeoutHandler == null) {
            //我们可能会设置自己的readTimeHandler,这里给一个默认的readTimeOutHandler
            innerCoSocket.readTimeoutHandler = () -> {
                //关闭读监听
                closeReadListen();
                innerCoSocket.handlerReadTimeOut();
            };
        }
        this.startReadListen();
        this.readTimeoutFuture = eventLoop().schedule(innerCoSocket.readTimeoutHandler, getConfig().getSoTimeout(), TimeUnit.MILLISECONDS);
    }

}
