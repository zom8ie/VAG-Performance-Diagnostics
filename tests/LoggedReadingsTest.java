package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

/** Regression payloads from the 0.14.0 journal, with no vehicle identifiers. */
final class LoggedReadingsTest {
    private static final class Samples extends DiagnosticBlocksTest.Snapshot {
        @Override public void status(String message,boolean ready){}
    }
    static final class GroupWire implements Elm.Wire {
        final Map<String,String> replies=new HashMap<>();
        final List<String> commands=new ArrayList<>();
        int sequence;
        public String exchange(String command,int timeout){
            commands.add(command);
            if(command.startsWith("AT"))return "OK\r>";
            if(command.endsWith("C00010000301")){sequence=0;return "20"+command.substring(1,2)+" 00 D0 00 03 40 07 01\r>";}
            if(command.equals("A00F8AFF32FF"))return "300 A1 0F 8A FF 4A FF\r>";
            if(command.equals("A8"))return "300 A8\r>";
            if(command.startsWith("B"))return "NO DATA\r>";
            String request=command.substring(6);
            String payload=request.equals("1089")?"5089":replies.get(request);
            if(payload==null)throw new AssertionError("Unscripted measurement "+request);
            StringBuilder answer=new StringBuilder(String.format(Locale.ROOT,"300 B%X\r",(Integer.parseInt(command.substring(0,2),16)+1)&15));
            byte[] bytes=Protocol.bytes(String.format(Locale.ROOT,"%04X",payload.length()/2)+payload);
            for(int offset=0;offset<bytes.length;offset+=7){
                boolean last=offset+7>=bytes.length;
                answer.append(String.format(Locale.ROOT,"300 %02X",(last?0x10:0x20)|sequence));sequence=(sequence+1)&15;
                for(int i=offset;i<Math.min(offset+7,bytes.length);i++)answer.append(String.format(Locale.ROOT," %02X",Protocol.u(bytes[i])));
                answer.append('\r');
            }
            return answer+">";
        }
    }
    private static VagReader reader(String mode,GroupWire wire,Samples samples)throws IOException{
        DiagnosticSession session=new DiagnosticSession(new Elm(wire,samples),samples,samples,()->false,()->true,m->true);
        session.mode=mode;
        VagReader reader=new VagReader(session,new BlockInfoReader(session));reader.open();return reader;
    }
    private static void value(Samples samples,String mode,String key,double expected,String unit){
        Engine.Row row=samples.rows.get(mode+"/"+key);
        check(row!=null&&row.unit.equals(unit),mode+" "+key+" decoded with its declared unit");
        check(Math.abs(Double.parseDouble(row.value)-expected)<.051,mode+" "+key+" logged value within display rounding");
    }
    static void run()throws Exception{
        near(Protocol.kwpValue(0x14,12,128).value,0,"mixture correction neutral byte is 128");
        near(Protocol.kwpValue(0x14,12,140).value,1.125,"logged idle correction");
        near(Protocol.kwpValue(0x14,50,131).value,1.171875,"logged partial-load correction");
        near(Protocol.kwpValue(0x14,50,127).value,-.390625,"negative mixture correction");
        near(Protocol.kwpValue(0x51,0x80,0).value,-1433.6,"signed angle lower boundary");
        near(Protocol.kwpValue(0x5D,8,127).value,-.008,"logged steering torque keeps small negative values");
        near(Protocol.kwpValue(0x5E,2,93).value,-7,"DQ250 torque uses the neutral offset before scaling");
        near(Protocol.kwpValue(0x5E,2,128).value,0,"DQ250 neutral torque");
        for(String[] identity:new String[][]{{"1K0909144R  3501","1K0909144R","3501"},
                {"02E300016F  4013","02E300016F","4013"},{"5L0907115G  0020","5L0907115G","0020"}}){
            byte[] header=("\u005a\u009b"+identity[0]+"\u0000").getBytes("ISO-8859-1");
            Map<String,String> parsed=BlockInfoReader.kwpIdentityFields(header);
            check(parsed.size()==2&&identity[1].equals(parsed.get("F187"))&&identity[2].equals(parsed.get("F189")),
                "logged KWP part number and software version stay separate without inventing hardware identity");
        }
        check(BlockInfoReader.kwpIdentityFields(Protocol.bytes("7F1A11")).isEmpty(),"identity rejection cannot become an identifier");

        GroupWire engine=new GroupWire();
        engine.replies.put("2114","6114224B80224B80224B80224B80250000250000250000250000");
        engine.replies.put("211F","611F1F147F1F1480250000250000250000250000250000250000");
        engine.replies.put("2120","6120140C8C1432832500002500001A3C652504DB10FF012500EB");
        engine.replies.put("215B","615B01C81321FF0F2232CC2232CC21430D103F0035AB7F610A42");
        engine.replies.put("215D","615D01C81321851651000625000010FF0010FF0010FF0010FF00");
        engine.replies.put("216A","616A530FA3530FD221FF681A3039050A805A02005A025436000A");
        Samples e=new Samples();VagReader er=reader("ECM",engine,e);
        for(int group:new int[]{20,31,32,91,93})check(er.readGroup(group),"logged engine group "+group+" now decodes");
        check(!er.readGroup(106),"pressure decodes but the unresolved second field keeps group 106 unverified");
        value(e,"ECM","g20f0",0,"°");value(e,"ECM","g31f0",.9921875,"1");
        value(e,"ECM","g32f0",1.125,"%");value(e,"ECM","g91f2",38,"°");
        value(e,"ECM","g93f2",.2625,"°");value(e,"ECM","g106f0",40.03,"бар");
        check(e.rows.get("ECM/g106f1").unit.isEmpty()&&er.lastGroupHadRawValues(),"pressure is never mislabeled as pump percentage");
        engine.replies.put("218C","7F2131");
        try{er.readGroup(140);throw new AssertionError("Explicit unsupported reply expected");}
        catch(VagReader.NoMeasurements unavailable){check(unavailable.unsupported&&unavailable.getMessage().contains("7F2131"),"explicit NRC is retained separately from missing data");}
        engine.replies.put("218C","7F2122");
        try{er.readGroup(140);throw new AssertionError("Conditions-not-correct reply expected");}
        catch(VagReader.NoMeasurements unavailable){check(!unavailable.unsupported,"temporary ECU conditions never disable a group permanently");}
        er.close();

        GroupWire dsg=new GroupWire();dsg.replies.put("2136","61365F0D51333747303733360020303536");
        dsg.replies.put("216D","616D2443FA1780C0178004178002");
        Samples d=new Samples();VagReader dr=reader("DSG",dsg,d);
        check(dr.readGroup(54)&&d.rows.get("DSG/g54f0").value.equals("Q37G0736 056"),"complete length-prefixed DSG text with embedded zero");
        check(dr.readGroup(109),"DSG mileage group now decodes");value(d,"DSG","g109f0",174020,"км");dr.close();

        GroupWire eps=new GroupWire();eps.replies.put("2101","61011A46535D087F740000250000");
        eps.replies.put("2107","610751FFCD5700812504912503E5");
        Samples s=new Samples();VagReader sr=reader("EPS",eps,s);
        check(sr.readGroup(1)&&sr.readGroup(7),"both logged EPS groups now decode");
        value(s,"EPS","g1f1",-.008,"Н·м");value(s,"EPS","g7f0",-2.23125,"°");sr.close();

        GroupWire abs=new GroupWire();abs.replies.put("2101","6101076400076400076400076400");
        abs.replies.put("2104","610451FFDE5200115500FC");abs.replies.put("2105","610553FFF72503E5");
        abs.replies.put("2106","610654FFFF0647CA063A00");
        Samples a=new Samples();VagReader ar=reader("ABS",abs,a);
        for(int group:new int[]{1,4,5,6})check(ar.readGroup(group),"logged ABS layout "+group+" works without an identity response");
        value(a,"ABS","g4f0",-1.4875,"°");value(a,"ABS","g4f1",.16677,"м/с²");
        value(a,"ABS","g5f0",-.09,"бар");value(a,"ABS","g6f0",-.0973,"м/с²");
        abs.replies.put("2104","610451FFDE5300115500FC");a.rows.clear();
        check(!ar.readGroup(4)&&a.rows.isEmpty(),"a different unknown ABS layout never inherits the observed mapping");ar.close();

        check(KwpMeasurements.parse(Protocol.bytes("61365F0D51333747"),54)==null,"truncated long text is rejected");
        check(KwpMeasurements.parse(Protocol.bytes("61013600"),1)==null,"truncated numeric field is rejected");
        check(KwpMeasurements.parse(Protocol.bytes("61025F03414243360001"),2).get(1).type==0x36,"text length preserves the next numeric field boundary");
        check(KwpMeasurements.parse(Protocol.bytes("6102A00000000000360001"),2).get(1).type==0x36,"undecoded extended format preserves the next field boundary");

        long[] clock={0};ReadAvailability retry=new ReadAvailability(()->clock[0]);
        check(retry.ready("0156")&&retry.failed("0156")==1000&&!retry.ready("0156"),"first empty response has a short retry delay");
        clock[0]=1000;check(retry.ready("0156")&&retry.failed("0156")==5000,"repeated empty responses back off");
        clock[0]=6000;check(retry.failed("0156")==30000,"retry delay is bounded at 30 seconds");
        clock[0]=36000;check(retry.ready("0156"),"silence does not permanently disable a parameter");
        retry.success("0156");check(retry.failed("0156")==1000,"a positive response resets retry pacing");
        retry.clear();check(retry.ready("0156"),"a new session clears retry pacing");
        checkCorrectedPartialProbe();
        checkRefusedGroupSkippedInPartial();
    }
    private static void checkRefusedGroupSkippedInPartial(){
        GroupWire wire=new GroupWire();
        wire.replies.put("218C","7F2131");
        wire.replies.put("2120","6120140C8C1432832500002500001A3C652504DB10FF012500EB");
        Set<String> verified=new HashSet<>();
        DiagnosticBlocksTest.Snapshot sample=new DiagnosticBlocksTest.Snapshot(){
            @Override public void record(String kind,String command,String detail,long elapsed){
                if(kind.equals("analysis")&&detail.equals("partial sweep complete"))engine.stopped=true;
            }
        };
        sample.engine=new Engine(new Elm(wire,sample),sample,sample,new Engine.PartialProgress(){
            public boolean isVerified(String key){
                if(key.equals("ECM_group_v09_140")||key.equals("ECM_group_formats2_32")
                    ||key.equals("ECM_group_unsupported_140")||key.equals("ECM_group_unsupported_32"))
                    return verified.contains(key);
                return true;
            }
            public void markVerified(String key){verified.add(key);}
        });
        sample.engine.requestPartialAnalysis();sample.engine.run();
        check(sample.failure==null&&verified.contains("ECM_group_unsupported_140"),
            "an explicit group refusal is remembered as confirmed unsupported");
        check(verified.contains("ECM_group_formats2_32"),"a decoding group is still verified normally");
        int setups=Collections.frequency(wire.commands,"01C00010000301");
        long refusals=wire.commands.stream().filter(c->c.endsWith("218C")).count();
        check(refusals==1,"the refused group was requested exactly once");
        sample.engine.stopped=false;
        sample.engine.requestPartialAnalysis();sample.engine.run();
        check(sample.failure==null&&Collections.frequency(wire.commands,"01C00010000301")==setups
            &&wire.commands.stream().filter(c->c.endsWith("218C")).count()==refusals,
            "the next partial sweep skips the confirmed-unsupported group without reopening the channel");
    }
    private static void checkCorrectedPartialProbe(){
        GroupWire wire=new GroupWire();wire.replies.put("2120","6120140C8C1432832500002500001A3C652504DB10FF012500EB");
        Set<String> verified=new HashSet<>();
        DiagnosticBlocksTest.Snapshot sample=new DiagnosticBlocksTest.Snapshot(){
            @Override public void record(String kind,String command,String detail,long elapsed){
                if(kind.equals("analysis_result")&&detail.equals("ECM_group_formats2_32 success"))engine.stopped=true;
            }
        };
        sample.engine=new Engine(new Elm(wire,sample),sample,sample,new Engine.PartialProgress(){
            public boolean isVerified(String key){return !key.equals("ECM_group_formats2_32")&&!key.equals("ECM_group_unsupported_32");}
            public void markVerified(String key){verified.add(key);}
        });
        sample.engine.requestPartialAnalysis();sample.engine.run();
        check(sample.failure==null&&verified.contains("ECM_group_formats2_32")&&sample.rows.containsKey("ECM/g32f0"),
            "partial analysis revisits a corrected formula even when the previous revision was verified");
    }
}
