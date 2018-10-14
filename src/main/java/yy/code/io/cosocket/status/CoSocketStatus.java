package yy.code.io.cosocket.status;

/**
 * Created by ${good-yy} on 2018/10/8.
 */
public final class CoSocketStatus {

    //netty线程开启了autoRead
    //public static final int AUTO_READING = 1 << 1;

    //netty线程关闭了自动读
    public static final int CLOSE_AUTO_READING = 1 << 2;

    //eof事件,对面的socket关闭outStream
    public static final int EOF = 1 << 3;

    //netty里面的channel已经关闭了,但是没有发生异常,只是正常的关闭了
    public static final int CLOSED = 1 << 4;

    //由于异常关闭了连接
    public static final int CLOSED_BY_EXCEPTION = 1 << 5;

    //我们的使用者线程(协程)因为等待读取数据而挂起了
    public static final int SOCKET_WAITING_FOR_READ = 1 << 6;

    //我们的使用者线程(协程)等待将数据写出去而阻塞 注意通过CoSocket写出去的数据,是先写到netty的io线程里面去,
    //然后在由netty的线程写出去,所以io线程中间会有一个buffer的大小的数据,如果数据超过最大的大小的数据,那么我们就会挂起当前使用socket的线程(协程)
    //但是我们要注意的是,通过coSocket写数据到netty的线程中的时候,本身也会有一个buffer的数据,然后调用flushBuffer的api来刷新数据到io线程里面去
    public static final int SOCKET_WAITING_FOR_WRITE = 1 << 7;
}
