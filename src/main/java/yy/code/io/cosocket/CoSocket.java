package yy.code.io.cosocket;


import io.netty.buffer.ByteBuf;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import io.netty.channel.nio.CoSocketEventLoop;
import yy.code.io.cosocket.fiber.StrandSuspendContinueSupport;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;
import yy.code.io.cosokcet.bytebuf.pool.GlobalByteBufPool;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public final class CoSocket implements Closeable {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(CoSocket.class);

    Runnable delayWakeUpHandler = null;
    Runnable readTimeoutHandler = null;
    //发生了io异常就会记录这个异常,条件允许,我们会自动底层的channel
    private IOException exception;
    private CoSocketEventLoop coSocketEventLoop;
    CoSocketChannel coChannel;
    private StrandSuspendContinueSupport ssSupport;
    private AtomicInteger status = new AtomicInteger(0);
    //出现异常或者正常关闭的时候的标志位,代表有没有释放过资源
    boolean isCoChannelRelease = false;
    boolean isEof = false;
    //读缓存,一次从channel里面读好多数据的,然后写到readBuffer里面
    private ByteBuf readBuffer;
    //写缓存,如果使用者线程,写不是directBuffer的数据到缓存里面去,那么我们就手动先写到我们内置直接内存的writeBuffer里面去
    //然后在往真实的channel里面去写数据
    private ByteBuf writeBuffer;
    BlockingReadWriteControl blockingRW = new BlockingReadWriteControl();
    public CoSocket() throws IOException {
        initDefault();
    }

    private void initDefault() throws IOException {
        SocketChannel channel = SocketChannel.open();
        initChannel(channel,new CoSocketConfig(), CoSocketFactory.globalEventLoop.nextCoSocketEventLoop());
    }

    //初始化channel,eventLoop,StrandSuspendContinueSupport
    private void initChannel(SocketChannel channel,CoSocketConfig config, CoSocketEventLoop eventLoop) throws IOException {
        assert eventLoop != null;
        assert channel != null;
        try {
            channel.configureBlocking(false);
            if (config == null) {
                config = new CoSocketConfig();
            }
            Socket socket = channel.socket();
            //接受缓存的大小和发送缓存的大小是必须的,我们需要这些参数,做一些特殊的处理
            config.setSendBufferSize(socket.getSendBufferSize());
            config.setReceiveBufferSize(socket.getReceiveBufferSize());
            coChannel = new CoSocketChannel(channel, config, this, eventLoop);
            //暂时就定quasar作为协程的实现,不做接口的形式了
            ssSupport = new StrandSuspendContinueSupport();
            this.coSocketEventLoop = eventLoop;
        } catch (IOException e) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("open channel happen ioException{}", e);
            }
            if (channel != null) {
                //关闭的时候又出现异常就忽略了,没办法在处理了,抛出来
                channel.close();
            }
            throw e;
        }
    }


    private boolean closed = false;
    private Object closeLock = new Object();


    public CoSocket(String host, int port)
            throws UnknownHostException, IOException {
        this(host != null ? new InetSocketAddress(host, port) :
                        new InetSocketAddress(InetAddress.getByName(null), port),
                (SocketAddress) null);
    }


    public CoSocket(InetAddress address, int port) throws IOException {
        this(address != null ? new InetSocketAddress(address, port) : null,
                (SocketAddress) null);
    }


    public CoSocket(String host, int port, InetAddress localAddr,
                    int localPort) throws IOException {
        this(host != null ? new InetSocketAddress(host, port) :
                        new InetSocketAddress(InetAddress.getByName(null), port),
                new InetSocketAddress(localAddr, localPort));
    }


    public CoSocket(InetAddress address, int port, InetAddress localAddr,
                    int localPort) throws IOException {
        this(address != null ? new InetSocketAddress(address, port) : null,
                new InetSocketAddress(localAddr, localPort));
    }


    private CoSocket(SocketAddress address, SocketAddress localAddr) throws IOException {
        if (address == null)
            throw new NullPointerException();
        initDefault();
        try {
            if (localAddr != null) {
                bind(localAddr);
            }
            connect(address);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            try {
                close();
            } catch (IOException ce) {
                e.addSuppressed(ce);
            }
            throw e;
        }
    }

    //这里我们允许连接超时,不能存在无限等待连接的情况
    //同时我们默认给3秒(很长了) 的连接超时时间,不允许无限的连接时间,就是不允许
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 3 * 1000);
    }

    //mark
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (endpoint == null)
            throw new IllegalArgumentException("connect: The address can't be null");

        if (timeout <= 0)
            throw new IllegalArgumentException("connect: timeout can't be <=0");

        if (isClosed())
            throw new SocketException("Socket is closed");

        if (isConnected())
            throw new SocketException("already connected");

        if (!(endpoint instanceof InetSocketAddress))
            throw new IllegalArgumentException("Unsupported address type");

        InetSocketAddress epoint = (InetSocketAddress) endpoint;
        InetAddress addr = epoint.getAddress();
        int port = epoint.getPort();
        checkAddress(addr, "connect");
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            if (epoint.isUnresolved())
                security.checkConnect(epoint.getHostName(), port);
            else
                security.checkConnect(addr.getHostAddress(), port);
        }
        AtomicInteger status = this.status;
        int forConnect = BitIntStatusUtils.addStatus(status.get(), CoSocketStatus.PARK_FOR_CONNECT);
        status.set(forConnect);
        coChannel.getConfig().setConnectionMilliSeconds(timeout);
        coChannel.connect(epoint);
        ssSupport.suspend();
        //被唤醒了
        int now = status.get();
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.CONNECT_SUCCESS)) {
            int running = BitIntStatusUtils.convertStatus(now, CoSocketStatus.PARK_FOR_CONNECT, CoSocketStatus.RUNNING);
            status.set(running);
            //连接成功了,直接返回
            return;
        }
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.CONNECT_EXCEPTION)) {
            assert exception != null;
            throw exception;
        } else {
            //连接的结果只有成功,或者失败,不应该有其他的情况的,发生了应该就是内部错误
            throw new IllegalStateException("could not happen may someError");
        }
    }

    void successConnect() {
        //todo
    }

    //连接的时候发生了异常,连接超时或者其他io异常,那我们就干掉当前的连接
    //注意的是,这个被io调度线程来调度使用的触发连接异常的
    void errorConnect(IOException e) {
        this.exception = e;
        while (true) {
            int status = this.status.get();
            int error = BitIntStatusUtils.addStatus(status, CoSocketStatus.CONNECT_EXCEPTION);
            //理论上来说,不应该发生失败的情况的,必须的一次成功的
            if (this.status.compareAndSet(status, error)) {
                break;
            }
        }
        ssSupport.beContinue();
        //之后coSocket线程应该就会被唤醒
    }

    //todo 绑定失败的情况的话,需要关闭本地channel
    public void bind(SocketAddress bindpoint) throws IOException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        if (isBound())
            throw new SocketException("Already bound");

        if (bindpoint != null && (!(bindpoint instanceof InetSocketAddress)))
            throw new IllegalArgumentException("Unsupported address type");
        InetSocketAddress epoint = (InetSocketAddress) bindpoint;
        if (epoint != null && epoint.isUnresolved())
            throw new SocketException("Unresolved address");
        if (epoint == null) {
            epoint = new InetSocketAddress(0);
        }
        InetAddress addr = epoint.getAddress();
        int port = epoint.getPort();
        checkAddress(addr, "connect");
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkListen(port);
        }
    }

    private void checkAddress(InetAddress addr, String op) {
        if (addr == null) {
            return;
        }
        if (!(addr instanceof Inet4Address || addr instanceof Inet6Address)) {
            throw new IllegalArgumentException(op + ": invalid address type");
        }
    }


    public InetAddress getInetAddress() {
        return getRealSocket().getInetAddress();
    }


    public InetAddress getLocalAddress() {
        return getRealSocket().getLocalAddress();
    }

    private Socket getRealSocket() {
        return coChannel.getSocketChannel().socket();
    }


    public int getPort() {
        return getRealSocket().getPort();
    }


    public int getLocalPort() {
        return getRealSocket().getLocalPort();
    }


    public SocketAddress getRemoteSocketAddress() {
        return getRealSocket().getRemoteSocketAddress();
    }


    public SocketAddress getLocalSocketAddress() {
        return getRealSocket().getLocalSocketAddress();
    }


    public InputStream getInputStream() throws IOException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        if (!isConnected())
            throw new SocketException("Socket is not connected");
        if (isInputShutdown())
            throw new SocketException("Socket input is shutdown");
        final CoSocket s = this;
        InputStream is = null;
        is = new CoSocketInputStream(s);
        return is;
    }


    public OutputStream getOutputStream() throws IOException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        if (!isConnected())
            throw new SocketException("Socket is not connected");
        if (isOutputShutdown())
            throw new SocketException("Socket output is shutdown");
        final CoSocket s = this;
        OutputStream os = null;
        //todo
        return os;
    }


    public void setTcpNoDelay(boolean on) throws SocketException {
        getRealSocket().setTcpNoDelay(on);
    }


    public boolean getTcpNoDelay() throws SocketException {
        return getRealSocket().getTcpNoDelay();
    }

    public void setSoLinger(boolean on, int linger) throws SocketException {
        getRealSocket().setSoLinger(on, linger);
    }


    public int getSoLinger() throws SocketException {
        return getRealSocket().getSoLinger();
    }


    //这和方法我们直接替换了,不会抛出异常
    public void setSoTimeout(int timeout) throws SocketException {
        coChannel.getConfig().setSoTimeout(timeout);
    }

    //这和方法我们直接替换了,不会抛出异常
    public int getSoTimeout() throws SocketException {
        return coChannel.getConfig().getSoTimeout();
    }

    public void setSendBufferSize(int size)
            throws SocketException {
        Socket socket = getRealSocket();
        socket.setSendBufferSize(size);
        //重新获取一下sendBufferSize
        coChannel.getConfig().setSendBufferSize(socket.getSendBufferSize());
    }


    public int getSendBufferSize() throws SocketException {
        return getRealSocket().getSendBufferSize();
    }


    public void setReceiveBufferSize(int size)
            throws SocketException {
        Socket socket = getRealSocket();
        socket.setReceiveBufferSize(size);
        //重新获取一下receiveBufferSize
        coChannel.getConfig().setReceiveBufferSize(socket.getReceiveBufferSize());
    }


    public int getReceiveBufferSize()
            throws SocketException {
        return getRealSocket().getReceiveBufferSize();
    }


    public void setKeepAlive(boolean on) throws SocketException {
        getRealSocket().setKeepAlive(on);
    }


    public boolean getKeepAlive() throws SocketException {
        return getRealSocket().getKeepAlive();
    }


    public void setTrafficClass(int tc) throws SocketException {
        getRealSocket().setTrafficClass(tc);
    }


    public int getTrafficClass() throws SocketException {
        return getRealSocket().getTrafficClass();
    }


    public void setReuseAddress(boolean on) throws SocketException {
        getRealSocket().setReuseAddress(on);
    }


    public boolean getReuseAddress() throws SocketException {
        return getRealSocket().getReuseAddress();
    }


    //linger有没有设置,有没有注册到channel上面,要区分对待
    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }
            if (!isCoChannelRelease) {
                coChannel.close();
                isCoChannelRelease = true;
            }
            // 我们在这里就不抛出异常了,因为我们要再次挂起一下协程,这样有性能问题,
            //如果是已经抛出异常的情况下,close的操作没有多大的意义,
            //可能遗漏的是一个tcp连接前面都正常,但是closed的时候有异常了,需要在应用层面体现出来
            //这个时候,我们也不管了,这种异常也往往没有多大意义,仅仅打日志如果在关闭channel正常的channel的情况下发生异常,记录下,不在使用CoSocket线程的层面抛出来
            ByteBuf readBuffer = this.readBuffer;
            ByteBuf writeBuffer = this.writeBuffer;
            //释放读写缓存
            if (readBuffer != null && !readBuffer.isReadable()) {
                this.readBuffer = null;
                readBuffer.release();
            }
            if (writeBuffer != null && !writeBuffer.isReadable()) {
                this.writeBuffer = null;
                writeBuffer.release();
            }
            closed = true;
        }
    }



    public void shutdownInput() throws IOException {
        CoSocketChannel.shutdownInput(coChannel.getSocketChannel());
    }


    public void shutdownOutput() throws IOException {
        CoSocketChannel.shutdownOutput(coChannel.getSocketChannel());
    }


    public boolean isConnected() {
        return getRealSocket().isConnected();
    }


    public boolean isBound() {
        return getRealSocket().isBound();
    }


    public boolean isClosed() {
        synchronized (closeLock) {
            return closed;
        }
    }


    public boolean isInputShutdown() {
        return coChannel.isInputShutdown();
    }


    public boolean isOutputShutdown() {
        return coChannel.isOutputShutdown();
    }


    public void setPerformancePreferences(int connectionTime,
                                          int latency,
                                          int bandwidth) {
        getRealSocket().setPerformancePreferences(connectionTime, latency, bandwidth);
    }

    //todo
    void prepareReadBuf() throws IOException {
        if (readBuffer == null) {
            if (isClosed()) {
                throw new SocketException("channel already close");
            }
            AtomicInteger status = this.status;
            int i = status.get();
            //一旦读发生异常,我们自己会记录这个异常,并且修改读异常位
            if (BitIntStatusUtils.isInStatus(i, CoSocketStatus.READ_EXCEPTION)) {
                throw new SocketException("read from channel already happen exception and already close read");
            }
            readBuffer = GlobalByteBufPool.getThreadHashGroup().applyDirect();
        }
    }

    //block代表会阻塞(挂起当前线程或者协程)的等待数据一直可用,直到读超时发生,抛出读超时异常,或者遇到eof
    //block为false的话,没有数据可读的话,就会直接返回0 代表没有任何数据可读
    //eof的话,我们就直接返回-1
    public int read(byte[] b, int off, int length, boolean isBlock) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || length < 0 || length > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (isEof) {
            return -1;
        }
        prepareReadBuf();
        if (!readBuffer.isReadable()) {
            SocketChannel channel = coChannel.getSocketChannel();
            int i;
            try {
                 i = readBuffer.writeBytes(channel, readBuffer.writableBytes());
            } catch (IOException e) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("read from channel happen error.", e);
                }
                return handlerReadException(e);
            }
            if (i < 0) {
                return handlerEof();
            }
            //tcp缓冲区里面没有数据了
            if (i == 0) {
                if (!isBlock) {
                    //不是堵塞模式的话,我们就直接返回0
                    return 0;
                } else {
                    //阻塞当前线程,然后一直等可读事件发生,超过超时时间就抛出超时异常
                    coChannel.waitForRead();
                    //当前读要被挂起了,所以我们要记录,因为没数据读而挂起的频率,防止频繁的切换导致浪费cpu
                    blockingRW.reportReadBlock();
                    //挂起当前线程
                    ssSupport.suspend();
                    //被select线程唤醒了,
                    //在读一下,没有数据gg了要抛出异常给调用者
                    try {
                        i = readBuffer.writeBytes(channel, readBuffer.writableBytes());
                    } catch (IOException e) {
                       return handlerReadException(e);
                    }
                    if(i<0){
                        return handlerEof();
                    }
                    if (i == 0) {
                        //这里就是读超时了,抛出读超时异常
                        throw new SocketTimeoutException("read from channel time out");
                    }
                }
            }
        }
        //将数据转移到数据里面
        int read = Math.min(readBuffer.readableBytes(), length);
        readBuffer.readBytes(b, off, read);
        if (!readBuffer.isReadable()) {
            //清除掉,方便下一次的使用
            readBuffer.clear();
        }
        return read;
    }

    private int handlerEof() {
        isEof = true;
        BitIntStatusUtils.casAddStatus(status, CoSocketStatus.EOF);
        //我们读到了流的最后
        return -1;
    }

    private int handlerReadException(IOException e) throws IOException {
        ByteBuf readBuffer = this.readBuffer;
        readBuffer.release();
        this.readBuffer = null;
        //设置读异常
        BitIntStatusUtils.casAddStatus(status, CoSocketStatus.READ_EXCEPTION);
        //抛出异常给调用者
        throw e;
    }

    void handlerReadTimeOut() {
        //唤醒我们等待读的线程,时间到了
        ssSupport.beContinue();
    }
    //返回true代表要关闭了读监听了,todo
    boolean handlerReadActive() {
        if (!BitIntStatusUtils.isInStatus(status.get(), CoSocketStatus.PARK_FOR_READ)) {
            //严重原因导致,当前使用CoSocket的线程不是等待读,错误场景,避免意外的唤醒阻塞的线程或者协程,关闭读监听
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("coSocket is not in park for read but has read listen");
            }
            return true;
        }
        long delay = blockingRW.reportReadActive(CoSocketEventLoop.getCurrentWakeUpTime());
        if (delay <= 0) {
            //直接就唤醒
            ssSupport.beContinue();
        } else {
            if (delayWakeUpHandler == null) {
                delayWakeUpHandler = new Runnable() {
                    @Override
                    public void run() {
                        ssSupport.beContinue();
                    }
                };
            }
            //延迟唤醒
            coChannel.eventLoop().schedule(delayWakeUpHandler, delay, TimeUnit.NANOSECONDS);
        }
        return true;
    }


    boolean handlerWriteActive() {
        //todo
        return false;
    }
}
