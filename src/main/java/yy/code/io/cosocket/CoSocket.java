package yy.code.io.cosocket;


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

    void errorConnect(IOException e) {
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
    private StrandSuspendContinueSupport strandSuspendContinueSupport;
    private AtomicInteger status = new AtomicInteger(0);

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
            strandSuspendContinueSupport = new StrandSuspendContinueSupport();
            this.coSocketEventLoop = eventLoop;
        } catch (IOException e) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("open channel happen ioException{}", e);
            }
            if (channel != null) {
                //关闭的时候又出现异常就忽略了,直接抛出来
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
    //同时我们默认给3秒(很长了) 的连接超时时间,不允许无限的连接时间
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
        AtomicInteger status = this.status;
        int forConntect = BitIntStatusUtils.addStatus(status.get(), CoSocketStatus.PARK_FOR_CONNECT);
        status.set(forConntect);
        coChannel.bind(epoint);
        strandSuspendContinueSupport.suspend();
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
        checkAddress(addr, "bind");
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
        return coChannel.getChannel().socket();
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


    //todo 关闭连接的时候需要判断当前连接是否失效,有效等,
    //linger有没有设置,有没有注册到channel上面
    public void close() throws IOException {
        //todo
    }


    public void shutdownInput() throws IOException {
        CoSocketChannel.shutdownInput(coChannel.getChannel());
    }


    public void shutdownOutput() throws IOException {
        CoSocketChannel.shutdownOutput(coChannel.getChannel());
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
