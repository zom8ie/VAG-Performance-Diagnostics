package ru.yeti.diag;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

/** Owns standard OBD polling and the supported PID mask. */
final class ObdReader {
    private final DiagnosticSession session;
    private final Elm elm;
    private final BlockInfoReader info;
    static final String ECU="7E8";
    private final Set<Integer> supported=new LinkedHashSet<>();
    private boolean capabilitiesReady;
    private final ReadAvailability availability=new ReadAvailability();
    private final Set<Integer> singleFramePids=new HashSet<>();
    private boolean fastPollingDisabled;
    private long nextStatusPoll;
    private final Set<Integer> staticStatusRead=new HashSet<>();
    private double powerAirflow=Double.NaN,powerLambda=Double.NaN,powerFuelRate=Double.NaN;
    volatile String filter="Все";
    volatile boolean readErrors;

    ObdReader(DiagnosticSession session,BlockInfoReader info){
        this.session=session;
        this.elm=session.elm;
        this.info=info;
    }

    void initialize() throws IOException {
        initialize(true);
    }

    void initialize(boolean showWaitingRows) throws IOException {
        readSupportedPids();
        if(showWaitingRows){
            for(Protocol.Pid pid:Protocol.PIDS)if(supports(pid))
                session.put("pid"+pid.id,pid.name,pid.group,"—","","Ожидание первого ответа");
        }
        if(session.dashboard==null){
            info.readEngineIdentity();
            readStatus();
        }
    }

    void readSupportedPids() throws IOException {
        capabilitiesReady=false;
        nextStatusPoll=0;staticStatusRead.clear();
        supported.clear();availability.clear();singleFramePids.clear();fastPollingDisabled=false;
        clearPowerInputs();
        String raw=elm.obd("0100");
        byte[] base=Protocol.positive(raw,ECU,0x41,0);
        if(base==null||base.length!=4)throw new IOException("Адаптер подключён, но двигатель не ответил. Проверь зажигание.");
        String protocol=elm.at("ATDPN").replaceAll("[\r\n> ]","");
        if(!protocol.equals("6")&&!protocol.equals("A6"))throw new IOException("Ожидался CAN 11-bit для Yeti. Получен протокол "+protocol);
        elm.isoPhysical("7E0",ECU);supported.addAll(Protocol.supported(0,base));
        for(int page=0x20;page<=0xE0&&supported.contains(page)&&session.isCurrent();page+=0x20) {
            byte[] d=Protocol.positive(elm.obd(String.format(Locale.ROOT,"01%02X",page)),ECU,0x41,page);
            if(d==null)break;supported.addAll(Protocol.supported(page,d));
        }
        session.log.record("capabilities","mode01",supported.toString(),0);
        capabilitiesReady=session.isCurrent();
    }

    boolean supports(Protocol.Pid pid){return supported.contains(pid.id);}
    boolean hasCapabilities(){return capabilitiesReady;}

    /** True when this pass would actually request something. Lets the mixed
     * engine cycle keep the TP2.0 channel open instead of closing it and
     * re-running adapter setup while every OBD source is backing off. */
    boolean hasPendingPoll(){
        if(session.analyzing.getAsBoolean())return true;
        return hasReadyPid(false,session::isEnabled)
            ||(filter.equals("Основные")||filter.equals("Все"))&&session.isEnabled("adapterV")
            ||readErrors||(filter.equals("Состояние")||filter.equals("Все"))&&System.nanoTime()/1_000_000>=nextStatusPoll;
    }

    boolean hasPendingDashboardPoll(DashboardReadings.Plan plan){
        return !hasCapabilities()||plan.contains("OBD","adapterV")
            ||hasReadyPid(true,key->DiagnosticSession.dashboardEnabled(plan,"OBD",key));
    }

    private boolean hasReadyPid(boolean dashboard,Predicate<String> enabled){
        for(Protocol.Pid p:Protocol.PIDS)
            if(shouldPoll(p,dashboard,enabled)&&availability.ready(pidRequest(p)))return true;
        return false;
    }

    private boolean shouldPoll(Protocol.Pid pid,boolean dashboard,Predicate<String> enabled){
        if(!supported.contains(pid.id))return false;
        boolean input=needsCalculatedPower(pid,enabled);
        return (enabled.test("pid"+pid.id)||input)
            &&(dashboard||input&&filter.equals("Основные")||filter.equals("Все")||pid.group.equals(filter));
    }

    byte[] readStandardPid(Protocol.Pid pid)throws IOException {
        return Protocol.positive(elm.obd(pidRequest(pid)),ECU,0x41,pid.id);
    }

    static String pidRequest(Protocol.Pid pid){
        return String.format(Locale.ROOT,"01%02X",pid.id);
    }
    /** Restores standard CAN framing after the shared engine TP2.0 reader. */
    void prepareForPolling() throws IOException {
        elm.ok("ATSP6");
        elm.ok("ATV0"); // TP2.0 uses variable lengths; standard OBD uses eight data bytes.
        elm.ok("ATCAF1");
        elm.ok("ATCFC1");
        elm.ok("ATAT1");elm.ok("ATST64");
        elm.isoPhysical("7E0",ECU);
    }
    private void readStatus() throws IOException {
        if(!supported.contains(1))return;
        byte[] d=Protocol.positive(elm.obd("0101"),ECU,0x41,1);
        if(d!=null&&d.length>=4) {
            session.put("mil","Лампа Check Engine","Состояние",(Protocol.u(d[0])&128)!=0?"Включена":"Выключена","","Статус ЭБУ");
            session.put("dtccount","Число сохранённых ошибок","Состояние",Integer.toString(Protocol.u(d[0])&127),"шт.","Это число из статуса OBD, не полный опрос всех блоков");
        }
    }
    void poll() throws IOException {
        boolean dashboard=session.dashboard!=null;
        boolean any=false;
        // Keep the calculated-power card available even when tracking is off,
        // otherwise there is no way to turn the calculation back on.
        clearPowerInputs();
        showPower(Double.NaN);
        showLambdaPower();
        showFuelPower();
        for(Protocol.Pid p:Protocol.PIDS) {
            if(!session.isCurrent())return;
            if(!shouldPoll(p,dashboard,session::isEnabled))continue;
            String cmd=pidRequest(p);
            if(!session.analyzing.getAsBoolean()&&!availability.ready(cmd))continue;
            any=true;
            byte[] d=readSample(p);
            if(d==null||!p.completeLength(d.length)){
                long delay=availability.failed(cmd);
                session.log.record("measurement_unavailable",cmd,"Нет полного ответа; retry_ms="+delay,0);
                session.put("pid"+p.id,p.name,p.group,"—","","Нет данных / неполный ответ");
                updatePowerInput(p.id,Double.NaN);
            }
            else {
                availability.success(cmd);
                Protocol.Reading v=p.decode(d);
                String note=VehicleData.obdDescription(p.id);
                if(note.isEmpty())note="OBD "+cmd;
                session.put("pid"+p.id,p.name,p.group,DiagnosticSession.format(v.value),v.unit,note);
                updatePowerInput(p.id,v.value);
                if(p.id==0x34&&session.isEnabled("oxygenCurrent"))session.put("oxygenCurrent","Ток передней лямбды","Смесь и топливо",
                    DiagnosticSession.format((Protocol.u(d[2])*256+Protocol.u(d[3]))/256.0-128),"мА",note);
            }
            DiagnosticSession.pause(DiagnosticSession.OBD_REQUEST_PAUSE_MS);
        }
        if(!session.isCurrent())return;
        if((dashboard||filter.equals("Основные")||filter.equals("Все"))&&session.isEnabled("adapterV")) {
            if(dashboard)any=true;
            String volts=elm.at("ATRV").replaceAll("[\r\n> ]","");
            if(volts.matches("[0-9]+(?:\\.[0-9]+)?V"))session.put("adapterV","Напряжение на адаптере","Основные",volts.substring(0,volts.length()-1),"В","Измеряет Viecar; может отличаться от напряжения ЭБУ");
            if(dashboard)DiagnosticSession.pause(DiagnosticSession.OBD_REQUEST_PAUSE_MS);
        }
        if(!dashboard){
            boolean statusSelected=filter.equals("Состояние")||filter.equals("Все");
            boolean statusDue=session.analyzing.getAsBoolean()||System.nanoTime()/1_000_000>=nextStatusPoll;
            if(readErrors||statusDue&&filter.equals("Состояние"))readStatus();
            if(statusSelected&&statusDue){
                readExtraObd(!session.analyzing.getAsBoolean());
                nextStatusPoll=System.nanoTime()/1_000_000+10_000;
            }
            if(readErrors){readErrors=false;readDtcs();}
        }
        if(dashboard){
            if(!any)DiagnosticSession.pause(200);
        }else DiagnosticSession.pause(any?100:800);
    }
    private byte[] readSample(Protocol.Pid pid)throws IOException{
        if(!fastPollingDisabled&&singleFramePids.contains(pid.id)){
            byte[] data=Protocol.positive(elm.singleFramePid(pid),ECU,0x41,pid.id);
            if(data!=null&&pid.completeLength(data.length))return data;
            // The fast form failed. A normal read distinguishes a clone that
            // rejects the response-count suffix from a brief post-TP2.0 gap
            // where the ECU ignores both forms. Only the former disables fast
            // polling; a gap keeps it armed and backs the PID off instead.
            byte[] normal=normalSample(pid);
            if(normal!=null&&pid.completeLength(normal.length)){
                fastPollingDisabled=true;
                session.log.record("obd_fast_fallback",pidRequest(pid),"Неполный ответ; обычный режим до следующего подключения",0);
            }
            return normal;
        }
        return normalSample(pid);
    }
    private byte[] normalSample(Protocol.Pid pid)throws IOException{
        String response=elm.obd(pidRequest(pid));
        byte[] data=Protocol.positive(response,ECU,0x41,pid.id);
        List<Protocol.Frame> frames=Protocol.frames(response);
        if(data!=null&&pid.completeLength(data.length)&&data.length<=5&&frames.size()==1&&frames.get(0).id.equals(ECU)
            &&(Protocol.u(frames.get(0).data[0])==data.length+2||Protocol.u(frames.get(0).data[0])==0x41))singleFramePids.add(pid.id);
        return data;
    }
    void probeFastPolling()throws IOException{
        for(Protocol.Pid pid:Protocol.PIDS){
            if(!supported.contains(pid.id)||pid.size>5)continue;
            byte[] baseline=readSample(pid);
            if(baseline==null||!pid.completeLength(baseline.length)||!singleFramePids.contains(pid.id))continue;
            byte[] quick=readSample(pid);
            if(fastPollingDisabled||quick==null||!pid.completeLength(quick.length))throw new IOException("Ускоренное чтение не подтвердилось; обычное чтение сохранено");
            session.log.record("obd_fast_verified",String.format(Locale.ROOT,"01%02X",pid.id),"Полный одиночный ответ от 7E8",0);
            return;
        }
        throw new IOException("Нет полного одиночного ответа для проверки скорости OBD");
    }
    void probeSecondaryTrim()throws IOException{
        for(Protocol.Pid pid:Protocol.PIDS)if(pid.id==0x56){
            if(!supported.contains(pid.id))throw new IOException("PID 56 не заявлен блоком двигателя");
            byte[] data=readStandardPid(pid);
            if(data==null||!pid.completeLength(data.length))throw new IOException("PID 56 не передал коррекцию ряда 1");
            Protocol.Reading value=pid.decode(data);
            session.put("pid"+pid.id,pid.name,pid.group,DiagnosticSession.format(value.value),value.unit,"OBD 0156 · байт A, ряд 1");
        }
    }
    void showPower(double maf){
        double kw=session.isEnabled("maf_power")?VehicleData.mafPowerKw(maf):Double.NaN;
        session.put("maf_power",VehicleData.POWER_NAME,"Основные",powerValue(kw),"",VehicleData.POWER_NOTE);
    }
    /** Confirms the main power estimate and samples optional OBD sources when the ECU provides them. */
    void probePowerEstimates()throws IOException{
        Protocol.Pid airflow=pid(0x10);
        if(airflow==null||!supported.contains(airflow.id))throw new IOException("Двигатель не заявил массовый расход воздуха OBD 0110");
        byte[] data=readStandardPid(airflow);
        if(data==null||!airflow.completeLength(data.length))throw new IOException("Расходомер не вернул показание");
        updatePowerInput(airflow.id,airflow.decode(data).value);
        for(int source:new int[]{lambdaPowerPid(),0x5E}){
            Protocol.Pid parameter=pid(source);
            if(parameter==null||!supported.contains(source))continue;
            data=readStandardPid(parameter);
            if(data==null||!parameter.completeLength(data.length)){
                session.log.record("measurement_unavailable",String.format(Locale.ROOT,"01%02X",source),"Необязательный источник расчёта мощности не ответил",0);
                continue;
            }
            updatePowerInput(source,parameter.decode(data).value);
        }
    }
    private void showLambdaPower(){
        int source=lambdaPowerPid();
        double kw=session.isEnabled("lambda_power")?VehicleData.lambdaPowerKw(powerAirflow,powerLambda):Double.NaN;
        String sourceNote=source==0x24?"Источник λ: передний широкополосный датчик OBD 0124."
            :source==0x34?"Источник λ: передний широкополосный датчик OBD 0134."
            :source==0x44?"Источник λ: заданная ЭБУ смесь OBD 0144, не измеренная лямбда."
            :"ЭБУ не заявил доступный источник λ по стандартному OBD.";
        session.put("lambda_power",VehicleData.LAMBDA_POWER_NAME,"Основные",powerValue(kw),"",VehicleData.LAMBDA_POWER_NOTE+" "+sourceNote);
    }
    private void showFuelPower(){
        double kw=session.isEnabled("fuel_power")?VehicleData.fuelRatePowerKw(powerFuelRate):Double.NaN;
        String sourceNote=supported.contains(0x5E)?"Источник: расчётный расход топлива ЭБУ OBD 015E."
            :"ЭБУ не заявил мгновенный расход топлива OBD 015E.";
        session.put("fuel_power",VehicleData.FUEL_POWER_NAME,"Основные",powerValue(kw),"",VehicleData.FUEL_POWER_NOTE+" "+sourceNote);
    }
    private static String powerValue(double kw){
        return Double.isFinite(kw)?DiagnosticSession.format(kw)+" кВт · "+DiagnosticSession.format(kw/.73549875)+" л.с.":"—";
    }
    private void clearPowerInputs(){
        powerAirflow=Double.NaN;powerLambda=Double.NaN;powerFuelRate=Double.NaN;
    }
    private int lambdaPowerPid(){
        for(int pid:new int[]{0x24,0x34,0x44})if(supported.contains(pid))return pid;
        return -1;
    }
    static Protocol.Pid pid(int id){
        for(Protocol.Pid pid:Protocol.PIDS)if(pid.id==id)return pid;
        return null;
    }
    private boolean needsCalculatedPower(Protocol.Pid pid,Predicate<String> enabled){
        if(pid.id==0x10)return enabled.test("maf_power")||enabled.test("lambda_power");
        if(pid.id==lambdaPowerPid())return enabled.test("lambda_power");
        return pid.id==0x5E&&enabled.test("fuel_power");
    }
    private void updatePowerInput(int pid,double value){
        if(pid==0x10){powerAirflow=value;showPower(value);showLambdaPower();return;}
        if(pid==lambdaPowerPid()){powerLambda=value;showLambdaPower();return;}
        if(pid==0x5E){powerFuelRate=value;showFuelPower();}
    }
    void probeExtra()throws IOException{
        boolean decoded=readExtraObd(false);
        for(int pid:new int[]{0x34,0x56}){
            if(!session.isCurrent())return;
            if(!supported.contains(pid))continue;
            byte[] data=Protocol.positive(elm.obd(String.format(Locale.ROOT,"01%02X",pid)),ECU,0x41,pid);
            decoded|=data!=null&&data.length>=(pid==0x34?4:1);
        }
        if(!decoded)throw new IOException("Новые параметры OBD пока не подтвердились");
    }
    private boolean readExtraObd(boolean live)throws IOException{
        boolean decoded=false;
        for(int pid:new int[]{3,0x13,0x1C,0x41}){
            if(!session.isCurrent())return decoded;
            if(!supported.contains(pid))continue;
            if(live&&staticStatusRead.contains(pid))continue;
            String cmd=String.format(Locale.ROOT,"01%02X",pid);
            byte[] d=Protocol.positive(elm.obd(cmd),ECU,0x41,pid);
            if(d==null||d.length<(pid==3?2:pid==0x41?4:1))continue;
            if(live&&(pid==0x13||pid==0x1C))staticStatusRead.add(pid);
            decoded=true;
            if(pid==3)session.put("fuelStatus","Режим топливной системы","Состояние",fuelStatus(Protocol.u(d[0])),"","OBD "+cmd);
            if(pid==0x13){
                List<String> sensors=new ArrayList<>();
                for(int i=0;i<4;i++)if((Protocol.u(d[0])&(1<<i))!=0)sensors.add("B1S"+(i+1));
                session.put("oxygenLayout","Лямбда-зонды · ряд 1","Состояние",sensors.isEmpty()?"Не заявлены":String.join(", ",sensors),"","OBD "+cmd);
            }
            if(pid==0x1C)session.put("obdStandard","Стандарт OBD","Состояние",Protocol.u(d[0])==6?"EOBD":String.format(Locale.ROOT,"Код %02X",d[0]),"","OBD "+cmd);
            if(pid==0x41)session.put("cycleMonitors","Мониторы текущего цикла","Состояние",cycleMonitors(d),"","OBD "+cmd);
        }
        return decoded;
    }

    private static String fuelStatus(int code){
        switch(code){
            case 1:return "Без обратной связи · прогрев";
            case 2:return "По сигналу лямбда-зонда";
            case 4:return "Без обратной связи · нагрузка или отсечка";
            case 8:return "Без обратной связи · неисправность";
            case 16:return "С обратной связью · неисправность";
            default:return String.format(Locale.ROOT,"Статус %02X",code);
        }
    }

    private static String cycleMonitors(byte[] d){
        int support=Protocol.u(d[1])&7,incomplete=(Protocol.u(d[1])>>>4)&7;
        int finished=Integer.bitCount(support&~incomplete)+Integer.bitCount(Protocol.u(d[2])&~Protocol.u(d[3]));
        int total=Integer.bitCount(support)+Integer.bitCount(Protocol.u(d[2]));
        return total==0?"Нет заявленных":"Завершено "+finished+" из "+total;
    }
    void readDtcs() throws IOException {
        for(String mode:new String[]{"03","07","0A"}) {
            if(session.stopped.getAsBoolean())return;
            byte[] response=Protocol.messages(elm.obd(mode)).get(ECU);
            int expected=Integer.parseInt(mode,16)+0x40;
            String title=mode.equals("03")?"Сохранённые ошибки":mode.equals("07")?"Ожидающие ошибки":"Постоянные ошибки";
            if(response==null||Protocol.u(response[0])!=expected)session.put("dtc"+mode,title,"Состояние","Нет ответа","","Не означает отсутствие ошибок");
            else {
                List<String> codes=Protocol.dtcs(response);
                session.put("dtc"+mode,title,"Состояние",codes.isEmpty()?"Не обнаружены":String.join(", ",codes),"","Только двигатель OBD. Стирание отсутствует.");
            }
        }
    }
    void clearDtcs() throws IOException {
        byte[] response=Protocol.messages(elm.clearObdFaults()).get(ECU);
        if(response==null||Protocol.u(response[0])!=0x44)throw new IOException("Двигатель не подтвердил удаление ошибок");
        session.put("faults","Ошибки · Двигатель","Состояние","Удалены","","Удалены сохранённые OBD-ошибки; контроль готовности двигателя сброшен.");
    }
}
