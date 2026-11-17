package ru.yeti.diag;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Checks the actual OBD inputs that disappeared from the mixed dashboard. */
final class ObdRecoveryBenchmark {
    private static final int PROBE_PAUSE_MS=500;
    private static final int MAX_ATTEMPTS=7;
    private static final Protocol.Pid AIRFLOW=ObdReader.pid(0x10);
    private static final Protocol.Pid LAMBDA=ObdReader.pid(0x34);
    private final DiagnosticSession session;
    private final VagReader vag;
    private final ObdReader obd;

    ObdRecoveryBenchmark(DiagnosticSession session,VagReader vag,ObdReader obd){
        this.session=session;this.vag=vag;this.obd=obd;
    }

    void run()throws IOException{
        session.elm.init();
        vag.open();
        if(!vag.readGroup(115))throw new IOException("Канал TP2.0 не вернул измерение для проверки восстановления");
        vag.close();
        if(!session.isCurrent())return;
        long start=System.nanoTime();
        obd.prepareForPolling();
        for(int round=1;round<=MAX_ATTEMPTS&&session.isCurrent();round++){
            if(readPair("direct",round,start)){
                long elapsed=since(start);
                publish(round==1?"direct_first":"direct_delayed",round,elapsed);
                if(round==1)return;
                throw new IOException("Оба параметра ответили только при повторе; обычный переход ещё не подтверждён");
            }
            if(round<MAX_ATTEMPTS&&session.isCurrent())DiagnosticSession.pause(PROBE_PAUSE_MS);
        }
        if(!session.isCurrent())return;
        // Control: a positive response after clean OBD setup does not prove the fast transition.
        long directElapsed=since(start);
        session.elm.init();
        obd.readSupportedPids();
        if(!session.isCurrent())return;
        boolean control=readPair("standalone",1,start);
        if(!session.isCurrent())return;
        publish(control?"standalone_only":"no_complete_pair",MAX_ATTEMPTS,directElapsed);
        throw new IOException(control?"После отдельной настройки OBD оба параметра ответили; быстрый переход не подтверждён"
            :"Расход воздуха и лямбда не вернули полную пару даже в отдельном OBD");
    }

    private boolean readPair(String path,int round,long start)throws IOException{
        if(!session.isCurrent())return false;
        boolean airflow=read(AIRFLOW,path,round,start);
        if(!session.isCurrent())return false;
        DiagnosticSession.pause(DiagnosticSession.OBD_REQUEST_PAUSE_MS);
        if(!session.isCurrent())return false;
        boolean lambda=read(LAMBDA,path,round,start);
        return session.isCurrent()&&airflow&&lambda;
    }

    private boolean read(Protocol.Pid pid,String path,int round,long start)throws IOException{
        long sent=since(start);
        byte[] sample=obd.readStandardPid(pid);
        boolean complete=sample!=null&&pid.completeLength(sample.length);
        session.log.record("obd_recovery_sample",ObdReader.pidRequest(pid),
            path+"; round="+round+"; sent_after_close_ms="+sent+"; complete="+complete,since(start)-sent);
        return complete;
    }

    private void publish(String result,int round,long elapsed){
        session.log.record("obd_recovery_benchmark","after_A8",result+"; rounds="+round,elapsed);
        String note;
        switch(result){
            case "direct_first":note="Оба параметра ответили с первого раза.";break;
            case "direct_delayed":note="Оба параметра ответили только при повторе.";break;
            case "standalone_only":note="Оба параметра ответили только после отдельной настройки OBD.";break;
            default:note="Нет полной пары даже после отдельной настройки OBD.";
        }
        session.put("obd_tp20_recovery","Восстановление OBD после TP2.0","Анализ",
            Long.toString(elapsed),"мс","Расход воздуха 0110 и лямбда 0134. "+note);
    }

    private static long since(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
}
