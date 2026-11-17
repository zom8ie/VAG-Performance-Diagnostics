package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class PollingReadTest {
    private static final class Samples extends DiagnosticBlocksTest.Snapshot {
        final List<String> events=new ArrayList<>();
        final List<String> details=new ArrayList<>();
        @Override public void status(String message,boolean ready){}
        @Override public void record(String kind,String command,String detail,long elapsed){events.add(kind);details.add(detail);}
    }
    private static final class ObdWire implements Elm.Wire {
        final List<String> commands=new ArrayList<>();
        String fast="7E8 04 41 0C 0C 80\r>",normal="7E8 04 41 0C 0B B8\r>";
        public String exchange(String command,int timeout){
            commands.add(command);
            if(command.equals("ATDPN"))return "6\r>";
            if(command.startsWith("AT"))return "OK\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 10 00 00\r>";
            if(command.equals("010C"))return normal;
            if(command.equals("010C1"))return fast;
            throw new AssertionError("Unexpected OBD command "+command);
        }
    }
    private static ObdReader reader(ObdWire wire,Samples samples)throws IOException{
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->false,m->true);
        session.mode="OBD";
        ObdReader reader=new ObdReader(session,new BlockInfoReader(session));reader.filter="Основные";
        reader.readSupportedPids();return reader;
    }
    static void run()throws Exception{
        ObdWire fast=new ObdWire();Samples samples=new Samples();ObdReader reader=reader(fast,samples);
        reader.poll();reader.poll();
        check(Collections.frequency(fast.commands,"010C")==1&&Collections.frequency(fast.commands,"010C1")==1,
            "single-response optimization starts only after a complete baseline frame");
        check(samples.rows.get("pid12").value.equals("800"),"fast polling publishes the complete new sample");
        reader.readSupportedPids();reader.poll();
        check(Collections.frequency(fast.commands,"010C")==2,"reconnection requires a new baseline before fast polling");
        for(String failure:Arrays.asList("?\r>","NO DATA\r>","7E8 03 41 0C 0B\r>","7E8 03 41 05 82\r>")){
            ObdWire wire=new ObdWire();wire.fast=failure;Samples fallback=new Samples();ObdReader slow=reader(wire,fallback);
            slow.poll();slow.poll();slow.poll();
            check(Collections.frequency(wire.commands,"010C1")==1&&Collections.frequency(wire.commands,"010C")==3,
                "invalid fast response falls back once, then keeps ordinary polling");
            check(fallback.rows.get("pid12").value.equals("750")&&fallback.events.contains("obd_fast_fallback"),
                "fallback never publishes missing, partial or mismatched data");
        }
        ObdWire empty=new ObdWire();empty.normal="NO DATA\r>";Samples missing=new Samples();ObdReader paced=reader(empty,missing);
        paced.poll();paced.poll();
        check(Collections.frequency(empty.commands,"010C")==1&&!empty.commands.contains("010C1"),
            "missing parameter backs off and never enables single-frame polling");
        check(missing.rows.get("pid12").value.equals("—")&&missing.events.contains("measurement_unavailable"),"missing data is distinct from a zero reading");
        checkDisabledPowerStaysVisible();
        checkFastSurvivesTransitionGap();
        checkSkipsIdleObdSetup();
        checkObdRecoveryBenchmark();

        ObdWire probe=new ObdWire();Samples verified=new Samples();reader(probe,verified).probeFastPolling();
        check(verified.events.contains("obd_fast_verified"),"analysis confirms a complete accelerated reply");
        reader.prepareForPolling();
        check(fast.commands.containsAll(Arrays.asList("ATAT1","ATST64","ATV0")),"OBD restores adaptive timing and fixed CAN length after TP2.0");

        LoggedReadingsTest.GroupWire channel=new LoggedReadingsTest.GroupWire();
        channel.replies.put("2173","617301C81321FF1112C86412C864");
        Samples cycle=new Samples();DiagnosticSession session=new DiagnosticSession(new Elm(channel,cycle),cycle,cycle,()->false,()->true,m->true);
        session.mode=Engine.COMBINED_ENGINE;
        BlockInfoReader info=new BlockInfoReader(session);VagReader vag=new VagReader(session,info);
        vag.initialize();check(vag.readGroup(115),"first expanded engine read succeeds");
        vag.close();new ObdReader(session,info).prepareForPolling();
        check(vag.hasMeasurements()&&!vag.hasChannel(),"closing for OBD keeps the measurement plan");
        vag.open();check(vag.readGroup(115),"expanded engine reading reopens a fresh channel after OBD");
        check(Collections.frequency(channel.commands,"01C00010000301")==2
            &&channel.commands.indexOf("A8")<channel.commands.indexOf("ATSP6"),"channel closes before changing protocol, without a sacrificial failed request");
        vag.close();
        checkPausedGroupPlan();
        checkCombinedKeepsObdAfterFailedChannelSetup();
        checkSlowStatusOutsideFastLoop();
    }
    private static void checkDisabledPowerStaysVisible()throws IOException{
        ObdWire wire=new ObdWire();Samples samples=new Samples();
        ParameterSelection selection=new ParameterSelection(){
            public boolean isEnabled(String mode,String key){return !key.equals("maf_power");}
            public void setEnabled(String mode,String key,boolean enabled){}
        };
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->false,m->true,selection);
        session.mode="OBD";
        ObdReader reader=new ObdReader(session,new BlockInfoReader(session));reader.filter="Основные";
        reader.readSupportedPids();reader.poll();
        check(samples.rows.containsKey("maf_power"),
            "disabled power remains available for reenabling");
    }
    private static void checkFastSurvivesTransitionGap()throws IOException{
        List<String> commands=new ArrayList<>();
        String[] reply={"ok"};boolean[] analyzing={false};
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATDPN"))return "6\r>";
            if(command.startsWith("AT"))return "OK\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 10 00 00\r>";
            if(command.equals("010C")||command.equals("010C1")){
                if(reply[0].equals("gap"))return "NO DATA\r>";
                return command.equals("010C1")?"7E8 04 41 0C 0C 80\r>":"7E8 04 41 0C 0B B8\r>";
            }
            throw new AssertionError(command);
        };
        Samples samples=new Samples();
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->analyzing[0],m->true);
        session.mode="OBD";
        ObdReader reader=new ObdReader(session,new BlockInfoReader(session));reader.filter="Основные";
        reader.readSupportedPids();
        reader.poll();reader.poll();
        check(commands.contains("010C1"),"fast polling armed after a complete baseline");
        reply[0]="gap";
        reader.poll();
        check(!samples.events.contains("obd_fast_fallback"),"a post-TP2.0 gap where both forms fail does not blame the clone");
        check(samples.events.contains("measurement_unavailable"),"the gap backs the PID off instead of disabling fast polling");
        check(Collections.frequency(commands,"010C1")==2&&Collections.frequency(commands,"010C")==2,
            "the gap retries the normal form once before backing off");
        reply[0]="ok";analyzing[0]=true;
        reader.poll();
        check(Collections.frequency(commands,"010C1")==3,"fast polling stays armed after the gap recovers");
    }
    private static void checkSkipsIdleObdSetup()throws IOException{
        ObdWire wire=new ObdWire();wire.normal="NO DATA\r>";wire.fast="NO DATA\r>";
        Samples samples=new Samples();
        ParameterSelection selection=new ParameterSelection(){
            public boolean isEnabled(String mode,String key){return key.equals("pid12");}
            public void setEnabled(String mode,String key,boolean enabled){}
        };
        boolean[] analyzing={false};
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->analyzing[0],m->true,selection);
        session.mode="OBD";
        ObdReader reader=new ObdReader(session,new BlockInfoReader(session));reader.filter="Основные";
        reader.readSupportedPids();
        check(reader.hasPendingPoll(),"a ready PID needs a poll");
        reader.poll();
        check(!reader.hasPendingPoll(),"a backing-off PID with no other source skips the idle OBD setup");
        analyzing[0]=true;
        check(reader.hasPendingPoll(),"analysis always polls");
    }
    private static void checkObdRecoveryBenchmark()throws IOException{
        for(boolean delayed:new boolean[]{false,true}){
            LoggedReadingsTest.GroupWire tp=new LoggedReadingsTest.GroupWire();
            tp.replies.put("2173","617301C81321FF1112C86412C864");
            List<String> commands=new ArrayList<>();
            int[] lambdaCalls={0};
            Elm.Wire wire=(command,timeout)->{
                commands.add(command);
                if(command.equals("0110"))return "7E8 04 41 10 01 F4\r>";
                if(command.equals("0134")){
                    lambdaCalls[0]++;
                    if(delayed&&lambdaCalls[0]==1)return "7E8 04 41 34 80 00\r>";
                    return "7E8 06 41 34 80 00 80 00\r>";
                }
                return tp.exchange(command,timeout);
            };
            Samples samples=new Samples();
            DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->true,m->true);
            session.mode="ECM";
            BlockInfoReader info=new BlockInfoReader(session);
            VagReader vag=new VagReader(session,info);
            try{
                new ObdRecoveryBenchmark(session,vag,new ObdReader(session,info)).run();
                check(!delayed,"the check cannot confirm a partial first pass through both inputs");
            }catch(IOException e){
                if(!delayed)throw e;
                check(e.getMessage().contains("только при повторе"),"delayed recovery remains pending");
            }finally{vag.close();}
            check(commands.indexOf("A8")<commands.indexOf("ATV0")&&commands.indexOf("ATV0")<commands.indexOf("0110")
                &&!commands.contains("010C"),"the recovery compares actual affected inputs after restoring CAN length");
            check(lambdaCalls[0]==(delayed?2:1)&&samples.details.stream()
                .anyMatch(detail->detail.startsWith(delayed?"direct_delayed;":"direct_first;")),
                "the recovery log distinguishes a complete first pass from late success");
        }
    }
    private static void checkSlowStatusOutsideFastLoop()throws IOException{
        Samples sample=new Samples();
        List<String> commands=new ArrayList<>();
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            switch(command){
                case "ATDPN":return "6\r>";
                case "0100":return "7E8 06 41 00 A0 10 20 11\r>";
                case "0120":return "7E8 06 41 20 00 00 00 01\r>";
                case "0140":return "7E8 06 41 40 80 00 00 00\r>";
                case "010C":case "010C1":return "7E8 04 41 0C 0B B8\r>";
                case "0103":return "7E8 04 41 03 01 00\r>";
                case "0113":return "7E8 03 41 13 01\r>";
                case "011C":return "7E8 03 41 1C 06\r>";
                case "0141":return "7E8 06 41 41 00 00 00 00\r>";
                default:if(command.startsWith("AT"))return "OK\r>";throw new AssertionError(command);
            }
        };
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,sample),sample,sample,()->false,()->false,m->true);
        session.mode="OBD";
        ObdReader reader=new ObdReader(session,new BlockInfoReader(session));
        reader.readSupportedPids();
        reader.poll();reader.poll();
        check(commands.contains("010C1")&&Arrays.asList("0103","0113","011C","0141").stream()
            .allMatch(command->Collections.frequency(commands,command)==1),
            "steady OBD polling keeps updating RPM without rereading extra status on every pass");
        reader.probeExtra();
        check(Collections.frequency(commands,"011C")==2,"explicit analysis still requests status immediately");
    }
    private static void checkCombinedKeepsObdAfterFailedChannelSetup(){
        List<String> commands=new ArrayList<>();
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATDPN"))return "6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 10 00 00\r>";
            if(command.equals("010C")||command.equals("010C1"))return "7E8 04 41 0C 0B B8\r>";
            if(command.startsWith("22")||command.startsWith("01C00010000301"))return "NO DATA\r>";
            if(command.startsWith("AT"))return "OK\r>";
            throw new AssertionError(command);
        };
        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot(){
            int samples;
            @Override public void status(String message,boolean ready){}
            @Override public void row(Engine.Row row){
                super.row(row);
                if(row.key.equals("pid12")&&row.value.equals("750")&&++samples==2)engine.stopped=true;
            }
        };
        state.engine=new Engine(new Elm(wire,state),state,state);
        state.engine.choose(Engine.COMBINED_ENGINE);
        state.engine.run();
        int firstOpen=commands.indexOf("01C00010000301");
        check(state.failure==null&&firstOpen>commands.indexOf("010C")&&commands.indexOf("010C1")>firstOpen,
            "failed expanded channel setup leaves standard engine readings running on the same Bluetooth connection");
        check(Collections.frequency(commands,"ATZ")==1&&Collections.frequency(commands,"01C00010000301")==1,
            "a refused expanded channel is not opened again during the next OBD pass");
    }
    private static void checkPausedGroupPlan()throws IOException{
        LoggedReadingsTest.GroupWire wire=new LoggedReadingsTest.GroupWire();
        wire.replies.put("2173","617301C81321FF1112C86412C864");wire.replies.put("1A9B","7F1A11");
        Samples samples=new Samples();boolean[] enabled={false};
        ParameterSelection selection=new ParameterSelection(){
            public boolean isEnabled(String mode,String key){return enabled[0]&&key.startsWith("g115f");}
            public void setEnabled(String mode,String key,boolean value){enabled[0]=value;}
        };
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->false,m->true,selection);
        session.mode="ECM";VagReader reader=new VagReader(session,new BlockInfoReader(session));reader.initialize();
        reader.poll();int commands=wire.commands.size();reader.poll();
        check(!reader.hasChannel()&&reader.hasMeasurements()&&wire.commands.size()==commands,
            "disabled groups close the idle channel without repeatedly reopening it");
        enabled[0]=true;reader.poll();
        check(reader.hasChannel()&&samples.rows.containsKey("g115f2"),"reenabling a group opens a fresh channel and resumes its preserved plan");
        reader.close();
    }
}
