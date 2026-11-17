package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

/** Owns the TP2.0 channel and reads vehicle-specific groups and snapshots. */
final class VagReader {
    private final DiagnosticSession session;
    private final Elm elm;
    private final BlockInfoReader info;
    private Tp20 tp;
    private List<Integer> groups=new ArrayList<>();
    private final List<Integer> chassisGroups=new ArrayList<>();
    private final ReadAvailability availability=new ReadAvailability();
    private boolean absMk60Ec1,triedEngineKwpIdentity;
    private boolean lastGroupHadRawValues;
    private boolean fastWaitActive,fastWaitDisabled;

    VagReader(DiagnosticSession session,BlockInfoReader info){
        this.session=session;
        this.elm=session.elm;
        this.info=info;
    }

    boolean hasChannel(){return tp!=null;}
    boolean hasMeasurements(){return !groups.isEmpty();}
    void clearMeasurements(){groups=Collections.emptyList();}
    boolean hasChassisMeasurements(){return !chassisGroups.isEmpty();}
    boolean hasPendingMeasurements(){
        for(int group:groups)
            if(hasEnabledFields(group)&&availability.ready(VehicleData.request(group)))return true;
        return false;
    }

    static final class NoMeasurements extends IOException {
        final boolean unsupported;
        NoMeasurements(String message){this(message,false);}
        NoMeasurements(String message,boolean unsupported){super(message);this.unsupported=unsupported;}
    }

    void close(){if(tp!=null){tp.close();tp=null;}fastWaitActive=false;}
    void open()throws IOException{
        int address=DiagnosticBlock.forMode(session.mode).tpAddress;
        tp=new Tp20(elm,this::pauseBeforeChannelRetry);
        try{tp.open(address);}
        catch(IOException e){
            close();
            if(session.wireLost()||!session.isCurrent()||address!=1||e instanceof Tp20.ChannelBusy)throw e;
            session.log.record("tp20_retry","ECM","Проверка открытия канала с одним ответом адаптера",0);
            tp=new Tp20(elm,this::pauseBeforeChannelRetry);
            try{tp.open(address,true);}catch(IOException retry){close();throw retry;}
        }
    }
    private void pauseBeforeChannelRetry(int millis)throws IOException {
        for(int remaining=millis;remaining>0;remaining-=100){
            if(!session.isCurrent()||session.wireLost())throw new IOException("Открытие канала прервано");
            DiagnosticSession.pause(Math.min(remaining,100));
        }
        if(!session.isCurrent()||session.wireLost())throw new IOException("Открытие канала прервано");
    }
    void initialize() throws IOException {
        clearMeasurements();availability.clear();
        if(session.mode.equals("DSG"))info.read("DSG");
        else if(engineMeasurements()){
            triedEngineKwpIdentity=false;
            info.publishEngineIdentity();
        }
        open();
        groups=VehicleData.groups(session.mode);
        if(session.mode.equals("DSG")&&!info.hasPartNumber()){
            try{info.readKwpIdentity(tp);}catch(IOException e){if(session.wireLost())throw e;close();open();}
        }
        session.put("vag_notice","Проверка на машине","Информация","Экспериментально","","Единицы берутся из ответа блока; назначение групп нужно подтвердить по его версии.");
    }

    void prepareCombinedMeasurements(){
        availability.clear();
        groups=VehicleData.groups(session.mode);
        triedEngineKwpIdentity=false;
        info.publishEngineIdentity();
    }
    boolean readBlockFaults()throws IOException{
        String mode=session.mode, label=VehicleData.modeName(mode);
        if(tp!=null){
            setLiveWait(false);
            return showFaults(label,Protocol.kwpDtcs(tp.request("1800FF00")));
        }
        elm.ok("ATSP6");
        elm.ok("ATCAF1");
        elm.ok("ATCFC1");
        DiagnosticBlock block=DiagnosticBlock.forMode(mode);
        elm.isoPhysical(block.tx,block.rx);
        List<String> codes=Protocol.udsDtcs(Protocol.messages(elm.udsFaults()).get(block.rx));
        if(codes==null&&!mode.equals("HALDEX")){
            try{
                open();
                codes=Protocol.kwpDtcs(tp.request("1800FF00"));
            }catch(IOException e){if(session.wireLost())throw e;}
            finally{close();}
        }
        return showFaults(label,codes);
    }
    void clearBlockFaults()throws IOException{
        DiagnosticBlock block=DiagnosticBlock.forMode(session.mode);
        elm.ok("ATSP6");elm.ok("ATCAF1");elm.ok("ATCFC1");
        elm.isoPhysical(block.tx,block.rx);
        byte[] response=Protocol.messages(elm.clearUdsFaults()).get(block.rx);
        if(response==null||Protocol.u(response[0])!=0x54)throw new IOException("Блок не подтвердил удаление ошибок по UDS");
        session.put("faults","Ошибки · "+VehicleData.modeName(session.mode),"Состояние","Удалены","","Удалены ошибки выбранного блока; повторно прочитайте память ошибок.");
    }
    boolean readSteering()throws IOException{
        chassisGroups.clear();availability.clear();
        Map<String,String> identity=info.read("EPS");
        if(!session.isCurrent())return false;
        try{open();}
        catch(IOException e){if(session.wireLost()||identity.isEmpty())throw e;return false;}
        String profile=(String.join(" ",identity.values())+" "+info.readKwpIdentity(tp)).toUpperCase(Locale.ROOT);
        boolean epsProfile=profile.contains("LENK")||profile.contains("STEER")||profile.contains("1K0909144")
            ||profile.contains("5N1909144")||profile.contains("EPS");
        if(!epsProfile)return false;
        for(int group:VehicleData.groups("EPS")){
            if(!session.isCurrent())break;
            if(session.dashboard!=null&&!hasEnabledFields(group))continue;
            try{if(readGroup(group))chassisGroups.add(group);}catch(NoMeasurements ignored){}
        }
        return hasChassisMeasurements();
    }
    void probeDsgFaults()throws IOException{
        elm.init();open();
        if(!showFaults("Коробка DQ250",Protocol.kwpDtcs(tp.request("1800FF00"))))
            throw new IOException("Коробка не подтвердила чтение ошибок");
    }
    void probeIdentity()throws IOException{
        info.publishIdentity(session.mode,Collections.emptyMap());
        open();info.readKwpIdentity(tp);
        if(!info.hasPartNumber()||!info.hasSoftwareVersion())throw new IOException("KWP не передал номер детали и версию ПО");
    }
    boolean showFaults(String block,List<String> codes){
        String value=codes==null?"Нет подтверждённого ответа":codes.isEmpty()?"Не обнаружены":String.join(", ",codes);
        session.put("faults","Ошибки · "+block,"Состояние",value,"","Чтение памяти блока");
        return codes!=null;
    }

    boolean readAbs()throws IOException{
        chassisGroups.clear();availability.clear();
        Map<String,String> identity=info.read("ABS");
        if(!session.isCurrent())return false;
        boolean uds=session.dashboard==null&&showFaults("ABS/ESP",Protocol.udsDtcs(Protocol.messages(elm.udsFaults()).get("77D")));
        String variant=String.join(" ",identity.values()).toUpperCase(Locale.ROOT);
        if(variant.contains("MK100")){
            session.put("abs_profile","Профиль ABS/ESP","Информация","MK100 · UDS","","Для измерений требуется карта именно этого блока");
            return false;
        }
        try{open();}
        catch(IOException e){
            if(session.wireLost()||!uds&&identity.isEmpty())throw e;
            session.put("abs_profile","Профиль ABS/ESP","Информация","Идентификация сохранена","","Группы KWP не открылись");
            return false;
        }
        variant=(variant+" "+info.readKwpIdentity(tp)).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        absMk60Ec1=variant.contains("MK60EC1");
        if(session.dashboard==null&&!uds)showFaults("ABS/ESP",Protocol.kwpDtcs(tp.request("1800FF00")));
        session.put("abs_profile","Профиль ABS/ESP","Информация",absMk60Ec1?"MK60EC1":"Версия требует сверки","","Снимок измерений в обычном сеансе");
        boolean decoded=true;
        for(int group:VehicleData.groups("ABS")){
            if(!session.isCurrent())return false;
            if(session.dashboard!=null&&!hasEnabledFields(group))continue;
            try{
                if(readGroup(group))chassisGroups.add(group);
                else decoded=false;
            }
            catch(NoMeasurements ignored){decoded=false;}
        }
        return decoded;
    }

    boolean pollChassis()throws IOException{
        boolean updated=false,requestedAny=false;
        if(!hasChassisMeasurements()||!session.isCurrent())return false;
        for(int group:chassisGroups){
            if(!session.isCurrent())break;
            String request=VehicleData.request(group);
            if(!hasEnabledFields(group)||!availability.ready(request))continue;
            if(!hasChannel())open();
            requestedAny=true;
            try{updated|=readGroup(group);availability.success(request);}
            catch(NoMeasurements unavailable){
                long delay=availability.failed(request);
                session.log.record("live_group_unavailable",request,unavailable.getMessage()+"; retry_ms="+delay,0);
            }
            // TP2.0 send() already enforces the separation negotiated with this ECU.
        }
        if(!requestedAny){close();DiagnosticSession.pause(200);}
        return updated;
    }

    void readGateway()throws IOException{
        Map<String,String> identity=info.read("GATEWAY");
        try{
            open();
            byte[] data=tp.request("1A9F");
            List<Integer> installed=Protocol.gatewayModules(data);
            if(installed==null||installed.isEmpty())throw new IOException("Шлюз не вернул список установленных блоков");
            for(int module:installed){
                String address=String.format(Locale.ROOT,"%02X",module);
                session.put("module"+address,VehicleData.moduleName(address),"Информация","Установлен","","Адрес "+address);
            }
        }catch(IOException e){
            if(!session.wireLost()&&!identity.isEmpty())session.put("gateway_profile","CAN-шлюз","Информация","Идентификация получена","","Список комплектации ещё не прочитан");
            throw e;
        }
    }
    void poll() throws IOException {
        List<Integer> unsupported=new ArrayList<>();
        boolean requestedAny=false;
        boolean useFastWait=engineMeasurements()&&!fastWaitDisabled&&!session.analyzing.getAsBoolean()
            &&(session.dashboard!=null||triedEngineKwpIdentity||info.hasPartNumber())
            &&groups.stream().noneMatch(group->group!=115&&group!=93&&hasEnabledFields(group));
        for(int group:groups){
            if(!session.isCurrent())return;
            String request=VehicleData.request(group);
            if(!hasEnabledFields(group)||!availability.ready(request))continue;
            if(!hasChannel())open();
            boolean testedGroup=group==115||group==93;
            if(!requestedAny||fastWaitActive&&!testedGroup){
                try{setLiveWait(useFastWait&&testedGroup);}
                catch(IOException e){
                    if(session.wireLost()||!session.isCurrent())throw e;
                    restoreNormalWait(e);
                }
            }
            requestedAny=true;
            try{readGroup(group);availability.success(request);}
            catch(IOException e){
                if(e instanceof NoMeasurements&&(!fastWaitActive||((NoMeasurements)e).unsupported)){
                    unavailable(group,(NoMeasurements)e,unsupported);
                    continue;
                }
                if(session.wireLost()||!session.isCurrent())throw e;
                session.log.record("vag_group_retry",VehicleData.request(group),e.getMessage(),0);
                if(fastWaitActive)restoreNormalWait(e);
                else{close();open();}
                try{readGroup(group);availability.success(request);}
                catch(NoMeasurements e2){unavailable(group,e2,unsupported);}
            }
        }
        groups.removeAll(unsupported);
        if(!requestedAny){close();DiagnosticSession.pause(200);return;}
        if(groups.isEmpty()){
            close();
            session.state.status("Блок доступен, выбранные группы не поддерживаются. Журнал сохранён.",true);
        }
    }

    private void setLiveWait(boolean fast)throws IOException{
        if(fast==fastWaitActive)return;
        elm.ok(fast?"ATST0A":"ATST18");
        fastWaitActive=fast;
        session.log.record("tp20_live_wait","ECM",fast?"40 ms":"96 ms",0);
    }

    private void restoreNormalWait(IOException failure)throws IOException{
        fastWaitDisabled=true;
        session.log.record("tp20_live_wait_fallback","ECM",failure.getMessage(),0);
        close();open();
    }

    private void unavailable(int group,NoMeasurements failure,List<Integer> unsupported){
        String request=VehicleData.request(group);
        if(failure.unsupported)unsupported.add(group);
        long delay=failure.unsupported?0:availability.failed(request);
        session.log.record("measurement_unavailable",request,failure.getMessage()+"; retry_ms="+delay,0);
    }

    boolean readGroup(int group)throws IOException{
        lastGroupHadRawValues=false;
        String request=VehicleData.request(group);
        byte[] response=tp.request(request);
        if(response.length==3&&Protocol.u(response[0])==0x7F&&Protocol.u(response[1])==0x21){
            int code=Protocol.u(response[2]);
            throw new NoMeasurements(String.format(Locale.ROOT,"Группа %d: отказ блока 7F21%02X",group,code),code==0x11||code==0x12||code==0x31);
        }
        List<KwpMeasurements.Field> measurements=KwpMeasurements.parse(response,group);
        if(measurements==null)throw new NoMeasurements("Группа "+group+": неполный или неизвестный формат "+Protocol.hex(response));
        int decodedMask=0;
        String unreadable="";
        String catalogMode=engineMeasurements()?"ECM":session.mode;
        int fields=measurements.size();
        boolean knownProfile=!session.mode.equals("ABS")||absMk60Ec1||KwpMeasurements.knownAbsLayout(group,measurements);
        for(int i=0;i<fields;i++){
            VehicleData.Field field=knownProfile?VehicleData.field(catalogMode,group,i):null;
            if(field==null)continue;
            if(session.dashboard==null&&!session.isEnabled("g"+group+"f"+i))continue;
            KwpMeasurements.Field sample=measurements.get(i);
            int type=sample.type;
            Protocol.Reading reading=sample.data.length==2?VehicleData.reading(catalogMode,group,i,type,sample.a(),sample.b()):null;
            String name=field.name;
            String note=field.description;
            if(reading!=null&&!field.unit.isEmpty()&&!field.unit.equals(reading.unit)){
                unreadable=sample.raw();
                lastGroupHadRawValues=true;
                session.put("g"+group+"f"+i,name,"Измерения",unreadable,"",rawNote(note,unreadable));
                continue;
            }
            String text=sample.text();
            if(reading==null&&(text.isEmpty()||!field.unit.isEmpty())){
                unreadable=sample.raw();
                lastGroupHadRawValues=true;
                session.put("g"+group+"f"+i,name,"Измерения",unreadable,"",rawNote(note,unreadable));
                continue;
            }
            session.put("g"+group+"f"+i,name,"Измерения",reading==null?text:DiagnosticSession.format(reading.value),reading==null?"":reading.unit,note);
            decodedMask|=1<<i;
        }
        if(engineMeasurements()&&session.dashboard==null&&!session.analyzing.getAsBoolean()&&!triedEngineKwpIdentity&&!info.hasPartNumber()&&decodedMask!=0){
            triedEngineKwpIdentity=true;
            try{info.readKwpIdentity(tp);}catch(IOException e){if(session.wireLost())throw e;close();open();}
        }
        int required=VehicleData.requiredFields(catalogMode,group);
        return required==0?decodedMask!=0:(decodedMask&required)==required;
    }
    boolean lastGroupHadRawValues(){return lastGroupHadRawValues;}
    private static String rawNote(String note,String raw){
        String prefix=note.isEmpty()?"":note+" ";
        return prefix+"Не расшифровано: "+raw+". Сырые байты сохранены для анализа формата.";
    }
    private boolean engineMeasurements(){return session.mode.equals("ECM")||session.mode.equals(Engine.COMBINED_ENGINE);}
    private boolean hasEnabledFields(int group){
        String catalogMode=engineMeasurements()?"ECM":session.mode;
        for(int index=0;index<4;index++){
            if(VehicleData.field(catalogMode,group,index)!=null&&session.isEnabled("g"+group+"f"+index))return true;
        }
        return false;
    }
}
