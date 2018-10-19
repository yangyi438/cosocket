package yy.code.io.cosocket;

import com.sun.xml.internal.bind.v2.model.core.ID;
import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

/**
 * Created by ${good-yy} on 2018/10/18.
 */
public interface RegisterHandler {

    InternalLogger LOGGER = InternalLoggerFactory.getInstance(RegisterHandler.class);

    //成功的时候的回调函数
    public void success(SelectionKey selectionKey, CoSocketChannel channel, CoSocketEventLoop eventLoop);

    //失败的时候回调函数
    public void error(IOException exception, CoSocketChannel channel, CoSocketEventLoop eventLoop);

    public RegisterHandler CONNECTION_HANDLER = new RegisterHandler() {
        @Override
        public void success(SelectionKey selectionKey, final CoSocketChannel coChannel, final CoSocketEventLoop eventLoop) {
            coChannel.selectionKey = selectionKey;
            int connectionMilliseconds = coChannel.getConfig().getConnectionMilliSeconds();
            coChannel.connectTimeoutFuture = eventLoop.schedule(new Runnable() {
                @Override
                public void run() {
                    coChannel.close();
                    //回调  errorConnect函数
                    CoSocket innerCoSocket = coChannel.innerCoSocket;
                    //代表已经释放过资源了
                    innerCoSocket.isRelease = true;
                    innerCoSocket.errorConnect(new SocketTimeoutException());
                }
            }, connectionMilliseconds, TimeUnit.MILLISECONDS);
        }

        @Override
        public void error(IOException exception, CoSocketChannel channel, CoSocketEventLoop eventLoop) {
            if (channel.selectionKey != null) {
                eventLoop.cancel(channel.selectionKey);
            }
            channel.close();
            CoSocket innerCoSocket = channel.innerCoSocket;
            //因为我们处于连接状态的channel没有啥资源,直接赋值为true,代表已经释放了资源
            innerCoSocket.isRelease = true;
            innerCoSocket.errorConnect(exception);
        }
    };
}
