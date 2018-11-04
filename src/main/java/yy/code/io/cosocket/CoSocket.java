package yy.code.io.cosocket;


import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.config.CoSocketConfig;
import yy.code.io.cosocket.fiber.StrandSuspendContinueSupport;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;
import yy.code.io.cosocket.status.SelectionKeyUtils;
import yy.code.io.cosokcet.bytebuf.pool.GlobalByteBufPool;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public final class CoSocket implements Closeable {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(CoSocket.class);

    Runnable delayWakeUpHandler = null;

    //附件,某些api操作的时候,可以方便的带一个附件来操作
    Object attachment;
    public Object getAttachment() {
        return attachment;
    }

    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }
    //发生了io异常就会记录这个异常,条件允许,我们会自动关闭底层的channel
    IOException exception;
    CoSocketEventHandler eventHandler;
    StrandSuspendContinueSupport ssSupport;
    final AtomicInteger status = new AtomicInteger(0);
    //出现异常或者正常关闭的时候的标志位,代表有没有释放过资源
    boolean isHandlerRelease = false;
    boolean isEof = false;
    boolean isInputCLose = false;
    boolean isOutPutCLose = false;
    //读缓存,一次从channel里面读好多数据的,然后写到readBuffer里面
    ByteBuf readBuffer;
    //写缓存,如果使用者线程,写不是directBuffer的数据到缓存里面去,那么我们就手动先写到我们内置直接内存的writeBuffer里面去
    //然后在往真实的channel里面去写数据
    ByteBuf writeBuffer;
    BlockingReadWriteControl blockingRW = new BlockingReadWriteControl();
    //在最后一次调用写的相关的操作的时候,如果我们是以阻塞模式写的话,我们知道超过一定时间,没写成功是会抛出超时异常的
    //这个时候我们还是可以继续写的,但是一段数据,到底写了多少,就丢失了,只能得到一个异常的反馈,
    // 使用这个变量来记录最后一个写的操作到底写了多少数据,写入到我们的writeBuffer或者channel里面的数据都算是的
    private int lastWriteCount;
    CoSocketConfig config;

    //最后一次写操作,到底写了多少数据,不论抛出异常了,还是怎么了,我们都要记录,主要为了超时写,抛出异常的时候,方便知道自己到底写了多少数据
    public int getLastWriteCount() {
        return lastWriteCount;
    }

    public CoSocket() throws IOException {
        initDefault();
    }

    CoSocket(SocketChannel channel, CoSocketConfig config, CoSocketEventLoop eventLoop) throws IOException {
        initChannel(channel, config, eventLoop);
    }

    private void initDefault() throws IOException {
        SocketChannel channel = SocketChannel.open();
        CoSocketConfig config = new CoSocketConfig();
        initChannel(channel, config, CoSocketFactory.globalEventLoop.nextCoSocketEventLoop());
    }

    //初始化channel,eventLoop,StrandSuspendContinueSupport
    void initChannel(SocketChannel channel, CoSocketConfig config, CoSocketEventLoop eventLoop) throws IOException {
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
            eventHandler = new CoSocketEventHandler(this, null, channel, eventLoop);
            //暂时就定quasar作为协程的实现,不做接口的形式了,以后做成接口的方式
            ssSupport = new StrandSuspendContinueSupport();
            this.config = config;
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

        while (true) {
            int before = status.get();
            if (BitIntStatusUtils.isInStatus(before, CoSocketStatus.CONNECT_EXCEPTION)) {
                //不允许连接失败了,再去连接,
                throw new SocketException("connect already faille");
            }
            int forConnect = BitIntStatusUtils.addStatus(before, CoSocketStatus.PARK_FOR_CONNECT);
            forConnect = BitIntStatusUtils.removeStatus(forConnect, CoSocketStatus.RUNNING);
            if (status.compareAndSet(before, forConnect)) {
                break;
            }
        }
        ssSupport.prepareSuspend();
        this.config.setConnectionMilliSeconds(timeout);
        eventHandler.connect(endpoint);
        //fixme 连接不需要判断有没有close 的status操作
        ssSupport.suspend();
        //被唤醒了
        int now = status.get();
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.CONNECT_EXCEPTION)) {
            //连接异常我们就直接抛出去了,这个时候使用者只需要调用close的操作了
            assert exception != null;
            IOException exception = this.exception;
            this.exception = null;
            throw exception;
        } else if (exception != null && exception instanceof SocketTimeoutException) {
            //仅仅是超时异常的话,就抛出连接超时,不做其他的处理
            IOException exception = this.exception;
            this.exception = null;
            throw exception;
        } else if (exception == null) {
            //success
            return;
        } else {
            //连接的结果只有成功,或者失败,不应该有其他的情况的,发生了应该就是内部错误
            throw new IllegalStateException("could not happen may someError");
        }
    }

    void successConnect() {
        AtomicInteger status = this.status;
        while (true) {
            int now = status.get();
            int update = now;
            if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_CONNECT)) {
                update = BitIntStatusUtils.removeStatus(update, CoSocketStatus.PARK_FOR_CONNECT);
                update = BitIntStatusUtils.addStatus(update, CoSocketStatus.RUNNING);
                update = BitIntStatusUtils.addStatus(update, CoSocketStatus.CONNECT_SUCCESS);
                if (status.compareAndSet(now, update)) {
                    ssSupport.beContinue();
                    break;
                } else {
                    //很少到这里应该,目前出现应该算是bug,不允许并发操作 status标志位, 虽然使用了atomic类
                    continue;
                }
            } else {
                if (LOGGER.isErrorEnabled()) {
                    //不可能发生这样的情况的,出现bug才可以出现这个问题,还有可能是epoll,bug被触发的时候,注册新的
                    //selectKey失败了,调用close的方法
                    LOGGER.error("not park for connect but wakeUp it is error");
                }
                break;
            }
        }
    }

    //连接的时候发生了异常,连接超时或者其他io异常,那我们就干掉当前的连接
    //注意的是,这个被io调度线程来调度使用的触发连接异常的
    void errorConnect(IOException e) {
        while (true) {
            int status = this.status.get();
            int update;
            if (BitIntStatusUtils.isInStatus(status, CoSocketStatus.PARK_FOR_CONNECT)) {
                update = BitIntStatusUtils.removeStatus(status, CoSocketStatus.PARK_FOR_CONNECT);
            } else {
                if (LOGGER.isErrorEnabled()) {
                    //不可能发生这样的情况的,只有epoll的bug 在rebuild的时候,重新注册selectKey的时候才会发生这样的事情
                    LOGGER.error("not park for connect but wakeUp it is error");
                }
                this.exception = null;
                //这里直接return 不 ssSupport.beContinue(); 了
                return;
            }
            update = BitIntStatusUtils.addStatus(update, CoSocketStatus.CONNECT_EXCEPTION);
            update = BitIntStatusUtils.addStatus(update, CoSocketStatus.RUNNING);
            //理论上来说,不应该发生失败的情况的,必须的一次成功的
            if (this.status.compareAndSet(status, update)) {
                this.exception = e;
                break;
            }
        }
        ssSupport.beContinue();
        //之后coSocket线程应该就会被唤醒
    }

    //连接超时
    void timeOutConnect(SocketTimeoutException timeOut) {
        while (true) {
            int status = this.status.get();
            int update;
            if (BitIntStatusUtils.isInStatus(status, CoSocketStatus.PARK_FOR_CONNECT)) {
                update = BitIntStatusUtils.removeStatus(status, CoSocketStatus.PARK_FOR_CONNECT);
            } else {
                if (LOGGER.isErrorEnabled()) {
                    //不可能发生这样的情况的,只有epoll的bug 在rebuild的时候,重新注册selectKey的时候才会发生这样的事情
                    LOGGER.error("not park for connect but wakeUp it is error");
                }
                this.exception = null;
                //这里直接return 不 ssSupport.beContinue(); 了
                return;
            }
            update = BitIntStatusUtils.addStatus(update, CoSocketStatus.RUNNING);
            //理论上来说,不应该发生失败的情况的,必须的一次成功的
            if (this.status.compareAndSet(status, update)) {
                this.exception = timeOut;
                break;
            }
        }
        ssSupport.beContinue();
        //之后coSocket线程应该就会被唤醒
    }


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
        eventHandler.getSocketChannel().bind(bindpoint);
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
        return eventHandler.getSocketChannel().socket();
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
        InputStream is;
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
        return new CoSocketOutputStream(s);
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
        this.config.setSoTimeout(timeout);
    }

    //这和方法我们直接替换了,不会抛出异常
    public int getSoTimeout() throws SocketException {
        return this.config.getSoTimeout();
    }

    public void setSendBufferSize(int size)
            throws SocketException {
        Socket socket = getRealSocket();
        socket.setSendBufferSize(size);
        //重新获取一下sendBufferSize
        this.config.setSendBufferSize(socket.getSendBufferSize());
    }


    public int getSendBufferSize() throws SocketException {
        return getRealSocket().getSendBufferSize();
    }


    public void setReceiveBufferSize(int size)
            throws SocketException {
        Socket socket = getRealSocket();
        socket.setReceiveBufferSize(size);
        //重新获取一下receiveBufferSize
        this.config.setReceiveBufferSize(socket.getReceiveBufferSize());
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


    //linger有没有设置,有没有注册到channel上面,要区分对待,见eventHandler.close();方法
    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }
            if (!isHandlerRelease) {
                eventHandler.close();
                isHandlerRelease = true;
            }
            // 我们在这里就不抛出异常了,因为我们要再次挂起一下协程,这样有性能问题,
            //如果是已经抛出异常的情况下,close的操作没有多大的意义,
            //可能遗漏的是一个tcp连接前面都正常,但是closed的时候有异常了,需要在应用层面体现出来
            //这个时候,我们也不管了,这种异常也往往没有多大意义,仅仅打日志如果在关闭channel正常的channel的情况下发生异常,记录下,不在使用CoSocket线程的层面抛出来

            //释放读写缓存
            releaseReadBuffer();
            releaseWriteBuffer();
            closed = true;
        }
    }

    private void releaseWriteBuffer() {
        ByteBuf writeBuffer = this.writeBuffer;
        if (writeBuffer != null) {
            this.writeBuffer = null;
            writeBuffer.release();
        }
    }

    //fixme 我们不会挂起线程,等到关闭操作被IO线程操作并真实的发生,并且等待回馈,这样有代价,
    //大多情况我们认为这样是没有意义的,关闭的时候发生了异常又如何,提升效率,牺牲了一些东西
    public void shutdownInput() throws IOException {
        checkConnectOrClose();
        if (isInputCLose) {
            return;
        }
        eventHandler.shutdownInput();
        releaseReadBuffer();
        isInputCLose = true;
    }

    private void releaseReadBuffer() {
        ByteBuf readBuffer = this.readBuffer;
        if (readBuffer != null) {
            readBuffer.release();
            this.readBuffer = null;
        }
    }

    //检测连接或者关闭的问题
    private void checkConnectOrClose() throws IOException {
        if (!isConnected()) {
            throw new SocketException("Socket is not connected");
        }
        if (isClosed()) {
            throw new SocketException("Socket closed");
        }
        int now = this.status.get();
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.CLOSE)) {
            throw new SocketException("channel closed");
        }
    }

    public void shutdownOutput() throws IOException {
        checkConnectOrClose();
        if (isOutPutCLose) {
            return;
        }
        eventHandler.shutdownOutput();
        releaseWriteBuffer();
        isOutPutCLose = true;
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
        return isInputCLose;
    }


    public boolean isOutputShutdown() {
        return isOutPutCLose;
    }


    public void setPerformancePreferences(int connectionTime,
                                          int latency,
                                          int bandwidth) {
        getRealSocket().setPerformancePreferences(connectionTime, latency, bandwidth);
    }

    public int write(int b, boolean block) throws IOException {
        prepareWriteBuf();
        prepareWritable();
        int i = writeBuffer.writableBytes();
        if (i > 0) {
            writeBuffer.writeByte(b);
            return 1;
        } else {
            flushInternal(false);
            i = writeBuffer.writableBytes();
            if (i > 0) {
                writeBuffer.writeByte(b);
                return 1;
            } else {
                if (block) {
                    flushInternal(true);
                    writeBuffer.writeByte(b);
                    return 1;
                } else {
                    //非阻塞,返回0
                    return 0;
                }
            }
        }
    }


    //block为true代表一定要刷新所有数据,否则,抛出io异常,或者超时异常,getLastWriteCount返回超时异常的时候,到底写了数据
    public int write(byte b[], int off, int len, boolean block) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException("off and len out of index");
        } else if (len == 0) {
            return 0;
        }
        prepareWriteBuf();
        prepareWritable();
        int writableBytes;
        this.lastWriteCount = 0;
        while (true) {
            writableBytes = writeBuffer.writableBytes();
            if (writableBytes > 0) {
                int writeHope = Math.min(writableBytes, len);
                writeBuffer.writeBytes(b, off, writeHope);
                len -= writeHope;
                off += writeHope;
                this.lastWriteCount += writeHope;
                if (len <= 0) {
                    //所有数据写完了
                    return lastWriteCount;
                } else {
                    continue;
                }
            } else {
                int writable = flushInternal(false);
                if (writable > 0) {
                    continue;
                } else {
                    if (block) {
                        flushInternal(true);
                        continue;
                    } else {
                        return lastWriteCount;
                    }
                }
            }
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
        prepareReadBuf();
        int readableBytes = prepareReadableBytes();
        if (readableBytes < 0) {
            //-1代表eof了
            return -1;
        }
        //tcp缓冲区里面没有数据了
        if (readableBytes == 0) {
            if (!isBlock) {
                //不是堵塞模式的话,我们就直接返回0
                return 0;
            } else {
                blockForReadWithTimeOut();
                //被selector线程唤醒了,
                //在读一下,没有数据gg了要抛出异常给调用者
                readableBytes = prepareReadableBytes();
                if (readableBytes == 0) {
                    //这里就是读超时了,抛出读超时异常
                    throw new SocketTimeoutException("read from channel time out");
                }
                if (readableBytes < 0) {
                    //-1代表eof了
                    return -1;
                }
            }
        }
        //将数据转移到数组里面
        int read = Math.min(readableBytes, length);
        readBuffer.readBytes(b, off, read);
        return read;
    }

    private void blockForReadWithTimeOut() throws IOException {
        //当前读要被挂起了,所以我们要记录,因为没数据读而挂起的频率,防止频繁的切换导致浪费cpu
        blockingRW.reportReadBlock();
        //阻塞当前线程,然后一直等可读事件发生,超过超时时间就抛出超时异常
        //利用cas的完成可见性问题
        ssSupport.prepareSuspend();
        AtomicInteger status = this.status;
        while (true) {
            int now = status.get();
            if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.CLOSE)) {
                releaseReadBuffer();
                throw new SocketException("channel closed");
            }
            int update = BitIntStatusUtils.convertStatus(now, CoSocketStatus.RUNNING, CoSocketStatus.PARK_FOR_READ);
            if (status.compareAndSet(now, update)) {
                break;
            }
        }
        this.eventHandler.timeoutForReadActive(eventHandler.readTimeoutHandler, this.config.getSoTimeout());
        //挂起当前线程
        ssSupport.suspend();
        int now = status.get();
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.READ_EXCEPTION)) {
            //提前释放读缓存
            releaseReadBuffer();
            IOException exception = this.exception;
            //必须异常
            assert exception != null;
            throw exception;
        }
    }

    /**
     * @return 读取的的byte的数据, 会堵塞的读
     * @throws IOException
     */
    public int readBytes() throws IOException {
        prepareReadBuf();
        int readableBytes = prepareReadableBytes();
        if (readableBytes > 0) {
            return readBuffer.readByte();
        }
        if (readableBytes == 0) {
            blockForReadWithTimeOut();
            readableBytes = prepareReadableBytes();
            if (readableBytes > 0) {
                return readBuffer.readByte();
            }
            if (readableBytes == 0) {
                //还没有读到数据,就抛抛出超时异常了
                throw new SocketTimeoutException("read Time out exception");
            }
            //eof
            return -1;
        }
        //eof
        return -1;
    }

    /**
     * @return 还有多少可读的数据, 在不堵塞的情况下, 就是返回当前read缓冲区的数据, fixme 有可能返回0
     */
    public int available() throws IOException {
        prepareReadBuf();
        //
        return readBuffer.readableBytes();
    }


    void prepareReadBuf() throws IOException {
        if (readBuffer == null) {
            checkConnectOrClose();
            if (isInputShutdown()) {
                //输入流已经关闭了
                throw new SocketException("input already close");
            }
            checkReadOrWritable(CoSocketStatus.READ_EXCEPTION, "read from channel already happen exception and already close read");
            readBuffer = GlobalByteBufPool.getThreadHashGroup().applyDirect();
        }
    }

    void prepareWriteBuf() throws IOException {
        if (writeBuffer == null) {
            checkConnectOrClose();
            if (isOutputShutdown()) {
                //输入流已经关闭了
                throw new SocketException("outputStream already close");
            }
            checkReadOrWritable(CoSocketStatus.WRITE_EXCEPTION, "write from channel already happen exception and already close read");
            writeBuffer = GlobalByteBufPool.getThreadHashGroup().applyDirect();
        }
    }

    //准备writeBuffer处于可以写的状态,配合prepareWriteBuf一起使用,不然有npe的可能性,方法返回后,writeBuffer必定是可写的状态
    private void prepareWritable() throws IOException {
        if (!writeBuffer.isWritable()) {
            if (!writeBuffer.isReadable()) {
                writeBuffer.clear();
            } else {
                //未必刷新所有数据到channel里面去
                flushInternal(false);
            }
        }
    }

    private void checkReadOrWritable(int avoidStatus, String s) throws SocketException {
        if (isClosed()) {
            throw new SocketException("channel already close");
        }
        AtomicInteger status = this.status;
        int now = status.get();
        if (BitIntStatusUtils.isInStatus(now, avoidStatus)) {
            throw new SocketException(s);
        }
    }


    public int flush(boolean blockForFlush) throws IOException {
        prepareWriteBuf();
        return flushInternal(blockForFlush);
    }

    /**
     * @param block true will block to  flush all buffer to channel
     * @throws IOException
     */
    private int flushInternal(boolean block) throws IOException {
        if (writeBuffer == null) {
            checkReadOrWritable(CoSocketStatus.WRITE_EXCEPTION, "write from channel already happen exception and already close read");
            //writeBuffer为空的情况,直接就盛情一个
            writeBuffer = GlobalByteBufPool.getThreadHashGroup().applyDirect();
        } else {
            int readableBytes = writeBuffer.readableBytes();
            if (readableBytes > 0) {
                int i;
                while (true) {
                    SocketChannel socketChannel = eventHandler.getSocketChannel();
                    try {
                        i = writeBuffer.readBytes(socketChannel, writeBuffer.readableBytes());
                    } catch (IOException e) {
                        //释放ByteBuf,保存写异常的标志位,并且以后再也不允许写了,
                        //注意这里,我们也会丢失掉异常发生之前,到底写入channel多少数据,很尴尬的问题
                        //也没必要统计写了多少数据,直接释放内存
                        writeBuffer.release();
                        writeBuffer = null;
                        BitIntStatusUtils.casAddStatus(this.status, CoSocketStatus.WRITE_EXCEPTION);
                        //抛出异常出来
                        throw e;
                    }
                    if (i == 0) {
                        //tcp发送缓冲区满了
                        if (block) {
                            //挂起,让io线程来写完所有数据,然后被唤醒,或者抛出异常,被挂起的时间由
                            // public void setInitialFlushBlockMilliSeconds(long minBlockingTime) {}
                            //    public void setMilliSecondPer1024B(long per1024B) {}
                            //来决定的,超过时间还没有由io线程写完,就会抛出SocketTimeOutException,写超时异常
                            waitForFLushWriteBuffer();
                            //因为超过写限制的时间,还没有写完所有数据到channel里面,所以抛出SocketTimeoutException
                            if (writeBuffer.isReadable()) {
                                //有必要的话,瘦身下writeBuffer,方便以后写数据
                                thinWriteBuffer(writeBuffer);
                                throw new SocketTimeoutException("write timeOut and not flush all buffer to channel");
                            } else {
                                writeBuffer.clear();
                            }
                            break;
                        } else {
                            //非阻塞模式,不用flush所有数据到channel里面,但是检查下,是否需要瘦身下
                            thinWriteBuffer(writeBuffer);
                            return writeBuffer.writableBytes();
                        }
                    }
                    if (!writeBuffer.isReadable()) {
                        writeBuffer.clear();
                        break;
                    }
                }
            } else {
                if (writeBuffer.writerIndex() != 0) {
                    //写索引不处于0的时候,就clear,防止占坑
                    writeBuffer.clear();
                }
            }
        }
        return writeBuffer.writableBytes();
    }

    //当writeIndex和readIndex靠近capacity,这样浪费了很多空间了,定八分之一为界限(有模糊的空间),超过就thin一下
    private void thinWriteBuffer(ByteBuf buffer) {
        int readerIndex = buffer.readerIndex();
        int capacity = buffer.capacity();
        int oneOf8 = capacity >> 3;
        int sOf8 = capacity - oneOf8;
        if (readerIndex >= sOf8) {
            //瘦身,留下空间来方便去写数据
            buffer.discardReadBytes();
        }
    }

    //等待写完缓冲区中所有的数据,或者写超时,然后就唤醒
    private void waitForFLushWriteBuffer() throws IOException {
        AtomicInteger status = this.status;
        //writeTimeoutHandler  需要cancel
        //修改标志status为等待write
        //利用cas解决可见性的问题
        ssSupport.prepareSuspend();
        while (true) {
            int before = status.get();
            if (BitIntStatusUtils.isInStatus(before, CoSocketStatus.CLOSE)) {
                releaseWriteBuffer();
                ssSupport.clean();
                //close的状态
                throw new SocketException("channel already close");
            }
            int now = BitIntStatusUtils.removeStatus(before, CoSocketStatus.RUNNING);
            now = BitIntStatusUtils.addStatus(now, CoSocketStatus.PARK_FOR_WRITE);
            if (status.compareAndSet(before, now)) {
                break;
            }
        }
        eventHandler.flushWriteBuffer();
        //在这里挂起了
        ssSupport.suspend();
        //被唤醒了
        int now = this.status.get();
        if (BitIntStatusUtils.isInStatus(now, CoSocketStatus.WRITE_EXCEPTION)) {
            releaseWriteBuffer();
            //写超时发生了异常
            IOException exception = this.exception;
            assert exception != null;
            this.exception = null;
            throw exception;
        }
    }

    // 读索引到最后就要clear一下
    private int prepareReadableBytes() throws IOException {
        if (!readBuffer.isReadable()) {
            if (isEof) {
                return -1;
            }
            readBuffer.clear();
            SocketChannel channel = eventHandler.getSocketChannel();
            int i;
            int writableBytes = readBuffer.writableBytes();
            try {
                i = readBuffer.writeBytes(channel, writableBytes);
                blockingRW.reportReadNoneBlocking(i, i < writableBytes);
            } catch (IOException e) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("read from channel happen error.", e);
                }
                return handlerReadException(e);
            }
            if (i < 0) {
                return handlerEof();
            }
            if (i == 0) {
                return 0;
            }
            return readBuffer.readableBytes();
        } else {
            return readBuffer.readableBytes();
        }
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
        //唤醒我们等待读的线程,时间到了,加上判断,我们的状态机是不是处于等待读的状态
        AtomicInteger status = this.status;
        int now = status.get();
        if (!BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_READ)) {
            //不是等待读就over了
            return;
        }
        CoSocketStatus.changeParkReadToRunning(status);
        ssSupport.beContinue();
    }

    //返回true代表要关闭了读监听了,由io线程调用的函数
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
            //将要唤醒了,也直接唤醒状态机
            CoSocketStatus.changeParkReadToRunning(this.status);
            //直接就唤醒
            ssSupport.beContinue();
        } else {
            if (delayWakeUpHandler == null) {
                delayWakeUpHandler = () -> {
                    if (BitIntStatusUtils.isInStatus(this.status.get(), CoSocketStatus.PARK_FOR_READ)) {
                        CoSocketStatus.changeParkReadToRunning(this.status);
                    }
                    ssSupport.beContinue();
                };
            }
            //延迟唤醒,纳秒为单位
            eventHandler.getEventLoop().schedule(delayWakeUpHandler, delay, TimeUnit.NANOSECONDS);
        }
        return true;
    }

    /**
     * @return true will close the writeListen  false will not
     */
    boolean handlerWriteActive() {
        AtomicInteger status = this.status;
        int now = status.get();
        if (!BitIntStatusUtils.isInStatus(now, CoSocketStatus.PARK_FOR_WRITE)) {
            //不可能,不因该发生这样的事情
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("could not happen PARK_FOR_WRITE may some error");
            }
            return true;
        }
        int max = this.config.getMaxWriteSizePerOnce();
        SocketChannel channel = eventHandler.getSocketChannel();
        try {
            while (max >= 0) {
                int readableBytes = writeBuffer.readableBytes();
                if (readableBytes == 0) {
                    break;
                }
                int i = writeBuffer.readBytes(channel, readableBytes);
                if (i == 0) {
                    //一点都没写进去,return false 表示接着等 写时间发生
                    return false;
                }
                //完成写的操作
                max--;
            }
            int readableBytes = writeBuffer.readableBytes();
            if (readableBytes > 0) {
                //和netty一样,给一个task,延迟下write到Channel里面,防止一直占用资源
                writeLastWithTask();
                return true;
            }
            //写完了,也直接关闭写监听,并且释放写超时task
            cancelWriteTimeOut();
            //唤醒挂起的线程或者协程
            CoSocketStatus.changeParkWriteToRunning(this.status);
            ssSupport.beContinue();
            return true;
        } catch (IOException e) {
            //写异常也要关闭写超时
            cancelWriteTimeOut();
            while (true) {
                int before = status.get();
                int writeError = BitIntStatusUtils.addStatus(before, CoSocketStatus.WRITE_EXCEPTION);
                writeError = BitIntStatusUtils.addStatus(writeError, CoSocketStatus.RUNNING);
                writeError = BitIntStatusUtils.removeStatus(writeError, CoSocketStatus.PARK_FOR_WRITE);
                if (status.compareAndSet(before, writeError)) {
                    this.exception = e;
                    ssSupport.beContinue();
                    //关闭写监听
                    return true;
                }
            }
        }
    }

    private void cancelWriteTimeOut() {
        ScheduledFuture<?> writeTimeoutFuture = eventHandler.writeTimeoutFuture;
        if (writeTimeoutFuture != null) {
            //写超时的task记得cancel
            writeTimeoutFuture.cancel(false);
            eventHandler.writeTimeoutFuture = null;
        }
    }

    //部分的写的任务包装成一个task来延迟执行
    private void writeLastWithTask() {
        eventHandler.getEventLoop().execute(() -> {
            boolean closeW = handlerWriteActive();
            if (closeW) {
                SelectionKey selectionKey = eventHandler.getSelectionKey();
                SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_WRITE);
            }
        });
    }

    //希望跳过的数据,返回真实的跳过的数据
    public long skip(long numHopeSkip) throws IOException {
        prepareReadBuf();
        int readableBytes = readBuffer.readableBytes();
        if (readableBytes == 0) {
            return 0;
        }
        long realSkip = Math.min(readableBytes, numHopeSkip);
        readBuffer.skipBytes((int) realSkip);
        return realSkip;
    }

    //写发生阻塞的时候,最小的阻塞时间,配合的还有一个,多少毫秒每K数据的堵塞时间,
    // 总的允许的阻塞时间为 initial + N(K) * MilliSecondPer1024B
    //我们不允许无限堵塞的发生,超过时间就会抛出SocketTimeOutException
    public void setInitialFlushBlockMilliSeconds(int minBlockingTime) {
        this.config.setInitialBlockMilliSeconds(minBlockingTime);
    }

    public void setMilliSecondPer1024B(int per1024B) {
        this.config.setMilliSecondPer1024B(per1024B);
    }

    public long getInitialFlushBlockMilliSeconds() {
        return this.config.getInitialBlockMilliSeconds();
    }

    //写数据的时候,每1024b的数据需要叠加的延迟时间
    public long getMilliSecondPer1024B() {
        return this.config.getMilliSecondPer1024B();
    }



}
