package yy.code.io.cosocket;

import io.netty.channel.nio.CoSocketEventLoop;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public interface CloseEventHandler {

    public void closeEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop);


}
