package ru.yeti.diag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/** Compares OBD request spacing once per analysis, even if Bluetooth reconnects. */
final class PollingBenchmark {
    private static final int ROUNDS=3;
    private static final int MAX_PARAMETERS=6;
    private static final int[] PAUSES={DiagnosticSession.LIVE_REQUEST_PAUSE_MS,20,0};
    private final List<Trial> trials=new ArrayList<>();
    private boolean started;
    private String failure;
    private String parameterCodes="";

    void run(DiagnosticSession session,ObdReader obd)throws IOException{
        if(!session.isCurrent())return;
        if(!started){
            started=true;
            try{
                session.elm.init();
                obd.readSupportedPids();
                if(!session.isCurrent())return;
                List<Protocol.Pid> parameters=selectParameters(obd);
                if(parameters.size()<2)throw new IOException("Для сравнения нужны хотя бы два параметра OBD");
                StringJoiner codes=new StringJoiner(", ");
                for(Protocol.Pid pid:parameters)codes.add(String.format(Locale.ROOT,"%02X",pid.id));
                parameterCodes=codes.toString();
                for(int pause:PAUSES){
                    if(!session.isCurrent())return;
                    Trial trial=new Trial(pause,parameters.size()*ROUNDS);
                    trials.add(trial);
                    try{measure(session,obd,parameters,trial);}
                    finally{
                        session.log.record("polling_benchmark","pause_"+pause,describe(trial),
                            TimeUnit.NANOSECONDS.toMillis(trial.elapsed));
                    }
                }
            }catch(IOException error){
                failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                report(session);
                if(session.wireLost())throw error;
                return;
            }
        }
        report(session);
    }

    private static List<Protocol.Pid> selectParameters(ObdReader obd){
        List<Protocol.Pid> parameters=new ArrayList<>();
        for(Protocol.Pid pid:Protocol.PIDS){
            if(obd.supports(pid))parameters.add(pid);
            if(parameters.size()==MAX_PARAMETERS)break;
        }
        return parameters;
    }

    private static void measure(DiagnosticSession session,ObdReader obd,List<Protocol.Pid> parameters,
                                Trial trial)throws IOException{
        long start=System.nanoTime();
        try{
            for(int round=0;round<ROUNDS;round++){
                for(Protocol.Pid pid:parameters){
                    if(!session.isCurrent())return;
                    trial.attempted++;
                    try{
                        byte[] response=obd.readStandardPid(pid);
                        if(response==null||response.length<pid.size)trial.missing++;
                    }catch(Elm.ResponseTimeout error){
                        trial.timeouts++;
                        throw error;
                    }catch(IOException error){
                        trial.transportErrors++;
                        throw error;
                    }
                    if(trial.pause>0)DiagnosticSession.pause(trial.pause);
                }
            }
        }finally{
            trial.elapsed=System.nanoTime()-start;
        }
    }

    private String describe(Trial trial){
        return "Запросы: "+trial.attempted+"/"+trial.planned+" · пропуски: "+trial.missing
            +" · тайм-ауты: "+trial.timeouts+" · ошибки связи: "+trial.transportErrors
            +" · PID: "+parameterCodes;
    }

    private void report(DiagnosticSession session){
        if(!session.isCurrent())return;
        Trial best=bestTrial();
        for(Trial trial:trials){
            boolean complete=trial.attempted==trial.planned&&trial.timeouts==0&&trial.transportErrors==0;
            String result=complete?TimeUnit.NANOSECONDS.toMillis(trial.elapsed)/ROUNDS+" мс/круг":"Прерван";
            session.put("polling_pause_"+trial.pause,"Пауза "+trial.pause+" мс","Анализ",result,"",describe(trial));
        }
        String result=failure!=null?"Сравнение прервано":best==null?"Нет варианта без пропусков":best.pause+" мс";
        String note=failure!=null?failure:"Самый быстрый вариант без пропусков в этом тесте OBD. Рабочая пауза не меняется.";
        session.put("polling_pause_result","Сравнение пауз OBD","Анализ",result,"",note);
    }

    /** A positive result means at least one complete trial had no missing answers. */
    boolean confirmed(){return failure==null&&bestTrial()!=null;}

    private Trial bestTrial(){
        Trial best=null;
        for(Trial trial:trials){
            boolean complete=trial.attempted==trial.planned&&trial.timeouts==0&&trial.transportErrors==0;
            if(complete&&trial.missing==0&&(best==null||trial.elapsed<best.elapsed))best=trial;
        }
        return best;
    }

    private static final class Trial {
        final int pause,planned;
        int attempted,missing,timeouts,transportErrors;
        long elapsed;

        Trial(int pause,int planned){
            this.pause=pause;
            this.planned=planned;
        }
    }
}
