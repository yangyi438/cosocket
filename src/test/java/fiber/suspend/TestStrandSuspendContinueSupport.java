package fiber.suspend;

import co.paralleluniverse.fibers.Fiber;
import yy.code.io.cosocket.fiber.StrandSuspendContinueSupport;

/**
 * Created by ${good-yy} on 2018/11/11.
 */
public class TestStrandSuspendContinueSupport {

    public static void main(String[] args) {
        new Fiber<>(() -> {
            StrandSuspendContinueSupport strandSuspendContinueSupport = new StrandSuspendContinueSupport();
            strandSuspendContinueSupport.prepareSuspend();
            strandSuspendContinueSupport.suspend();
        }).start();
    }


}
