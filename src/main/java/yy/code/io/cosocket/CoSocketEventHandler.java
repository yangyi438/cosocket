package yy.code.io.cosocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;
import yy.code.io.cosocket.status.SelectionKeyUtils;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public class CoSocketEventHandler extends AbstractNioChannelEventHandler {

    private static InternalLogger logger = InternalLoggerFactory.getInstance(CoSocketEventHandler.class);

    private final CoSocket coSocket;

    Runnable readTimeoutHandler = new Runnable() {
        @Override
        public void run() {
            SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_READ);
            coSocket.handlerReadTimeOut();
        }
    };
    Runnable writeTimeOutHandler = new Runnable() {
        @Override
        public void run() {
            AtomicInteger status = coSocket.status;
            int now = status.get();
            if (!BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_WRITE)) {
                //等待写超时事件发生的时候,中途其他原因,调用了CoSocketChannel的close()方法
                //那么所有等待事件会抹除,直接唤醒coSocket的线程(如果需要的话),这时候其他任务有可能冲突
                //加一下判断,防止冲突了
                return;
            }
            CoSocketStatus.changeParkWriteToRunning(status);
            SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_WRITE);
            coSocket.ssSupport.beContinue();
        }
    };

    ReadEventHandler readEventHandler = new ReadEventHandler() {
        @Override
        public void readEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            if (readTimeoutFuture != null) {
                //取消读超时的task
                readTimeoutFuture.cancel(false);
            }
            boolean closeRead = coSocket.handlerReadActive();
            if (closeRead) {
                SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_READ);
            }
        }
    };
    WriteEventHandler writeEventHandler = new WriteEventHandler() {
        @Override
        public void writeEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            boolean closeWrite = coSocket.handlerWriteActive();
            if (closeWrite) {
                //关闭写监听
                SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_WRITE);
            }
        }
    };
    ConnectEventHandler connectEventHandler = new ConnectEventHandler() {
        @Override
        public void connectEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            try {
                if (!channel.finishConnect()) {
                    throw new IOException("finishConnect happen error");
                }
                //连接成功
                coSocket.successConnect();
                SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_CONNECT);
            } catch (IOException e) {
                try {
                    eventLoop.cancel(selectionKey);
                    channel.close();
                    //CoSocketChannel的相关的部分的资源已经释放完毕了
                    coSocket.isHandlerRelease = true;
                } catch (IOException error) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("connection channel error then close channel error{}", error);
                    }
                }
                coSocket.errorConnect(e);
            } finally {
                if (connectTimeoutFuture != null) {
                    //取消连接超时检测,或者其他的检测
                    connectTimeoutFuture.cancel(false);
                    connectTimeoutFuture = null;
                }
            }
        }
    };

    ScheduledFuture<?> connectTimeoutFuture;
    ScheduledFuture<?> readTimeoutFuture;
    ScheduledFuture<?> writeTimeoutFuture;
    private boolean isClose;

    public CoSocketEventHandler(CoSocket coSocket, SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        super(selectionKey, socketChannel, eventLoop);
        this.coSocket = coSocket;
        assert socketChannel != null;
        assert eventLoop != null;
        assert coSocket != null;
    }

    @Override
    protected WriteEventHandler getWriteHandler() {
        return writeEventHandler;
    }

    @Override
    protected ConnectEventHandler getConnectHandler() {
        return connectEventHandler;
    }

    @Override
    protected ReadEventHandler getReadHandler() {
        return readEventHandler;
    }

    @Override
    protected CloseEventHandler getCloseHandler() {
        if (logger.isErrorEnabled()) {
            logger.error("error the eventLoop call the close");
        }
        return new CloseEventHandler() {
            @Override
            public void closeEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
                close();
            }
        };
    }

    public void  timeoutForReadActive(Runnable runnable, int maxWaitTime) {
        if (eventLoop.inEventLoop()) {
            blockingForReadActive0(runnable, maxWaitTime);
        } else {
            eventLoop.execute(() -> blockingForReadActive0(runnable, maxWaitTime));
        }
    }

    public void connect(SocketAddress endpoint) {
        if (eventLoop.inEventLoop()) {
            connect0(endpoint);
        } else {
            eventLoop.execute(() -> {
                connect0(endpoint);
            });
        }
    }

    public void connect0(SocketAddress endpoint) {
        try {
            socketChannel.connect(endpoint);
            if (!checkAndListenKey(SelectionKey.OP_CONNECT, CoSocketStatus.PARK_FOR_CONNECT, CoSocketStatus.CONNECT_EXCEPTION)) {
                //register SelectKey的时候发生了错误
                return;
            }
            int connectionMilliseconds = coSocket.config.getConnectionMilliSeconds();
            this.connectTimeoutFuture = eventLoop.schedule(new Runnable() {
                @Override
                public void run() {
                    //去除连接监听,同时抛出连接超时异常
                    SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_CONNECT);
                    coSocket.timeOutConnect(new SocketTimeoutException("connect timeOut"));
                }
            }, connectionMilliseconds, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            if (logger.isTraceEnabled()) {
                logger.trace("connect happens error.", e);
            }
        }
    }

    public void blockingForReadActive0(Runnable timeRunnable, int maxWaitTime) {
        if (!checkAndListenKey(SelectionKey.OP_READ, CoSocketStatus.PARK_FOR_READ, CoSocketStatus.READ_EXCEPTION)) {
            return;
        }
        this.readTimeoutFuture = eventLoop.schedule(timeRunnable, maxWaitTime, TimeUnit.MILLISECONDS);
    }

    private boolean checkAndListenKey(int listenOps, int alsoStatus, int registerErrorOps) {
        if (selectionKey == null) {
            //第一次还没有注册的情况
            try {
                selectionKey = socketChannel.register(eventLoop.unwrappedSelector(), listenOps, this);
            } catch (Exception e) {
                if (logger.isTraceEnabled()) {
                    logger.trace("register for read happen error .", e);
                }
                if (!(e instanceof IOException)) {
                    if (logger.isErrorEnabled()) {
                        logger.error("not a IOException.", e);
                    }
                    e = new IOException(e);
                }
                //调用连接错误
                AtomicInteger status = coSocket.status;
                while (true) {
                    int now = status.get();
                    if (!BitIntStatusUtils.isInStatus(now, alsoStatus)) {
                        return false;
                    }
                    int update;
                    update = BitIntStatusUtils.convertStatus(now, alsoStatus, CoSocketStatus.RUNNING);
                    update = BitIntStatusUtils.addStatus(update, registerErrorOps);
                    if (status.compareAndSet(now, update)) {
                        coSocket.exception = (IOException) e;
                        //wakeUp
                        coSocket.ssSupport.beContinue();
                        return false;
                    }
                }
            }
        } else {
            SelectionKeyUtils.listenOps(selectionKey, listenOps);
        }
        return true;
    }

    CoSocketEventLoop eventLoop() {
        return eventLoop;
    }


    private void shutdownInput0() {
        if (isClose) {
            return;
        }
        try {
            SocketChannel channel = this.getSocketChannel();
            if (PlatformDependent.javaVersion() >= 7) {
                channel.shutdownInput();
            } else {
                channel.socket().shutdownInput();
            }
            if (selectionKey != null) {
                SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            //忽略关闭的时候发生的异常
            if (logger.isTraceEnabled()) {
                logger.trace("close inputStream happen exception .", e);
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


    public static boolean isShutdown(SocketChannel channel) {
        Socket socket = channel.socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive(channel);
    }

    public static boolean isActive(SocketChannel channel) {
        return channel.isOpen() && channel.isConnected();
    }

    public boolean isInputShutdown() {
        SocketChannel channel = getSocketChannel();
        return channel.socket().isInputShutdown() || !isActive(channel);
    }

    public boolean isOutputShutdown() {
        SocketChannel channel = getSocketChannel();
        return channel.socket().isOutputShutdown() || !isActive(channel);
    }

    private void shutdownOutput0() {
        if (isClose) {
            //已经close就跳过了
            return;
        }
        SocketChannel channel = getSocketChannel();
        try {
            if (PlatformDependent.javaVersion() >= 7) {
                channel.shutdownOutput();
            } else {
                channel.socket().shutdownOutput();
            }
            if (selectionKey != null) {
                SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            //忽略关闭的时候发生的异常
            if (logger.isTraceEnabled()) {
                logger.trace("close outPutStream happen exception .", e);
            }
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
        SocketChannel channel = getSocketChannel();
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
        //添加的task还在线程待执行的队列中,但是我们由status状态机. 控制多余的task来执行
        AtomicInteger status = coSocket.status;
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
                coSocket.exception = new SocketException("channel close now");
            }
            update = BitIntStatusUtils.addStatus(update, CoSocketStatus.CLOSE);
            if (status.compareAndSet(now, update)) {
                if (flag) {
                    //唤醒
                    coSocket.ssSupport.beContinue();
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

    public void flushWriteBuffer() {
        if (!checkAndListenKey(SelectionKey.OP_WRITE, CoSocketStatus.PARK_FOR_WRITE, CoSocketStatus.WRITE_EXCEPTION)) {
            //监听或者发生错误
            return;
        }
        CoSocketConfig config = this.coSocket.config;
        long initialBlockMilliSeconds = config.getInitialBlockMilliSeconds();
        long milliSecondPer1024B = config.getMilliSecondPer1024B();
        ByteBuf writeBuffer = coSocket.writeBuffer;
        int readableBytes = writeBuffer.readableBytes();
        //多加一毫秒了,容错一下,
        int perCount = (readableBytes >> 10) + 1;
        long totalDelayTime = initialBlockMilliSeconds + perCount * milliSecondPer1024B;
        writeTimeoutFuture = eventLoop().schedule(this.writeTimeOutHandler, totalDelayTime, TimeUnit.MILLISECONDS);
    }
}
