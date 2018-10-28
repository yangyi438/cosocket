package yy.code.io.cosocket.fiber;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.strands.Strand;

/**
 * Created by ${good-yy} on 2018/8/31.
 * //提供线程或者协程的suspend 或者 beContinue
 */
public class StrandSuspendContinueSupport {

    private Strand strand;

    public void prepareSuspend(){
        this.strand = Strand.currentStrand();
    }
    public void clean() {
        this.strand = null;
    }

    @Suspendable
    public void suspend() {
        try {
            Strand.park();
        } catch (SuspendExecution suspendExecution) {
            throw new Error("quasar err or  you not right use the quasar  goto http://docs.paralleluniverse.co/quasar/ with detail");
        }
    }




    public void beContinue() {
        Strand strand = this.strand;
        this.strand = null;
        if (strand != null ) {
            strand.unpark();
        }
    }

}
