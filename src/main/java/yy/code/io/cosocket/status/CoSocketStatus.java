package yy.code.io.cosocket.status;

/**
 * Created by ${good-yy} on 2018/10/8.
 */
public final class CoSocketStatus {

    public static final int RUNNING = 1 << 1;

    //等待连接成功
    public static final int PARK_FOR_CONNECT = 1 << 2;

    //等待读事件
    public static final int PARK_FOR_READ = 1 << 3;

    //等待可写事件的发生
    public static final int PARK_FOR_WRITE = 1 << 4;

    //发生了异常的标志位
    public static final int EXCEPTION = 1 << 6;

    //读已经结束了,直接发生了eof的事件
    public static final int EOF = 1 << 7;

    //正常关闭了连接
    public static final int CLOSE = 1 << 8;

    //连接成功了
    public static final int CONNECT_SUCCESS = 1 << 9;

}
