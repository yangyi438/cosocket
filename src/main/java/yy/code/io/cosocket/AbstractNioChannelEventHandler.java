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

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public CoSocketEventLoop getEventLoop() {
        return eventLoop;
    }

    public AbstractNioChannelEventHandler(SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        this.selectionKey = selectionKey;
        this.socketChannel = socketChannel;
        this.eventLoop = eventLoop;
    }

    protected abstract WriteEventHandler getWriteHandler();

    protected abstract ConnectEventHandler getConnectHandler();

    protected abstract ReadEventHandler getReadHandler();

    protected abstract CloseEventHandler getCloseHandler();

    @Override
    public void writeActive() {
        WriteEventHandler writeEventHandler = getWriteHandler();
        writeEventHandler.writeEventHandler(selectionKey, socketChannel, eventLoop);
    }


    @Override
    public void connectActive() {
        ConnectEventHandler connectEventHandler = getConnectHandler();
        connectEventHandler.connectEventHandler(selectionKey, socketChannel, eventLoop);
    }


    @Override
    public  void readActive(){
        ReadEventHandler readEventHandler = getReadHandler();
        readEventHandler.readEventHandler(selectionKey, socketChannel,eventLoop);
    }



    @Override
    public  void close(){
        CloseEventHandler closeEventHandler = getCloseHandler();
        closeEventHandler.closeEventHandler(selectionKey, socketChannel, eventLoop);
    }



}
