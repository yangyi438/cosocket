package yy.code.io.cosocket;

/**
 * Created by ${good-yy} on 2018/11/2.
 */
public interface EventHandler {

    //处理写事件
    void handlerWriteActive();

    //处理连接事件
    void handlerConnectActive();

    //处理读事件
    void handlerReadActive();

    //关闭事件循环
    void close();

}
