package yy.code.io.cosocket;

import io.netty.channel.nio.CoSocketEventLoop;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public abstract class AbstractNioChannelEventHandler implements EventHandler {

    protected SelectionKey selectionKey;
    protected SocketChannel socketChannel;
    protected CoSocketEventLoop eventLoop;

    public AbstractNioChannelEventHandler(SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        this.selectionKey = selectionKey;
        this.socketChannel = socketChannel;
        this.eventLoop = eventLoop;
    }


    @Override
    public abstract void handlerWriteActive();

    @Override
    public abstract void handlerConnectActive();

    @Override
    public abstract void handlerReadActive();

    @Override
    public abstract void close();

}
