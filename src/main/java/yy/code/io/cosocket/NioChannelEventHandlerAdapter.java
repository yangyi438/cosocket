package yy.code.io.cosocket;

import io.netty.channel.nio.CoSocketEventLoop;
import yy.code.io.cosocket.status.SelectionKeyUtils;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Created by ${good-yy} on 2018/11/10.
 */
public class NioChannelEventHandlerAdapter extends AbstractNioChannelEventHandler {

    public NioChannelEventHandlerAdapter(SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        super(selectionKey, socketChannel, eventLoop);
    }

    private static final WriteEventHandler IGNORE_WRITE = new WriteEventHandler() {
        @Override
        public void writeEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_WRITE);
        }
    };

    private static final ReadEventHandler IGNORE_READ = new ReadEventHandler() {
        @Override
        public void readEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_READ);
        }
    };

    private static final ConnectEventHandler IGNORE_CONNECT = new ConnectEventHandler() {
        @Override
        public void connectEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            SelectionKeyUtils.removeOps(selectionKey, SelectionKey.OP_CONNECT);
        }
    };

    private static final CloseEventHandler CLOSE_NOW = new CloseEventHandler() {
        @Override
        public void closeEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop) {
            AbstractNioChannelEventHandler.SafeCloseEventLoopNioChannel(selectionKey, eventLoop, channel);
        }
    };

    @Override
    protected WriteEventHandler getWriteHandler() {
        return IGNORE_WRITE;
    }

    @Override
    protected ConnectEventHandler getConnectHandler() {
        return IGNORE_CONNECT;
    }

    @Override
    protected ReadEventHandler getReadHandler() {
        return IGNORE_READ;
    }

    @Override
    protected CloseEventHandler getCloseHandler() {
        return CLOSE_NOW;
    }

//    @Override
//    public void writeActive() {
//        IGNORE_WRITE.writeEventHandler(selectionKey, socketChannel, eventLoop);
//    }
//
//
//    @Override
//    public void connectActive() {
//        IGNORE_CONNECT.connectEventHandler(selectionKey, socketChannel, eventLoop);
//    }
//
//
//    @Override
//    public void readActive() {
//        IGNORE_READ.readEventHandler(selectionKey, socketChannel, eventLoop);
//    }
//
//
//    @Override
//    public void closeActive() {
//        CLOSE_NOW.closeEventHandler(selectionKey, socketChannel, eventLoop);
//    }
}
