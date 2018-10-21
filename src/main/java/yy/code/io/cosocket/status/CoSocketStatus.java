package yy.code.io.cosocket.status;

/**
 * Created by ${good-yy} on 2018/10/8.
 */
public final class CoSocketStatus {

    //使用CoSocket的线程正在运行
    public static final int RUNNING = 1 << 1;

    //等待连接成功
    public static final int PARK_FOR_CONNECT = 1 << 2;

    //等待读事件
    public static final int PARK_FOR_READ = 1 << 3;

    //等待可写事件的发生
    public static final int PARK_FOR_WRITE = 1 << 4;

    //发生了异常的标志位,区分异常的类型,一种是连接异常,一种是读的时候发生了异常,一种是,写的时候发生的异常
    //并不一刀切,区分对待各种异常

    //连接异常,超时,或者
    public static final int CONNECT_EXCEPTION = 1 << 6;

    //从chanel里面读的时候发生的异常
    public static final int READ_EXCEPTION = 1 << 7;

    public static final int WRITE_EXCEPTION = 1 << 8;

    //读已经结束了,直接发生了eof的事件
    public static final int EOF = 1 << 9;

    //正常关闭了连接
    public static final int CLOSE = 1 << 10;

    //连接成功了
    public static final int CONNECT_SUCCESS = 1 << 11;

}
