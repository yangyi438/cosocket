package yy.code.io.cosocket;


import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.eventloop.CoSocketEventLoop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.channels.SocketChannel;


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

    private CoSocketEventLoop coSocketEventLoop;
    CoSocketChannel coChannel;


    //抛出异常了,不满足jdk的Socket不抛出异常的要求
    public CoSocket() throws IOException {
        initChannel();
    }

    //初始化channel
    private void initChannel() throws IOException {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            //todo 传递 channel
            coChannel = new CoSocketChannel();
            created = true;
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


    private boolean created = false;
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
        initChannel();
        if (address == null)
            throw new NullPointerException();
        try {
            if (localAddr != null)
                bind(localAddr);
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
    //同时我们默认给10秒(很长了) 的连接超时时间,不允许无限的连接时间
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 10 * 1000);
    }


    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        connect(endpoint, timeout, null);
    }

    public void connect(SocketAddress endpoint, int timeout, CoSocketEventLoop eventLoop) throws IOException {
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
        coChannel.

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
        if (!isConnected())
            return null;
        try {
            return getImpl().getInetAddress();
        } catch (SocketException e) {
        }
        return null;
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
        getRealSocket().setTcpNoDelay(true);
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


    public  void setSoTimeout(int timeout) throws SocketException {
        //todo
    }


    public  int getSoTimeout() throws SocketException {
        //todo
        return 0;
    }


    public synchronized void setSendBufferSize(int size)
            throws SocketException {
        getRealSocket().setSendBufferSize(size);
    }


    public synchronized int getSendBufferSize() throws SocketException {
        return getRealSocket().getSendBufferSize();
    }


    public synchronized void setReceiveBufferSize(int size)
            throws SocketException {
        getRealSocket().setReceiveBufferSize(size);
    }


    public synchronized int getReceiveBufferSize()
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
