package ru.yeti.diag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static ru.yeti.diag.TestChecks.check;

final class PollingBenchmarkTest {
    static void run()throws Exception{
        checkVerifiedBenchmarkIsSkippedByPartialAnalysis();
        checkPendingBenchmarkIsRunByPartialAnalysis();
        checkInterruptedTrial();
    }

    private static void checkVerifiedBenchmarkIsSkippedByPartialAnalysis()throws Exception{
        List<String> commands=new ArrayList<>();
        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot();
        state.engine=new Engine(new Elm((command,timeout)->{
            commands.add(command);
            throw new AssertionError("Verified partial check sent "+command);
        },state),state,state,new Engine.PartialProgress(){
            public boolean isVerified(String key){return true;}
            public void markVerified(String key){throw new AssertionError(key);}
        });
        state.engine.requestPartialAnalysis();
        state.engine.run();
        check(state.failure==null&&commands.isEmpty()&&state.rows.isEmpty(),
            "partial analysis skips a previously confirmed OBD pause comparison");
    }

    private static void checkPendingBenchmarkIsRunByPartialAnalysis()throws Exception{
        List<String> expected=new ArrayList<>();
        for(int round=0;round<9;round++)expected.addAll(Arrays.asList("010C","010D"));
        List<String> reads=new ArrayList<>();
        List<String> confirmed=new ArrayList<>();
        Elm.Wire wire=(command,timeout)->{
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 18 00 00\r>";
            if(command.equals("010C")||command.equals("010D")){
                reads.add(command);
                if(reads.size()==13)return "NO DATA\r>";
                return command.equals("010C")?"7E8 04 41 0C 0B E0\r>":"7E8 03 41 0D 00\r>";
            }
            if(command.startsWith("AT"))return "OK\r>";
            throw new AssertionError("Unexpected benchmark command "+command);
        };
        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot();
        state.engine=new Engine(new Elm(wire,state),state,state,new Engine.PartialProgress(){
            public boolean isVerified(String key){return !key.equals("polling_pause_comparison");}
            public void markVerified(String key){confirmed.add(key);}
        });
        state.engine.requestPartialAnalysis();
        state.engine.run();
        check(state.failure==null&&reads.equals(expected),
            "partial analysis compares all three pauses when the check has not been confirmed");
        check(confirmed.equals(Arrays.asList("polling_pause_comparison")),
            "a successful pause comparison is remembered and removed from partial analysis");
        check(state.rows.get("OBD/polling_pause_0").note.contains("пропуски: 1")
            &&state.rows.get("OBD/polling_pause_20").note.contains("пропуски: 0")
            &&!state.rows.get("OBD/polling_pause_result").value.equals("0 мс"),
            "a completed trial with missing responses cannot be selected as best");
    }

    private static void checkInterruptedTrial()throws Exception{
        Elm.Wire wire=(command,timeout)->{
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 18 00 00\r>";
            if(command.startsWith("AT"))return "OK\r>";
            throw new Elm.ResponseTimeout();
        };
        PollingBenchmark benchmark=new PollingBenchmark();
        DiagnosticBlocksTest.Snapshot first=new DiagnosticBlocksTest.Snapshot();
        runBenchmark(benchmark,first,wire);
        check(first.rows.get("OBD/polling_pause_40").note.contains("тайм-ауты: 1")
            &&!first.rows.containsKey("OBD/polling_pause_20"),
            "a timeout is counted and aborts the remaining trials");
        DiagnosticBlocksTest.Snapshot restored=new DiagnosticBlocksTest.Snapshot();
        runBenchmark(benchmark,restored,(command,timeout)->{throw new AssertionError(command);});
        check(restored.rows.get("OBD/polling_pause_result").value.equals("Сравнение прервано"),
            "the same analysis can display a cached benchmark after reconnect without sending more test commands");
    }

    private static void runBenchmark(PollingBenchmark benchmark,DiagnosticBlocksTest.Snapshot state,Elm.Wire wire)
        throws Exception{
        Elm elm=new Elm(wire,state);
        DiagnosticSession session=new DiagnosticSession(elm,state,state,()->false,()->true,mode->true);
        session.mode="OBD";
        benchmark.run(session,new ObdReader(session,new BlockInfoReader(session)));
    }
}
