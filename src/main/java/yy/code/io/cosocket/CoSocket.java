package yy.code.io.cosocket;


import io.netty.buffer.ByteBuf;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import io.netty.channel.nio.CoSocketEventLoop;
import yy.code.io.cosocket.fiber.StrandSuspendContinueSupport;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;


public final class CoSocket {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(CoSocket.class);

    void successConnect() {
    }

    //连接的时候发生了异常,连接超时或者其他io异常,那我们就干掉当前的连接
    //注意的是,这个被io调度线程来调度使用的触发连接异常的
    void errorConnect(IOException e) {
        this.exception = e;
        while (true) {
            int status = this.status.get();
            int error = BitIntStatusUtils.addStatus(status, CoSocketStatus.EXCEPTION);
            //理论上来说,不应该发生失败的情况的,必须的一次成功
            if (this.status.compareAndSet(status,error)) {
                break;
            }
        }
        ssSupport.beContinue();
        //之后coSocket线程应该就会被唤醒
    }


    boolean handlerReadActive() {
        return false;
    }

    boolean handlerWriteActive() {
        return false;
    }

    //发生了io异常就会记录这个异常,条件允许,我们会自动底层的channel
    private IOException exception;
    private CoSocketEventLoop coSocketEventLoop;
    CoSocketChannel coChannel;
    private StrandSuspendContinueSupport ssSupport;
    private AtomicInteger status = new AtomicInteger(0);
    //出现异常或者正常关闭的时候的标志位,代表有没有释放过资源
     boolean isRelease = false;
    //读缓存,一次从channel里面读好多数据的,然后写到readBuffer里面
    private ByteBuf readBuffer;
    //写缓存,如果使用者线程,写不是directBuffer的数据到缓存里面去,那么我们就手动先写到我们内置直接内存的writeBuffer里面去
    //然后在往真实的channel里面去写数据
    private ByteBuf writeBuffer;

    public CoSocket() throws IOException {
        initDefault();
    }

    private void initDefault() throws IOException {
        initChannel(new CoSocketConfig(), CoSocketFactory.globalEventLoop.nextCoSocketEventLoop());
    }

    //初始化channel,eventLoop,StrandSuspendContinueSupport
    private void initChannel(CoSocketConfig config, CoSocketEventLoop eventLoop) throws IOException {
        SocketChannel channel = null;
        assert eventLoop != null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            if (config == null) {
                config = new CoSocketConfig();
            }
            coChannel = new CoSocketChannel(channel, config, this,eventLoop);
            //暂时就定quasar作为协程的实现,不做接口的形式了
            ssSupport = new StrandSuspendContinueSupport();
            this.coSocketEventLoop = eventLoop;
        } catch (IOException e) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("open channel happen ioException{}", e);
            }
            if (channel != null) {
                //关闭的时候又出现异常就忽略了,没办法在处理了
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
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.EXCEPTION)) {
            assert exception != null;
            throw exception;
        } else {
            //连接的结果只有成功,或者失败,不应该有其他的情况的,发生了应该就是内部错误
            throw new IllegalStateException("could not happen may someError");
        }
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
        //todo
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


    public void setSoTimeout(int timeout) throws SocketException {
        //todo
    }


    public int getSoTimeout() throws SocketException {
        //todo
        return 0;
    }


    public  void setSendBufferSize(int size)
            throws SocketException {
        getRealSocket().setSendBufferSize(size);
    }


    public  int getSendBufferSize() throws SocketException {
        return getRealSocket().getSendBufferSize();
    }


    public  void setReceiveBufferSize(int size)
            throws SocketException {
        getRealSocket().setReceiveBufferSize(size);
    }


    public  int getReceiveBufferSize()
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



    //linger有没有设置,有没有注册到channel上面
    public void close() throws IOException {
        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }
            if (!isRelease) {
                AtomicInteger now = this.status;
                int stat = now.get();
                if (BitIntStatusUtils.isInStatus(stat, CoSocketStatus.EXCEPTION)) {
                    //是异常的话,可以确定是io线程设定这个状态的,并且会自动释放资源,所以我们就不用担心了
                    //只是修改一下标志位
                    isRelease = true;
                    closed = true;
                    return;
                } else {
                    //这种就是直接使用CoSocket的api的时候直接发生的异常,然后需要调用关闭的api,来确保释放资源
                    // 或者是正常的调用close的操作来关闭资源
                    //这个时候的close的操作是必须的操作,我们
                    // 我们在这里就不抛出异常了,因为我们要再次挂起一下协程,这样有性能问题,
                    //如果是已经抛出异常的情况下,clsoe的操作没有多大的意义,
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
                    coChannel.close();
                }
            }
            closed = true;
        }
    }


    //因为连接超时而关闭
    private void closeByErrorConnect() {

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

}
