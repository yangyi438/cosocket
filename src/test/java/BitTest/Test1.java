package BitTest;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import yy.code.io.cosocket.status.BitIntStatusUtils;
import yy.code.io.cosocket.status.CoSocketStatus;

/**
 * Created by ${good-yy} on 2018/10/29.
 */
public class Test1 {
    public static void main(String[] args) {
        try {
            Strand.park();
        } catch (SuspendExecution suspendExecution) {
            suspendExecution.printStackTrace();
        }
         System.out.println(123);

    }
}
