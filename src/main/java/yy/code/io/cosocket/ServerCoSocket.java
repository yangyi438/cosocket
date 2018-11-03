package yy.code.io.cosocket;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import yy.code.io.cosocket.eventloop.CoSocketEventLoopGroup;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;



/**
 * Created by ${good-yy} on 2018/10/29.
 */
//服务端版本的CoSocket
public class ServerCoSocket {
    /**
     * Various states of this socket.
     */
    private final ServerSocketChannel serverSocketChannel;
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(ServerCoSocket.class);


    private CoSocketEventLoopGroup eventLoopGroup = CoSocketFactory.globalEventLoop;

    public void setEventLoopGroupo(CoSocketEventLoopGroup eventLoopGroup) {
        assert eventLoopGroup != null;
        this.eventLoopGroup = eventLoopGroup;
    }

    public ServerCoSocket() throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
    }


    public ServerCoSocket(int port) throws IOException {
        this(port, 50, null);
    }


    public ServerCoSocket(int port, int backlog) throws IOException {
        this(port, backlog, null);
    }

    public ServerCoSocket(int port, int backlog, InetAddress bindAddr) throws IOException {

        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException(
                    "Port value out of range: " + port);
        if (backlog < 1)
            backlog = 50;
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(true);
            bind(new InetSocketAddress(bindAddr, port), backlog);
        } catch (SecurityException e) {
            close();
            throw e;
        } catch (IOException e) {
            close();
            throw e;
        }
    }


    public void bind(SocketAddress endpoint) throws IOException {
        bind(endpoint, 50);
    }


    public void bind(SocketAddress endpoint, int backlog) throws IOException {
        serverSocketChannel.socket().bind(endpoint, backlog);
    }

    public InetAddress getInetAddress() {
        return serverSocketChannel.socket().getInetAddress();
    }


    public int getLocalPort() {
        return serverSocketChannel.socket().getLocalPort();
    }


    public SocketAddress getLocalSocketAddress() {
        return serverSocketChannel.socket().getLocalSocketAddress();
    }


    public CoSocket accept() throws IOException {
        Socket socket = serverSocketChannel.socket().accept();
        SocketChannel channel = socket.getChannel();
        try {
            return new CoSocket(channel, new CoSocketConfig(), this.eventLoopGroup.nextCoSocketEventLoop());
        } catch (IOException e) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("accept error.",e);
            }
            try {
                if (channel != null) {
                    //关闭资源
                    channel.close();
                }
            } catch (IOException ioException) {
                //打印日志而且忽略异常,close的时候,没办法处理了
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("accept error and close channel also happen error.", ioException);
                }
            }
            throw e;
        }
    }



    public void close() throws IOException {
        serverSocketChannel.close();
    }

    //不暴漏serverSocketChannel
    public ServerSocketChannel getChannel() {
        return null;
    }


    public boolean isBound() {
        return serverSocketChannel.socket().isBound();
    }


    public boolean isClosed() {
        return serverSocketChannel.socket().isClosed();
    }


    public synchronized void setSoTimeout(int timeout) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        serverSocketChannel.socket().setSoTimeout(timeout);
    }


    public  int getSoTimeout() throws IOException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        return serverSocketChannel.socket().getSoTimeout();
    }


    public void setReuseAddress(boolean on) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        serverSocketChannel.socket().setReuseAddress(on);
    }


    public boolean getReuseAddress() throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        return serverSocketChannel.socket().getReuseAddress();
    }


    public String toString() {
        if (!isBound())
            return "ServerSocket[unbound]";
        InetAddress in;
        if (System.getSecurityManager() != null)
            in = InetAddress.getLoopbackAddress();
        else
            in = this.getInetAddress();
        return "ServerSocket[addr=" + in +
                ",localport=" + this.getLocalPort() + "]";
    }


    public  void setReceiveBufferSize(int size) throws SocketException {
        if (!(size > 0)) {
            throw new IllegalArgumentException("negative receive size");
        }
        if (isClosed())
            throw new SocketException("Socket is closed");
        serverSocketChannel.socket().setReceiveBufferSize(size);
    }


    public  int getReceiveBufferSize()
            throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        return serverSocketChannel.socket().getReceiveBufferSize();
    }


    public void setPerformancePreferences(int connectionTime,
                                          int latency,
                                          int bandwidth) {

    }

}