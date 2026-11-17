package ru.yeti.diag;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Compares the adapter's receive window on the same multi-frame reads. */
final class Tp20WaitBenchmark {
    private static final int[] TIMEOUTS_MS={96,64,40};
    private static final int[] GROUPS={115,93,115,93,115,93};
    private final DiagnosticSession session;
    private final VagReader reader;

    Tp20WaitBenchmark(DiagnosticSession session,VagReader reader){
        this.session=session;
        this.reader=reader;
    }

    void run()throws IOException{
        session.elm.init();
        long baseline=Long.MAX_VALUE,best=Long.MAX_VALUE;
        int recommended=0;
        for(int timeout:TIMEOUTS_MS){
            if(!session.isCurrent())return;
            long elapsed=measure(timeout);
            if(!session.isCurrent())return;
            if(timeout==TIMEOUTS_MS[0]){
                if(elapsed==Long.MAX_VALUE)throw new IOException("Базовый опрос TP2.0 неполный");
                baseline=elapsed;
                best=elapsed;
            }else if(elapsed<best){
                best=elapsed;
                recommended=timeout;
            }
        }
        if(recommended==0)throw new IOException("Меньшее время ожидания пока не подтвердило полный ускоренный опрос");
        String summary="Полные ответы "+GROUPS.length+"/"+GROUPS.length+" · "+ms(baseline)+" → "+ms(best)+" мс за серию";
        session.put("tp20_wait_result","Ожидание ответов TP2.0","Анализ",Integer.toString(recommended),"мс",summary);
        session.log.record("tp20_benchmark","recommended",recommended+" ms; "+summary,ms(best));
    }

    private long measure(int timeout)throws IOException{
        int attempted=0,complete=0;
        long started=0,elapsed=0;
        String outcome="complete";
        try{
            reader.open();
            if(timeout==64)session.elm.ok("ATST10");
            if(timeout==40)session.elm.ok("ATST0A");
            started=System.nanoTime();
            for(int group:GROUPS){
                if(!session.isCurrent()){outcome="cancelled";break;}
                attempted++;
                if(reader.readGroup(group))complete++;
                else outcome="incomplete_measurement";
            }
        }catch(IOException error){
            outcome=session.isCurrent()?error.getMessage():"cancelled";
            if(session.wireLost())throw error;
        }finally{
            if(started!=0)elapsed=System.nanoTime()-started;
            session.log.record("tp20_benchmark","wait_"+timeout,
                "complete="+complete+"/"+GROUPS.length+"; attempted="+attempted+"; outcome="+outcome,ms(elapsed));
            reader.close();
        }
        return complete==GROUPS.length?elapsed:Long.MAX_VALUE;
    }

    private static long ms(long nanos){return TimeUnit.NANOSECONDS.toMillis(nanos);}
}
