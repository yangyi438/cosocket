package yy.code.io.cosocket;

import io.netty.channel.nio.CoSocketEventLoop;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public interface ReadEventHandler {

    public void readcEventHandler(SelectionKey selectionKey, SocketChannel channel, CoSocketEventLoop eventLoop);

}
