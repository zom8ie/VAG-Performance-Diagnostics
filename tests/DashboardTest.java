package ru.yeti.diag;

import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class DashboardTest {
    static void run(){
        checkPresentation();
        checkDemoReadings();
        DashboardCatalog.Parameter rpm=DashboardCatalog.find("OBD/pid12");
        DashboardCatalog.Parameter wheel=DashboardCatalog.find("ABS/g1f0");
        DashboardCatalog.Parameter lambdaPower=DashboardCatalog.find("OBD/lambda_power");
        DashboardCatalog.Parameter fuelPower=DashboardCatalog.find("OBD/fuel_power");
        check(DashboardCatalog.forBlock(Engine.COMBINED_ENGINE).contains(rpm)
            &&DashboardCatalog.forBlock(Engine.COMBINED_ENGINE).contains(DashboardCatalog.find("ECM/g14f2"))
            &&DashboardCatalog.forBlock(Engine.COMBINED_ENGINE).contains(lambdaPower)
            &&DashboardCatalog.forBlock(Engine.COMBINED_ENGINE).contains(fuelPower),
            "engine picker includes both standard and manufacturer measurements");
        check(DashboardCatalog.search(DashboardCatalog.forBlock(Engine.COMBINED_ENGINE),"пропуски цилиндр 4")
                .contains(DashboardCatalog.find("ECM/g16f0"))
            &&DashboardCatalog.search(DashboardCatalog.forBlock("HALDEX"),"несуществующий параметр").isEmpty(),
            "parameter picker searches visible names and hides non-matches immediately");
        List<DashboardCatalog.Parameter> grid=DashboardCatalog.gridCells(Arrays.asList(rpm,wheel));
        check(grid.size()==6&&grid.get(0)==rpm&&grid.get(1)==wheel&&grid.get(5)==null,
            "car dashboard always reserves a fixed 3 by 2 page");
        DashboardReadings readings=new DashboardReadings();
        DashboardReadings.Plan plan=new DashboardReadings.Plan(Arrays.asList(rpm,null,rpm,wheel),readings::record);
        check(plan.routes.size()==2&&plan.routes.get("OBD").size()==1&&!plan.contains("OBD",wheel.key),
            "dashboard plan groups by source and deduplicates repeated cells");

        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot(){
            @Override public void status(String message,boolean ready){}
        };
        List<String> commands=new ArrayList<>();
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 08 10 00 00\r>";
            if(command.equals("010C"))return "7E8 04 41 0C 0B E0\r>";
            if(command.startsWith("22"))return "NO DATA\r>";
            if(command.startsWith("AT"))return "OK\r>";
            throw new AssertionError("Unexpected dashboard command "+command);
        };
        state.engine=new Engine(new Elm(wire,state),state,state);
        state.engine.setFilter("Температуры");
        state.engine.pauseMeasurements();
        state.engine.setDashboard(new DashboardReadings.Plan(Collections.singletonList(rpm),(mode,row)->{
            readings.record(mode,row);
            if(!row.value.equals("—"))state.engine.stopped=true;
        }));
        state.engine.run();
        DashboardReadings.Sample sample=readings.sample(rpm.id);
        check(state.failure==null&&state.rows.isEmpty()&&commands.contains("010C")&&!commands.contains("0105"),
            "dashboard reads only the selected OBD sensor and keeps its rows separate from the phone screen");
        check(sample!=null&&sample.value.equals("760 об/мин"),"dashboard publishes real decoded data");
        Engine.Row temporarilyMissing=new Engine.Row(rpm.key,rpm.name,"Основные","—","","Нет данных / неполный ответ");
        check(!readings.record("OBD",temporarilyMissing)&&readings.sample(rpm.id).value.equals("760 об/мин"),
            "dashboard retains its last successful value across a temporary missing response");
        check(sample.scalePosition==DashboardReadings.NO_SCALE,
            "the dial waits for two distinct numeric readings");
        Engine.Row changed=new Engine.Row(rpm.key,rpm.name,"","900","об/мин","");
        check(readings.record("OBD",changed)&&!readings.record("OBD",changed),
            "a changed value triggers refresh, duplicate values are suppressed");
        readings.record("OBD",new Engine.Row(rpm.key,rpm.name,"","740","об/мин",""));
        readings.record("OBD",new Engine.Row(rpm.key,rpm.name,"","820","об/мин",""));
        DashboardReadings.Sample midpoint=readings.sample(rpm.id);
        check(midpoint.scalePosition==50&&midpoint.minimum.equals("740")&&midpoint.maximum.equals("900"),
            "the dial uses the measured range for its midpoint and endpoint labels");
        readings.record("OBD",new Engine.Row(rpm.key,rpm.name,"","—","",""));
        check(readings.sample(rpm.id).range.equals("Мин 740 об/мин · Макс 900 об/мин")
                &&readings.sample(rpm.id).scalePosition==DashboardReadings.NO_SCALE,
            "missing measurements do not alter numeric extrema");
        checkBackgroundPolling(rpm);
        checkOneObdDiscoveryAcrossDashboardRoutes(rpm,false);
        checkOneObdDiscoveryAcrossDashboardRoutes(rpm,true);
        checkDashboardKeepsTp20DuringObdBackoff(rpm);
        checkSwitchPageDuringRead(rpm);
    }

    private static void checkPresentation(){
        DashboardNeedle needle=new DashboardNeedle();
        check(needle.positionAt(0)==DashboardReadings.NO_SCALE&&!needle.running(0),"no animated gauge before data");
        needle.moveTo(20,100);
        check(needle.positionAt(100)==20&&!needle.running(100),"first position appears without invented movement");
        needle.moveTo(100,200);
        check(needle.positionAt(200)==20&&needle.positionAt(500)==60&&needle.running(500),"needle interpolates between real readings");
        needle.moveTo(100,500);
        check(needle.positionAt(800)==100&&!needle.running(800),"duplicate samples do not restart animation");
        needle.moveTo(0,900);
        check(needle.positionAt(1200)==50,"falling readings animate too");
        needle.moveTo(80,1200);
        check(needle.positionAt(1200)==50&&needle.positionAt(1500)==65&&needle.positionAt(1800)==80,
            "a new target continues from the current position without jumping");
        needle.moveTo(DashboardReadings.NO_SCALE,1500);
        check(needle.positionAt(1500)==DashboardReadings.NO_SCALE&&!needle.running(1500),
            "stale or unavailable data stops animation immediately");
        needle.moveTo(25,1600);
        check(needle.positionAt(1600)==25&&!needle.running(1600),"reconnection does not animate from an invalid position");
        needle.moveTo(100,1700);
        check(needle.positionAt(10000)==100&&!needle.running(10000),"delayed host frame reaches the latest target");
        float[] bounds=DashboardTouch.bounds(800,400);
        check(bounds[0]==0&&bounds[2]==800&&bounds[1]>0&&bounds[3]<400,
            "panel bounds fill the surface width and centre the 3x2 design vertically");
        check(DashboardTouch.cellAt(133,133,800,400)==0&&DashboardTouch.cellAt(400,133,800,400)==1
            &&DashboardTouch.cellAt(666,266,800,400)==5&&DashboardTouch.cellAt(400,4,800,400)==-1,
            "surface taps map onto the six grid cells and miss outside the panel");
        check(DashboardTouch.pageAction(0)==-1&&DashboardTouch.pageAction(3)==-1
            &&DashboardTouch.pageAction(2)==1&&DashboardTouch.pageAction(5)==1
            &&DashboardTouch.pageAction(1)==0&&DashboardTouch.pageAction(4)==0,
            "left cards turn pages back, right cards forward, middle cards only zoom");
        check(DashboardTouch.shifted(0,-1)==DashboardCatalog.PAGES-1
            &&DashboardTouch.shifted(DashboardCatalog.PAGES-1,1)==0&&DashboardTouch.shifted(1,1)==2,
            "pages wrap around in both directions");
        check(DashboardTouch.cellAt(400,133,800,400)==1&&DashboardTouch.pageAction(1)==0,
            "a double tap focus inside a middle cell selects that card for zoom without turning pages");
        String[] boost=DashboardTouch.reading("1.03 бар","бар");
        String[] selector=DashboardTouch.reading("4 1","");
        String[] status=DashboardTouch.reading("Нет связи","бар");
        check(boost[0].equals("1.03")&&boost[1].equals("бар")
            &&selector[0].equals("4 1")&&selector[1].isEmpty()
            &&status[0].equals("Нет связи")&&status[1].isEmpty(),
            "readings split into a centred number and a small unit line, statuses stay whole");
    }

    private static void checkDemoReadings(){
        DashboardReadings readings=new DashboardReadings();
        List<DashboardCatalog.Parameter> first=DashboardDemo.page(0);
        check(first.size()==DashboardCatalog.CELLS&&first.get(0).id.equals("ECM/g115f2")
            &&first.get(5).id.equals("OBD/maf_power"),"demo starts with six visible engine instruments");
        check(!DashboardDemo.isEmpty(first)&&DashboardDemo.isEmpty(Arrays.asList(null,null,null,null,null,null)),
            "demo defaults only an unconfigured page");
        DashboardDemo.record(readings,first,0);
        DashboardDemo.record(readings,first,1200);
        DashboardReadings.Sample boost=readings.sample("ECM/g115f2");
        check(boost!=null&&boost.value.endsWith(" бар")&&boost.scalePosition!=DashboardReadings.NO_SCALE,
            "demo publishes changing numeric readings for animated scales");
        List<DashboardCatalog.Parameter> haldex=DashboardDemo.page(2);
        DashboardDemo.record(readings,haldex,2400);
        check(readings.sample("HALDEX/h2BE6").value.endsWith(" А")
            &&readings.sample("OBD/lambda_power").value.endsWith(" кВт"),
            "demo covers the saved Haldex and power instrument formats");
    }

    private static void checkSwitchPageDuringRead(DashboardCatalog.Parameter rpm){
        DashboardCatalog.Parameter coolant=DashboardCatalog.find("OBD/pid5");
        DashboardCatalog.Parameter speed=DashboardCatalog.find("OBD/pid13");
        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot(){
            @Override public void status(String message,boolean ready){}
        };
        List<String> commands=new ArrayList<>();
        List<String> published=new ArrayList<>();
        DashboardReadings.Plan third=new DashboardReadings.Plan(Collections.singletonList(speed),(mode,row)->{
            if(row.note.equals("Ожидание первого ответа"))return;
            published.add(row.key);
            state.engine.stopped=true;
        });
        DashboardReadings.Plan second=new DashboardReadings.Plan(Collections.singletonList(coolant),(mode,row)->{
            if(row.note.equals("Ожидание первого ответа"))return;
            published.add(row.key);
            state.engine.setDashboard(third);
        });
        DashboardReadings.Plan first=new DashboardReadings.Plan(Collections.singletonList(rpm),
            (mode,row)->{if(!row.note.equals("Ожидание первого ответа"))published.add(row.key);});
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 08 18 00 00\r>";
            if(command.equals("010C")){
                state.engine.setDashboard(second);
                return "7E8 04 41 0C 0B E0\r>";
            }
            if(command.equals("0105"))return "7E8 03 41 05 82\r>";
            if(command.equals("010D"))return "7E8 03 41 0D 00\r>";
            if(command.startsWith("AT"))return "OK\r>";
            throw new AssertionError("Unexpected dashboard command "+command);
        };
        state.engine=new Engine(new Elm(wire,state),state,state);
        state.engine.pauseMeasurements();
        state.engine.setDashboard(first);
        state.engine.run();
        check(state.failure==null&&published.equals(Arrays.asList(coolant.key,speed.key)),
            "switching between three pages discards the old in-flight value and publishes only the active page");
        check(Collections.frequency(commands,"0100")==1&&Collections.frequency(commands,"010C")==1
            &&commands.indexOf("010C")<commands.indexOf("0105")&&commands.indexOf("0105")<commands.indexOf("010D"),
            "same-protocol page switches reuse setup and read only the new page");
    }

    private static void checkOneObdDiscoveryAcrossDashboardRoutes(DashboardCatalog.Parameter rpm,boolean failFastWait){
        DashboardCatalog.Parameter boost=DashboardCatalog.find("ECM/g115f2");
        LoggedReadingsTest.GroupWire kwp=new LoggedReadingsTest.GroupWire();
        kwp.replies.put("2173","617301C82E21854712FA7612FA642500001F50002500001F1480");
        kwp.replies.put("1A9B","7F1A11");
        List<String> commands=new ArrayList<>();
        List<String> fastWaitEvents=new ArrayList<>();
        String[] lastWait={""};
        boolean[] variableCanLength={false};
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.startsWith("ATST"))lastWait[0]=command;
            if(command.equals("ATV1")||command.equals("ATV0"))variableCanLength[0]=command.equals("ATV1");
            if(failFastWait&&command.endsWith("2173")&&lastWait[0].equals("ATST0A"))return "NO DATA\r>";
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 10 00 00\r>";
            if(command.equals("010C")||command.equals("010C1"))
                return variableCanLength[0]?"NO DATA\r>":"7E8 04 41 0C 0B E0\r>";
            return kwp.exchange(command,timeout);
        };
        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot(){
            @Override public void status(String message,boolean ready){}
            @Override public void record(String kind,String command,String detail,long elapsed){
                if(kind.startsWith("tp20_live_wait"))fastWaitEvents.add(kind);
            }
        };
        List<String> modes=new ArrayList<>();
        state.engine=new Engine(new Elm(wire,state),state,state);
        state.engine.pauseMeasurements();
        state.engine.setDashboard(new DashboardReadings.Plan(Arrays.asList(rpm,boost),(mode,row)->{
            if(row.value.equals("—"))return;
            modes.add(mode);
            if(modes.size()==3)state.engine.stopped=true;
        }));
        state.engine.run();
        check(state.failure==null&&modes.equals(Arrays.asList("OBD","ECM","OBD")),"dashboard keeps publishing across different protocols");
        check(Collections.frequency(commands,"ATZ")==1&&Collections.frequency(commands,"0100")==1&&commands.contains("010C1"),
            "switching dashboard engine routes reuses adapter setup, capabilities and confirmed fast reads");
        check(fastWaitEvents.equals(failFastWait?Arrays.asList("tp20_live_wait","tp20_live_wait_fallback"):Collections.singletonList("tp20_live_wait"))
            &&Collections.frequency(commands,"01C00010000301")== (failFastWait?2:1),
            "confirmed engine group uses the short window and reopens at normal timing after a missing reply");
    }

    private static void checkDashboardKeepsTp20DuringObdBackoff(DashboardCatalog.Parameter rpm){
        DashboardCatalog.Parameter boost=DashboardCatalog.find("ECM/g115f2");
        LoggedReadingsTest.GroupWire kwp=new LoggedReadingsTest.GroupWire();
        kwp.replies.put("2173","617301C82E21854712FA7612FA642500001F50002500001F1480");
        List<String> commands=new ArrayList<>();
        Elm.Wire wire=(command,timeout)->{
            commands.add(command);
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 00 10 00 00\r>";
            if(command.equals("010C"))return "NO DATA\r>";
            return kwp.exchange(command,timeout);
        };
        DiagnosticBlocksTest.Snapshot state=new DiagnosticBlocksTest.Snapshot(){
            @Override public void status(String message,boolean ready){}
        };
        int[] expanded={0};
        state.engine=new Engine(new Elm(wire,state),state,state);
        state.engine.pauseMeasurements();
        state.engine.setDashboard(new DashboardReadings.Plan(Arrays.asList(rpm,boost),(mode,row)->{
            if(mode.equals("ECM")&&++expanded[0]==2)state.engine.stopped=true;
        }));
        state.engine.run();
        check(state.failure==null&&expanded[0]==2&&Collections.frequency(commands,"010C")==1
            &&Collections.frequency(commands,"01C00010000301")==1&&!commands.contains("ATSP6"),
            "during OBD backoff the mixed dashboard updates its working TP2.0 route without closing and reopening it: "
                +state.failure+"; ECM samples="+expanded[0]+"; commands="+commands);
    }

    private static void checkBackgroundPolling(DashboardCatalog.Parameter rpm){
        class BackgroundSnapshot extends DiagnosticBlocksTest.Snapshot {
            int phoneSamples, dashboardSamples, clears;
            @Override public void status(String message,boolean ready){}
            @Override public void clear(){super.clear();clears++;}
            @Override public void row(Engine.Row row){
                super.row(row);
                if(!row.key.equals("pid5")||row.value.equals("—"))return;
                phoneSamples++;
                if(phoneSamples==1)engine.setPhoneInForeground(false);
                if(phoneSamples==2){
                    engine.setDashboard(new DashboardReadings.Plan(Collections.singletonList(rpm),(mode,sample)->{
                        if(!sample.value.equals("—")){
                            dashboardSamples++;
                            engine.setPhoneInForeground(true);
                        }
                    }));
                }
                if(phoneSamples==3)engine.stopped=true;
            }
        }
        BackgroundSnapshot state=new BackgroundSnapshot();
        Elm.Wire wire=(command,timeout)->{
            if(command.equals("ATDPN"))return "A6\r>";
            if(command.equals("0100"))return "7E8 06 41 00 08 10 00 00\r>";
            if(command.equals("010C"))return "7E8 04 41 0C 0B E0\r>";
            if(command.equals("0105")||command.equals("01051"))return "7E8 03 41 05 82\r>";
            if(command.startsWith("22"))return "NO DATA\r>";
            if(command.startsWith("AT"))return "OK\r>";
            throw new AssertionError("Unexpected command "+command);
        };
        state.engine=new Engine(new Elm(wire,state),state,state);
        state.engine.setFilter("Температуры");
        state.engine.run();
        check(state.failure==null&&state.phoneSamples==3&&state.dashboardSamples==1,
            "phone polling continues in background, yields to the dashboard, and resumes on the phone");
        check(state.clears==1,"dashboard handoff preserves phone measurements");
    }
}
