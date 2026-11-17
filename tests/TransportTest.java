package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class TransportTest {
    static Replay opening(){return new Replay().add("01C00010000301","201 00 D0 00 03 40 07 01\r>")
        .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
        .add("1000021089","300 B1\r300 10 00 02 50 89\r>").add("B1","NO DATA\r>");}
    private static void checkBusyChannelRetry() throws IOException {
        String setup="01C00010000301", busy="201 00 D7 00 00 40 07 01\r>";
        List<Integer> pauses=new ArrayList<>();
        List<String> retries=new ArrayList<>();
        Replay recovers=new Replay().add(setup,busy).add(setup,busy);
        recovers.steps.addAll(opening().steps);
        recovers.add("A8","300 A8\r>");
        Tp20 tp=new Tp20(new Elm(recovers,(kind,command,detail,time)->{
            if(kind.equals("tp20_retry"))retries.add(detail);
        }),pauses::add);
        tp.open(1);tp.close();
        check(recovers.steps.isEmpty()&&pauses.equals(Arrays.asList(180,6000)),"D7 delays the last setup request and permits opening on D0");
        check(retries.equals(Collections.singletonList("D7; delay_ms=6000")),"delayed setup retry is logged once");

        Replay refuses=new Replay().add(setup,busy).add(setup,busy).add(setup,busy);
        Tp20 unavailable=new Tp20(new Elm(refuses,EVENTS),millis->{});
        try{unavailable.open(1);throw new AssertionError("Persistent D7 should stop setup");}
        catch(Tp20.ChannelBusy expected){check(refuses.steps.isEmpty(),"persistent D7 is reported after exactly three requests");}

        Replay cancelled=new Replay().add(setup,busy).add(setup,busy);
        Tp20 stopping=new Tp20(new Elm(cancelled,EVENTS),millis->{if(millis==6000)throw new IOException("Остановлено");});
        try{stopping.open(1);throw new AssertionError("Cancelled retry should stop setup");}
        catch(IOException expected){check(cancelled.steps.isEmpty()&&expected.getMessage().equals("Остановлено"),"cancellation prevents the delayed request");}
    }
    private static void checkFinalFrameAfterAck() throws IOException {
        Replay abs=new Replay().add("03C00010000301","203 00 D0 00 03 90 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021A9B","300 B1\r300 10 00 03 7F 1A 11\r>")
            .add("B1","300 10 00 03 7F 1A 11\r>")
            .add("1100041800FF00","300 B2\r300 11 00 03 7F 18 12\r>").add("B2","NO DATA\r>")
            .add("1200022101","300 B3\r300 22 00 0E 61 01 07 64 00\r300 23 07 64 00 07 64 00 07\r300 14 64 00\r>")
            .add("B5","300 14 64 00\r300 A3\r>").add("A3","300 A1 0F 8A FF 4A FF\r>")
            .add("A8","300 A8\r>");
        Tp20 absTp=new Tp20(new Elm(abs,EVENTS));absTp.open(3);
        check(Protocol.hex(absTp.request("1A9B")).equals("7F1A11"),"ABS identity rejection preserved after repeated final frame");
        check(Protocol.hex(absTp.request("1800FF00")).equals("7F1812"),"ABS next request preserves sequence after duplicate");
        check(Protocol.hex(absTp.request("2101")).equals("6101076400076400076400076400"),"ABS complete measurement after repeated final frame and A3");
        absTp.close();check(abs.steps.isEmpty(),"ABS replay consumed");

        Replay eps=new Replay().add("09C00010000301","209 00 D0 00 03 A8 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021A9B","300 B1\r300 20 00 30 5A 9B 31 4B 30\r300 21 39 30 39 31 34 34 52\r300 22 20 20 33 35 30 31 00\r300 23 00 00 00 00 1F 3E 2C\r300 24 82 51 45 50 53 5F 5A\r300 25 46 4C 53 20 4B 6C 2E\r300 26 20 32 33 37 20 20 20\r300 17 20\r>").add("B8","NO DATA\r>")
            .add("1100022101","300 B2\r300 28 00 0E 61 01 1A 46 52\r300 29 5D DD 7E 74 00 01 25\r300 1A 00 00\r>").add("BB","NO DATA\r>")
            .add("1200022107","300 B3\r300 2B 00 0E 61 07 51 FE 76\r300 2C 57 02 81 25 04 91 25\r300 1D 03 E5\r>")
            .add("BE","300 1D 03 E5\r300 A3\r>").add("A3","300 A1 0F 8A FF 4A FF\r>")
            .add("A8","300 A8\r>");
        Tp20 epsTp=new Tp20(new Elm(eps,EVENTS));epsTp.open(9);
        epsTp.request("1A9B");epsTp.request("2101");
        check(Protocol.hex(epsTp.request("2107")).equals("610751FE765702812504912503E5"),"EPS complete measurement after repeated final frame and A3");
        epsTp.close();check(eps.steps.isEmpty(),"EPS replay consumed");

        Replay changed=opening().add("110002210F","300 B2\r300 11 00 02 61 0F\r>")
            .add("B2","300 11 00 02 61 10\r>");
        Tp20 changedTp=new Tp20(new Elm(changed,EVENTS));changedTp.open(1);
        boolean rejected=false;
        try{changedTp.request("210F");}catch(IOException e){rejected=e.getMessage().contains("после завершения ответа");}
        check(rejected,"different final frame is still rejected after ACK");
    }
    static void run() throws Exception {
        Replay gateway=new Replay().add("1FC00010000301","21F 00 D0 00 03 50 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021A9F","300 B1\r300 20 00 09 5A 9F 05 03 03\r300 11 00 01 01 FF\r>")
            .add("B2","NO DATA\r>").add("A8","300 A8\r>");
        Tp20 gatewayTp=new Tp20(new Elm(gateway,EVENTS));gatewayTp.open(0x1F);
        check(Protocol.gatewayModules(gatewayTp.request("1A9F")).equals(Collections.singletonList(3)),"gateway installation read immediately after opening transport");
        gatewayTp.close();check(gateway.steps.isEmpty(),"gateway transport closed");
        Replay replay=opening().add("110002210F","300 B2\r300 21 00 0E 61 0F 36 00 01\r300 22 36 00 02 36 00 03 08\r300 13 00 00\r>").add("B4","NO DATA\r>").add("A8","300 A8\r>");
        Tp20 tp=new Tp20(new Elm(replay,EVENTS));tp.open(1);
        check(Protocol.hex(tp.request("210F")).equals("610F360001360002360003080000"),"TP2 ACK and reassembly");tp.close();check(replay.steps.isEmpty(),"replay consumed");
        Replay duplicate=new Replay().add("01C00010000301","201 00 D0 00 03 40 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021089","300 B1\r300 10 00 02 50 89\r300 10 00 02 50 89\r>").add("B1","NO DATA\r>").add("A8","300 A8\r>");
        Tp20 duplicateTp=new Tp20(new Elm(duplicate,EVENTS));duplicateTp.open(1);duplicateTp.close();check(duplicate.steps.isEmpty(),"TP2 duplicate final frame tolerated");
        checkFinalFrameAfterAck();
        checkBusyChannelRetry();
        checkControlData();
        checkAcknowledgementPrecedesChannelTest();
        checkResetPrompt();
        Replay firstReply=new Replay().add("01C000100003011","201 00 D0 00 03 40 07 01\r>")
            .add("A00F8AFF32FF","300 A1 0F 8A FF 4A FF\r>")
            .add("1000021089","300 B1\r300 10 00 02 50 89\r>").add("B1","NO DATA\r>").add("A8","300 A8\r>");
        Tp20 fastOpen=new Tp20(new Elm(firstReply,EVENTS));fastOpen.open(1,true);
        boolean blocked=false;try{fastOpen.request("3101");}catch(IOException e){blocked=true;}
        fastOpen.close();check(blocked&&firstReply.steps.isEmpty(),"single-response ELM setup and route whitelist");
        Replay bad=opening().add("110002210F","300 B2\r300 12 00 05 61 0F 36 00 01\r>");
        Tp20 broken=new Tp20(new Elm(bad,EVENTS));broken.open(1);boolean fail=false;try{broken.request("210F");}catch(IOException e){fail=true;}check(fail,"TP2 sequence fault detected");
        ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Elm.StreamWire wire=new Elm.StreamWire(new ByteArrayInputStream("41 05 82\r>".getBytes("US-ASCII")),sent,EVENTS);
        check(wire.exchange("0105",100).endsWith(">"),"ELM prompt boundary");check(sent.toString("US-ASCII").equals("0105\r"),"one CR per command");
        Elm.StreamWire timeout=new Elm.StreamWire(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream(),EVENTS);fail=false;
        try{timeout.exchange("0105",1);}catch(IOException e){fail=true;}check(fail&&timeout.closed,"timeout invalidates session");
    }
    private static void checkControlData()throws IOException{
        Replay keepAlive=opening().add("110002210F","300 B2\r300 A3\r>")
            .add("A3","300 A1 0F 8A FF 4A FF\r300 11 00 05 61 0F 36 00 01\r>")
            .add("B2","300 B2\r>");
        Tp20 tp=new Tp20(new Elm(keepAlive,EVENTS));tp.open(1);
        check(Protocol.hex(tp.request("210F")).equals("610F360001")&&keepAlive.steps.isEmpty(),
            "data accompanying a keep-alive response is retained, and ACK after final uses the low sequence nibble");
        Replay unexpected=opening().add("110002210F","300 B2\r300 11 00 02 61 0F\r>")
            .add("B2","300 A3\r>").add("A3","300 A1 0F 8A FF 4A FF\r300 12 00 02 61 10\r>");
        Tp20 bad=new Tp20(new Elm(unexpected,EVENTS));bad.open(1);
        try{bad.request("210F");throw new AssertionError("Unrelated data after keep-alive must fail");}
        catch(IOException expected){check(expected.getMessage().contains("после завершения"),"keep-alive never hides unrelated data after the final frame");}
    }
    private static void checkAcknowledgementPrecedesChannelTest()throws IOException{
        Replay engine=opening()
            .add("1100022173","300 B2\r300 A3\r300 21 00 1A 61 73 01 C8 2E\r300 22 21 85 47 12 FA 76 12\r300 23 FA 64 25 00 00 1F 50\r300 14 00 25 00 00 1F 14 80\r>")
            .add("B5","300 14 00 25 00 00 1F 14 80\r>")
            .add("A3","300 A1 0F 8A FF 4A FF\r>")
            .add("1200022173","300 B3\r300 15 00 05 61 73 36 00 01\r>")
            .add("B6","NO DATA\r>");
        Tp20 tp=new Tp20(new Elm(engine,EVENTS));tp.open(1);
        check(Protocol.hex(tp.request("2173")).equals("617301C82E21854712FA7612FA642500001F50002500001F1480"),
            "a bundled channel test cannot delay acknowledgement of the already received engine reply");
        check(Protocol.hex(tp.request("2173")).equals("6173360001")&&engine.steps.isEmpty(),
            "the following request preserves both sequence counters");
    }
    private static void checkResetPrompt()throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        List<String> events=new ArrayList<>();
        Elm.StreamWire reset=new Elm.StreamWire(new ByteArrayInputStream("\r>ATZ\r>\rELM327 v1.5\r>OK\r>".getBytes("US-ASCII")),output,
            (kind,cmd,detail,time)->events.add(kind));
        Elm elm=new Elm(reset,(kind,cmd,detail,time)->{});
        check(elm.at("ATZ").contains("ELM327 v1.5"),"reset waits through empty and echo-only prompts for its startup response");
        elm.ok("ATE0");
        check(output.toString("US-ASCII").equals("ATZ\rATE0\r")&&Collections.frequency(events,"reset_pending")==2,
            "startup banner does not become the ATE0 response");
        Elm.StreamWire silent=new Elm.StreamWire(new ByteArrayInputStream(">".getBytes("US-ASCII")),new ByteArrayOutputStream(),EVENTS);
        try{silent.exchange("ATZ",5);throw new AssertionError("Empty prompt must not confirm reset");}
        catch(IOException expected){check(silent.closed,"empty reset prompt remains bounded by the original timeout");}
    }
}
