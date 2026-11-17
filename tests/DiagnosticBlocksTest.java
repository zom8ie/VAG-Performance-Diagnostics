package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class DiagnosticBlocksTest {
    static class Snapshot implements Engine.State,Engine.Log{
        Engine engine;
        String failure;
        final Map<String,Engine.Row> rows=new HashMap<>();
        public void status(String message,boolean ready){if(ready)engine.stopped=true;}
        public void row(Engine.Row row){rows.put(row.key,row);}
        public void clear(){rows.clear();}
        public void failure(String message){failure=message;}
        public void record(String kind,String command,String detail,long duration){}
        public void value(String mode,String key,String name,String value,String unit,String status){}
        void runAbs(Elm.Wire wire){
            runBlock(wire,"ABS");
        }
        void runBlock(Elm.Wire wire,String mode){
            engine=new Engine(new Elm(wire,this),this,this);
            engine.choose(mode);
            engine.run();
        }
    }
    private static void checkConfigurationSetupReuse(){
        Set<String> pending=new HashSet<>(Arrays.asList("coding_OBD","dataset_OBD","coding_DSG","dataset_DSG"));
        List<String> commands=new ArrayList<>(), reused=new ArrayList<>(), fallbacks=new ArrayList<>();
        Snapshot snapshot=new Snapshot(){
            @Override public void record(String kind,String command,String detail,long duration){
                if(kind.equals("adapter_reuse"))reused.add(detail);
                if(kind.equals("adapter_reuse_fallback"))fallbacks.add(detail);
                if(kind.equals("analysis_result")&&detail.startsWith("dataset_DSG "))engine.stopped=true;
            }
            @Override public void status(String message,boolean ready){
                super.status(message,ready);
                if(message.equals("Анализ · сравнение пауз OBD"))engine.stopped=true;
            }
        };
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATSH7E1")&&Collections.frequency(commands,command)==1)return "?\r>";
            if(command.startsWith("AT"))return "OK\r>";
            return "NO DATA\r>";
        };
        snapshot.engine=new Engine(new Elm(wire,snapshot),snapshot,snapshot,new Engine.PartialProgress(){
            public boolean isVerified(String key){return !pending.contains(key);}
            public void markVerified(String key){pending.remove(key);}
        });
        snapshot.engine.requestPartialAnalysis();snapshot.engine.run();
        check(snapshot.failure==null&&reused.equals(Arrays.asList("dataset_OBD","coding_DSG","dataset_DSG")),"adjacent UDS checks reuse adapter setup after a complete NO DATA response");
        check(fallbacks.equals(Collections.singletonList("coding_DSG"))&&Collections.frequency(commands,"ATZ")==2
            &&Collections.frequency(commands,"ATSH7E1")==3,"failed adapter command resets the adapter and repeats the affected UDS check once");
        check(commands.contains("ATCRA7E8")&&commands.contains("ATCRA7E9"),"each UDS check still selects its ECU address");
    }
    private static void checkIdentitySetupReuse(){
        Set<String> pending=new HashSet<>(Arrays.asList("identity_v10_OBD","identity_v10_DSG"));
        List<String> commands=new ArrayList<>();
        Snapshot snapshot=new Snapshot();
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            return command.startsWith("AT")?"OK\r>":"NO DATA\r>";
        };
        snapshot.engine=new Engine(new Elm(wire,snapshot),snapshot,snapshot,new Engine.PartialProgress(){
            public boolean isVerified(String key){return !pending.contains(key);}
            public void markVerified(String key){pending.remove(key);}
        });
        snapshot.engine.requestPartialAnalysis();
        snapshot.engine.run();
        check(snapshot.failure==null&&pending.size()==2&&Collections.frequency(commands,"ATZ")==1,
            "identity checks reuse adapter setup and leave unanswered IDs unverified");
        check(commands.contains("ATCRA7E8")&&commands.contains("ATCRA7E9")
            &&Collections.frequency(commands,"22F187")==2,"identity requests target each ECU separately");
    }
    private static void checkObdSetupReuse(boolean failFirstReuse){
        List<String> commands=new ArrayList<>(), events=new ArrayList<>();
        Snapshot snapshot=new Snapshot(){
            @Override public void record(String kind,String command,String detail,long duration){
                if(kind.startsWith("adapter_reuse"))events.add(kind);
                if(kind.equals("analysis_result")&&detail.startsWith("extra_obd_status "))engine.stopped=true;
            }
        };
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 20 00 00 00\r>";
            if(command.equals("0103")){
                if(failFirstReuse&&Collections.frequency(commands,command)==2)return "NO DATA\r>";
                return "7E8 04 41 03 01 00\r>";
            }
            if(command.startsWith("AT"))return "OK\r>";
            return "NO DATA\r>";
        };
        snapshot.engine=new Engine(new Elm(wire,snapshot),snapshot,snapshot);
        snapshot.engine.requestAnalysis();snapshot.engine.run();
        int initializations=failFirstReuse?2:1;
        check(snapshot.failure==null&&Collections.frequency(commands,"ATZ")==initializations
            &&Collections.frequency(commands,"0100")==initializations,"additional OBD check reuses the supported PID mask with a single fallback when needed");
        check(events.equals(failFirstReuse?Arrays.asList("adapter_reuse","adapter_reuse_fallback")
            :Collections.singletonList("adapter_reuse")),"OBD reuse and fallback are visible in the journal");
    }
    private static void checkTp20WaitBenchmark(){
        String key="tp20_ack_wait_comparison";
        Set<String> verified=new HashSet<>();
        Map<String,String> results=new LinkedHashMap<>();
        Snapshot snapshot=new Snapshot(){
            @Override public void record(String kind,String command,String detail,long duration){
                if(kind.equals("tp20_benchmark"))results.put(command,detail);
            }
        };
        LoggedReadingsTest.GroupWire groups=new LoggedReadingsTest.GroupWire();
        groups.replies.put("2173","617301C82E21854712FA7612FA642500001F50002500001F1480");
        groups.replies.put("215D","615D01C81321851651000625000010FF0010FF0010FF0010FF00");
        String[] timeout={"ATST18"};
        Elm.Wire wire=(command,delay)->{
            if(command.startsWith("ATST"))timeout[0]=command;
            if(command.endsWith("2173")||command.endsWith("215D")){
                if(timeout[0].equals("ATST0A"))return "NO DATA\r>";
                if(timeout[0].equals("ATST18"))DiagnosticSession.pause(60);
            }
            return groups.exchange(command,delay);
        };
        snapshot.engine=new Engine(new Elm(wire,snapshot),snapshot,snapshot,new Engine.PartialProgress(){
            public boolean isVerified(String probe){return !probe.equals(key)||verified.contains(probe);}
            public void markVerified(String probe){verified.add(probe);}
        });
        snapshot.engine.requestPartialAnalysis();snapshot.engine.run();
        check(snapshot.failure==null&&verified.contains(key)&&results.get("wait_96").contains("complete=6/6")
            &&results.get("wait_64").contains("complete=6/6"),"partial analysis compares equal complete TP2.0 series");
        check(results.get("wait_40").contains("complete=0/6")&&results.get("recommended").startsWith("64 ms"),
            "the shortest window is rejected when it loses an ECU response");
        results.clear();
        snapshot.engine.stopped=false;
        snapshot.engine.requestPartialAnalysis();snapshot.engine.run();
        check(results.isEmpty(),"a confirmed benchmark is skipped in the next partial analysis");
    }

    static void run() throws Exception {
        checkConfigurationSetupReuse();
        checkIdentitySetupReuse();
        checkObdSetupReuse(false);
        checkObdSetupReuse(true);
        checkTp20WaitBenchmark();
        Replay udsAbs=new Replay().add("22F187","NO DATA\r>").add("22F189","NO DATA\r>").add("22F191","NO DATA\r>")
            .add("22F197","77D 10 08 62 F1 97 4D 4B 31\r77D 21 30 30 00 00 00 00 00\r>")
            .add("22F19E","NO DATA\r>").add("22F1A2","NO DATA\r>").add("1902FF","77D 07 59 02 FF 40 31 1A 08\r>");
        Snapshot mk100=new Snapshot();
        mk100.runAbs(udsAbs);
        check(mk100.failure==null&&udsAbs.steps.isEmpty()&&mk100.rows.get("faults").value.contains("C00311A"),"MK100 snapshot reads identity and faults without checking speed");
        Replay mk60Wire=new Replay();
        for(String did:VehicleData.ID_FIELDS)mk60Wire.add("22"+did,"NO DATA\r>");
        mk60Wire.add("1902FF","NO DATA\r>").add("03C00010000301","203 00 D0 00 03 40 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021A9B","300 B1\r300 20 00 09 5A 9B 4D 4B 36\r300 11 30 45 43 31\r>").add("B2","NO DATA\r>")
            .add("1100041800FF00","300 B2\r300 12 00 02 58 00\r>").add("B3","NO DATA\r>")
            .add("1200022101","300 B3\r300 23 00 0E 61 01 07 64 1E\r300 24 07 64 1E 07 64 1E 07\r300 15 64 1E\r>").add("B6","NO DATA\r>")
            .add("1300022104","300 B4\r300 16 00 03 7F 21 31\r>").add("B7","NO DATA\r>")
            .add("1400022105","300 B5\r300 17 00 03 7F 21 31\r>").add("B8","NO DATA\r>")
            .add("1500022106","300 B6\r300 18 00 03 7F 21 31\r>").add("B9","NO DATA\r>")
            .add("1600022101","300 B7\r300 29 00 0E 61 01 07 64 23\r300 2A 07 64 23 07 64 23 07\r300 1B 64 23\r>").add("BC","NO DATA\r>")
            .add("A8","300 A8\r>");
        Snapshot moving=new Snapshot(){
            @Override public void status(String message,boolean ready){
                if(ready&&engine.requestedMode().equals(Engine.IDLE))engine.stopped=true;
            }
            @Override public void row(Engine.Row row){
                super.row(row);
                if(row.key.equals("g1f3")&&row.value.equals("35"))engine.pauseMeasurements();
            }
        };
        moving.runAbs(mk60Wire);
        check(moving.failure==null&&mk60Wire.steps.isEmpty(),"ABS reads KWP groups in default session");
        check(moving.rows.get("g1f0").value.equals("35")&&moving.rows.get("g1f3").unit.equals("км/ч"),"ABS automatically updates the wheel speed while moving");
        check(moving.engine.activeMode().equals(Engine.IDLE),"Leaving the ABS screen closes the channel and pauses polling");
        check(BlockConfigurationReader.datasetValue(Protocol.bytes("2D2D2020")).isEmpty()&&BlockConfigurationReader.datasetValue(Protocol.bytes("30303231")).equals("0021"),
            "dataset placeholder rejected and version preserved");
        Replay epsWire=new Replay();
        for(String did:VehicleData.ID_FIELDS)
            epsWire.add("22"+did,did.equals("F197")?"77C 06 62 F1 97 45 50 53\r>":"NO DATA\r>");
        epsWire.add("09C00010000301","209 00 D0 00 03 40 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021A9B","300 B1\r300 10 00 03 7F 1A 31\r>").add("B1","NO DATA\r>")
            .add("1100022101","300 B2\r300 21 00 08 61 01 00 00 00\r300 12 5E 64 33\r>").add("B3","NO DATA\r>")
            .add("1200022107","300 B3\r300 13 00 05 61 07 09 64 80\r>").add("B4","NO DATA\r>")
            .add("1300022101","300 B4\r300 24 00 08 61 01 00 00 00\r300 15 5E 64 33\r>").add("B6","NO DATA\r>")
            .add("1400022107","300 B5\r300 16 00 05 61 07 09 64 81\r>").add("B7","NO DATA\r>")
            .add("A8","300 A8\r>");
        Snapshot eps=new Snapshot(){
            private int angleSamples;
            @Override public void status(String message,boolean ready){}
            @Override public void row(Engine.Row row){
                super.row(row);
                if(row.key.equals("g7f0")&&++angleSamples==2)engine.stopped=true;
            }
        };
        eps.runBlock(epsWire,"EPS");
        check(eps.failure==null&&epsWire.steps.isEmpty()&&eps.rows.get("g1f1").value.equals("-770")
            &&!eps.rows.get("g7f0").value.equals("2")&&eps.rows.get("address").value.equals("44"),"EPS repeatedly reads steering angle alongside torque");
        check(eps.rows.get("idF189").value.equals("—")&&eps.rows.get("idF191").value.equals("—"),"missing identity fields are not inferred");
    }
}
