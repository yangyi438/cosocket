package yy.code.io.cosocket.status;

import java.nio.channels.SelectionKey;

/**
 * Created by ${good-yy} on 2018/11/3.
 */
public final class SelectionKeyUtils {

    public static void listenOps(SelectionKey selectionKey, int ops) {
        int interestOps = selectionKey.interestOps();
        if ((interestOps & ops) == 0) {
            selectionKey.interestOps(interestOps | SelectionKey.OP_READ);
        }
    }

    public static void removeOps(SelectionKey selectionKey, int ops) {
        int interestOps = selectionKey.interestOps();
        if ((interestOps & ops) != 0) {
            selectionKey.interestOps(interestOps & ~ops
            );
        }
    }

    public static boolean isInStatus(SelectionKey selectionKey, int ops) {
        int interestOps = selectionKey.interestOps();
        return (interestOps & ops) != 0;
    }

}
