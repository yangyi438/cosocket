package yy.code.io.cosocket.client;

import java.io.IOException;

/**
 * Created by ${good-yy} on 2018/10/14.
 */
public abstract class CoSocket {

    public abstract void successConnect();

    public abstract void errorConnect(IOException e);

    public abstract boolean handlerReadActive();

    public abstract boolean handlerWriteActive();

}
