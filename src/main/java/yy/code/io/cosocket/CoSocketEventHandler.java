package yy.code.io.cosocket;

import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;
import yy.code.io.cosocket.status.SelectionKeyUtils;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public class CoSocketEventHandler extends AbstractNioChannelEventHandler {

    private static InternalLogger logger = InternalLoggerFactory.getInstance(CoSocketEventHandler.class);

    private final CoSocket2 coSocket2;

    Runnable readTimeoutHandler = new Runnable() {
        @Override
        public void run() {
            SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_READ);
            coSocket2.handlerReadTimeOut();
        }
    };

    ScheduledFuture<?> connectTimeoutFuture;
    ScheduledFuture<?> readTimeoutFuture;
    ScheduledFuture<?> writeTimeoutFuture;

    public CoSocketEventHandler(CoSocket2 coSocket2, SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        super(selectionKey, socketChannel, eventLoop);
        this.coSocket2 = coSocket2;
        assert socketChannel != null;
        assert eventLoop != null;
        assert coSocket2 != null;
    }

    @Override
    protected WriteEventHandler getWriteHandler() {
        return null;
    }

    @Override
    protected ConnectEventHandler getConnectHandler() {
        return null;
    }

    @Override
    protected ReadEventHandler getReadHandler() {
        return null;
    }

    @Override
    protected CloseEventHandler getCloseHandler() {
        return null;
    }

    public void timeoutForReadActive( Runnable runnable,int maxWaitTime) {
        if (eventLoop.inEventLoop()) {
            blockingForReadActive0(runnable,maxWaitTime);
        } else {
            eventLoop.execute(() -> blockingForReadActive0(runnable, maxWaitTime));
        }
    }

    public void blockingForReadActive0(Runnable runnable,int maxWaitTime) {
        if (!checkKey(SelectionKey.OP_READ, CoSocketStatus.PARK_FOR_READ, CoSocketStatus.READ_EXCEPTION)) {
            return;
        }
        SelectionKeyUtils.listenOps(selectionKey, SelectionKey.OP_READ);
        this.readTimeoutFuture = eventLoop.schedule(runnable, maxWaitTime, TimeUnit.MILLISECONDS);
    }

    private boolean checkKey(int listenOps, int alsoStatus, int registerErrorOps) {
        if (selectionKey == null) {
            //第一次还没有注册的情况
            try {
                selectionKey = socketChannel.register(eventLoop.unwrappedSelector(), listenOps, this);
            } catch (Exception e) {
                if (logger.isTraceEnabled()) {
                    logger.trace("register for read happen error .", e);
                }
                if (!(e instanceof IOException)) {
                    e = new IOException(e);
                }
                //调用连接错误
                AtomicInteger status = coSocket2.status;
                while (true) {
                    int now = status.get();
                    if (!BitIntStatusUtils.isInStatus(now, alsoStatus)) {
                        return false;
                    }
                    int update;
                    update = BitIntStatusUtils.convertStatus(now, alsoStatus, CoSocketStatus.RUNNING);
                    update = BitIntStatusUtils.addStatus(update, registerErrorOps);
                    if (status.compareAndSet(now, update)) {
                        coSocket2.exception = (IOException) e;
                        coSocket2.ssSupport.beContinue();
                        return false;
                    }
                }
            }
        }
        return true;
    }

}
