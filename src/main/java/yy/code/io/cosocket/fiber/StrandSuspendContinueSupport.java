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


    @Suspendable
    public void suspend() {
        try {
            this.strand = Strand.currentStrand();
            Strand.park();
        } catch (SuspendExecution suspendExecution) {
            throw new Error("quasar err or  you not right use the quasar  goto http://docs.paralleluniverse.co/quasar/ with detail");
        }
    }

    //调用着要保证业务线程已经suspend了,或者快要suspend了(快要有特殊含义,我们是修改通用的status状态为waiting状态在 暂停的 中间有一丁点的间隔,这个状态可能被io线程捕获到)
    @Suspendable
    public void beContinue() {
        Strand strand = this.strand;
        this.strand = null;
        //cas成功之后进去这个方法,有可能,业务线程没有来得及调用suspend方法,避免这种这样
        //  see yy.code.httpserver.servlet.NettyInputStream sleepForRead
        strand.unpark();
    }

}
