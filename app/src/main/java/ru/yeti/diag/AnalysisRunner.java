package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

/** Defines full and partial checks and records their outcomes. */
final class AnalysisRunner {
    private static final String POLLING_BENCHMARK_KEY="polling_pause_comparison";
    private final DiagnosticSession session;
    private final Elm elm;
    private final Engine.PartialProgress partialProgress;
    private final ObdReader obd;
    private final VagReader vag;
    private final HaldexReader haldex;
    private final BlockInfoReader info;
    private final BlockConfigurationReader configuration;

    AnalysisRunner(DiagnosticSession session,Engine.PartialProgress partialProgress,ObdReader obd,
                   VagReader vag,HaldexReader haldex,BlockInfoReader info,BlockConfigurationReader configuration){
        this.session=session;
        this.elm=session.elm;
        this.partialProgress=partialProgress;
        this.obd=obd;
        this.vag=vag;
        this.haldex=haldex;
        this.info=info;
        this.configuration=configuration;
    }

    private interface Probe { void run() throws IOException; }

    /**
     * Read-only diagnostic sweep. Every strategy is logged separately so a
     * later comparison can show exactly where the ECU stopped responding.
     * No coding, adaptation or actuator command is sent here.
     */
    void runFull(PollingBenchmark benchmark) throws IOException {
        session.log.record("analysis","start","read-only sweep",0);
        String oldFilter=obd.filter;obd.filter="Все";obd.readErrors=true;
        try{
            boolean obdReady=probe("OBD","obd_all",()->{elm.init();obd.initialize();obd.poll();});
            check("OBD","extra_obd_status",true,()->probeExtraObd(obdReady));
        }
        finally{obd.filter=oldFilter;}
        checkGroups("ECM",VehicleData.groups("ECM"),true);
        checkGroups("DSG",VehicleData.groups("DSG"),true);
        check("DSG","dq250_faults",true,vag::probeDsgFaults);
        check("ABS","abs_readonly",true,()->{elm.init();if(!vag.readAbs())throw new IOException("ABS ответил, карта измерений пока не подтверждена");});
        check("GATEWAY","gateway_installation",true,()->{elm.init();vag.readGateway();});
        probe("HALDEX","haldex5_all_dids",()->{elm.init();haldex.initialize();haldex.poll();});
        checkHaldexNew(true);
        checkNewBlockFunctions(true);
        checkPollingBenchmark(true,benchmark);
        if(session.isCurrent())session.log.record("analysis","finish","read-only sweep complete",0);
    }

    void runPartial(PollingBenchmark benchmark) throws IOException {
        session.log.record("analysis","start","partial read-only sweep",0);
        checkGroups("ECM",integers(VehicleData.ENGINE_PRIORITY),false);
        checkGroups("DSG",integers(VehicleData.DSG_NEW),false);
        check("DSG","dq250_faults",false,vag::probeDsgFaults);
        check("ABS","abs_readonly",false,()->{elm.init();if(!vag.readAbs())throw new IOException("ABS ответил, карта измерений пока не подтверждена");});
        check("GATEWAY","gateway_installation",false,()->{elm.init();vag.readGateway();});
        checkHaldexNew(false);
        check("OBD","extra_obd_status",false,()->probeExtraObd(false));
        checkNewBlockFunctions(false);
        checkPollingBenchmark(false,benchmark);
        if(session.isCurrent())session.log.record("analysis","finish","partial sweep complete",0);
    }
    private void comparePollingPauses(PollingBenchmark benchmark)throws IOException{
        if(!session.isCurrent())return;
        session.mode="OBD";
        session.state.status("Анализ · сравнение пауз OBD",false);
        benchmark.run(session,obd);
    }
    /** The short analysis retries this only until it has a successful comparison. */
    private void checkPollingBenchmark(boolean full,PollingBenchmark benchmark)throws IOException{
        if(!full&&partialProgress.isVerified(POLLING_BENCHMARK_KEY)){
            session.log.record("analysis_skip","OBD",POLLING_BENCHMARK_KEY+" already verified",0);
            return;
        }
        comparePollingPauses(benchmark);
        if(benchmark.confirmed())partialProgress.markVerified(POLLING_BENCHMARK_KEY);
    }
    private void checkNewBlockFunctions(boolean full)throws IOException{
        check("OBD","obd_single_frame",full,()->{elm.init();obd.readSupportedPids();obd.probeFastPolling();});
        check("OBD","obd_secondary_trim",full,()->{elm.init();obd.readSupportedPids();obd.probeSecondaryTrim();});
        check("OBD","live_value_retention_v13",full,this::checkLiveValueRetention);
        check("ECM","engine_protocol_switch_v2",full,this::checkProtocolSwitch);
        check("ECM","tp20_ack_wait_comparison",full,()->new Tp20WaitBenchmark(session,vag).run());
        check("ECM","obd_tp20_recovery",full,()->new ObdRecoveryBenchmark(session,vag,obd).run());
        check("ECM","power_tp20_groups",full,()->{
            elm.init();vag.open();
            boolean airflow=vag.readGroup(3);
            boolean lambda=vag.readGroup(31);
            if(!airflow||!lambda)throw new IOException("Группы 3/31 не подтвердили расход воздуха и лямбду для расчёта мощности");
        });
        for(String block:new String[]{"ECM","DSG","EPS"})
            check(block,"identity_kwp_fields_"+block,full,()->{elm.init();vag.probeIdentity();});
        check("OBD","android_auto_live_refresh",full,this::checkDashboardSamples);
        check("OBD","android_auto_layout_motion_v1",full,this::checkDashboardPresentation);
        check("OBD","android_auto_connection_policy_v1",full,this::checkDashboardConnection);
        check("ECM","raw_measurement_capture",full,this::checkRawMeasurementCapture);
        check("EPS","steering_values",full,()->{elm.init();if(!vag.readSteering())throw new IOException("Измерения усилителя руля ещё не подтверждены");});
        for(String block:new String[]{"ABS","EPS"}){
            check(block,"live_groups_v12_"+block,full,()->{
                elm.init();
                if(block.equals("ABS"))vag.readAbs();else vag.readSteering();
                if(!vag.pollChassis())throw new IOException("Повторный проход не вернул измерения");
            });
        }
        UdsSetup identitySetup=new UdsSetup();
        for(String block:new String[]{"OBD","DSG","ABS","EPS","GATEWAY","HALDEX"}){
            String key="identity_v10_"+block;
            check(block,key,full,()->{
                Map<String,String> values=identitySetup.read(key,()->info.read(block));
                if(!values.keySet().containsAll(Arrays.asList(VehicleData.CORE_ID_FIELDS)))
                    throw new IOException("Блок ответил не на все запросы идентификации");
            });
        }
        for(String block:new String[]{"ECM","EPS","HALDEX","GATEWAY"}){
            check(block,"faults_v10_"+block,full,()->{
                elm.init();if(!vag.readBlockFaults())throw new IOException("Блок не подтвердил чтение памяти ошибок");
            });
        }
        check("OBD","power_estimates_v12",full,()->{elm.init();obd.initialize();obd.probePowerEstimates();});
        checkConfigurations(full);
    }

    private void checkLiveValueRetention()throws IOException{
        Engine.Row successful=new Engine.Row("retention_probe","Проверка удержания","Измерения","1","","");
        Engine.Row missing=new Engine.Row("retention_probe","Проверка удержания","Измерения","—","","Нет данных / неполный ответ");
        if(MeasurementRetention.keepLastSuccessful(successful,missing)!=successful)
            throw new IOException("Временный пропуск ответа заменил последнее измерение");
        session.put("live_value_retention","Удержание последнего значения","Анализ","Проверено","",
            "Временный неполный ответ не заменяет последнее успешно расшифрованное значение.");
    }

    private void checkDashboardConnection()throws IOException{
        DashboardConnection retry=new DashboardConnection();
        if(retry.shouldStart(0,true,true,true,true)
            ||retry.shouldStart(0,false,false,true,true)
            ||retry.shouldStart(0,false,true,false,true)
            ||retry.shouldStart(0,false,true,true,false)
            ||!retry.shouldStart(0,false,true,true,true)
            ||retry.shouldStart(1,false,true,true,true)
            ||!retry.shouldStart(DashboardConnection.RETRY_MS,false,true,true,true))
            throw new IOException("Условия восстановления подключения панели не подтверждены");
        session.put("android_auto_connection_policy","Восстановление связи панели","Анализ","Расчёт проверен","",
            "Проверены разрешения, выбранный адаптер, явное отключение и интервал повторов. Подключение с экрана автомобиля проверяется отдельно.");
    }

    private void checkProtocolSwitch()throws IOException{
        elm.init();
        vag.open();
        if(!vag.readGroup(115))throw new IOException("Нет исходного ответа расширенных параметров");
        vag.close();
        if(!session.isCurrent())return;
        obd.prepareForPolling();
        Protocol.Pid rpm=null;
        for(Protocol.Pid pid:Protocol.PIDS)if(pid.id==0x0C)rpm=pid;
        byte[] sample=obd.readStandardPid(rpm);
        if((sample==null||!rpm.completeLength(sample.length))&&session.isCurrent()){
            session.log.record("adapter_reuse_fallback","OBD","engine_protocol_switch_v2",0);
            elm.init();obd.readSupportedPids();
            if(!session.isCurrent())return;
            sample=obd.readStandardPid(rpm);
        }
        if(sample==null||sample.length!=rpm.size)throw new IOException("OBD не ответил после закрытия расширенного канала");
        if(!session.isCurrent())return;
        vag.open();
        if(!vag.readGroup(115))throw new IOException("Расширенный канал не восстановился после OBD");
    }

    /** Confirms that a live group is retained whether its fields decode or stay raw. */
    private void checkRawMeasurementCapture()throws IOException{
        elm.init();
        vag.open();
        boolean decoded=vag.readGroup(14);
        if(!decoded&&!vag.lastGroupHadRawValues())
            throw new IOException("Группа 014 не вернула ни расшифрованных значений, ни сырых байтов");
        session.put("raw_measurement_capture","Нечитаемые параметры","Измерения",
            vag.lastGroupHadRawValues()?"Сырые байты сохранены":"Формат расшифрован","",
            "Проверка сохранения ответа измерительной группы для последующей расшифровки.");
    }

    private void checkDashboardPresentation()throws IOException{
        if(DashboardTouch.cellAt(133,133,800,400)!=0||DashboardTouch.cellAt(666,266,800,400)!=5
            ||DashboardTouch.cellAt(400,133,800,400)!=1||DashboardTouch.cellAt(-1,-1,800,400)!=-1
            ||DashboardTouch.pageAction(0)!=-1||DashboardTouch.pageAction(3)!=-1
            ||DashboardTouch.pageAction(2)!=1||DashboardTouch.pageAction(5)!=1
            ||DashboardTouch.pageAction(1)!=0||DashboardTouch.pageAction(4)!=0
            ||DashboardTouch.shifted(0,-1)!=DashboardCatalog.PAGES-1
            ||DashboardTouch.shifted(DashboardCatalog.PAGES-1,1)!=0)
            throw new IOException("Жесты страниц полноэкранной панели не подтверждены");
        String[] boost=DashboardTouch.reading("1.03 бар","бар");
        String[] status=DashboardTouch.reading("Нет связи","бар");
        if(!boost[0].equals("1.03")||!boost[1].equals("бар")||!status[1].isEmpty())
            throw new IOException("Разделение значения и единицы прибора не подтверждено");
        DashboardNeedle needle=new DashboardNeedle();
        needle.moveTo(0,0);
        needle.moveTo(100,0);
        float half=needle.positionAt(DashboardNeedle.DURATION_MS/2);
        if(half!=50||needle.positionAt(DashboardNeedle.DURATION_MS)!=100)
            throw new IOException("Промежуточные положения шкалы не подтверждены");
        needle.moveTo(DashboardReadings.NO_SCALE,DashboardNeedle.DURATION_MS/2);
        if(needle.running(DashboardNeedle.DURATION_MS/2)||needle.positionAt(DashboardNeedle.DURATION_MS/2)!=DashboardReadings.NO_SCALE)
            throw new IOException("Остановка шкалы без данных не подтверждена");
        session.put("android_auto_gesture_motion","Жесты и движение шкал Android Auto","Измерения","Расчёт проверен","",
            "Программная проверка тапов страниц, двойного тапа увеличения и промежуточных положений шкалы. Заполнение экрана и плавность на магнитоле этим не подтверждаются.");
    }

    private void checkDashboardSamples()throws IOException{
        DashboardCatalog.Parameter voltage=DashboardCatalog.find("OBD/adapterV");
        DashboardCatalog.Parameter rpm=DashboardCatalog.find("OBD/pid12");
        DashboardReadings readings=new DashboardReadings();
        List<Boolean> updates=new ArrayList<>();
        DashboardReadings.Plan plan=new DashboardReadings.Plan(Collections.singletonList(voltage),
            (mode,row)->updates.add(readings.record(mode,row)));
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        Engine.Row last=null;
        for(int i=0;i<2;i++){
            if(!session.isCurrent())return;
            String response=elm.at("ATRV").replaceAll("[\\r\\n> ]","");
            if(!response.matches("[0-9]+(?:\\.[0-9]+)?V"))throw new IOException("Адаптер не передал напряжение");
            String value=response.substring(0,response.length()-1);
            min=Math.min(min,Double.parseDouble(value));
            max=Math.max(max,Double.parseDouble(value));
            last=new Engine.Row(voltage.key,voltage.name,"Измерения",value,"В",voltage.description);
            plan.receiver.accept("OBD",last);
            if(i==0&&readings.sample(voltage.id).scalePosition!=DashboardReadings.NO_SCALE)
                throw new IOException("Шкала панели появилась до двух разных измерений");
        }
        String expected="Мин "+DiagnosticSession.format(min)+" В · Макс "+DiagnosticSession.format(max)+" В";
        DashboardReadings.Sample sample=readings.sample(voltage.id);
        int expectedPosition=DashboardReadings.NO_SCALE;
        if(min<max)expectedPosition=Double.parseDouble(last.value)==max?100:0;
        if(sample==null||sample.scalePosition!=expectedPosition)
            throw new IOException("Положение на шкале панели не соответствует измеренному диапазону");
        if(!plan.contains("OBD",voltage.key)||plan.contains("OBD",rpm.key)||!sample.range.equals(expected))
            throw new IOException("Диапазон показаний панели не подтверждён");
        plan.receiver.accept("OBD",last);
        if(!updates.get(0)||updates.get(2))throw new IOException("Обновление панели по изменению значения не подтверждено");
        session.put("android_auto_range","Диапазон показаний Android Auto","Измерения",sample.range,"",
            "Два измерения выбранного параметра. Шкала использует измеренный диапазон. Повтор значения не требует перерисовки.");
    }

    private void probeExtraObd(boolean reuseAdapter)throws IOException {
        if(reuseAdapter){
            session.log.record("adapter_reuse","OBD","extra_obd_status",0);
            try{obd.probeExtra();return;}
            catch(IOException e){
                if(session.wireLost()||!session.isCurrent())throw e;
                session.log.record("adapter_reuse_fallback","OBD","extra_obd_status",0);
            }
        }
        elm.init();obd.initialize();
        obd.probeExtra();
    }

    @FunctionalInterface
    private interface UdsRead<T>{T read()throws IOException;}

    /** Each read selects its own ECU. A completed negative reply leaves the adapter usable. */
    private final class UdsSetup {
        private boolean ready;
        <T>T read(String key,UdsRead<T> action)throws IOException{
            if(ready){
                ready=false;
                session.log.record("adapter_reuse",session.mode,key,0);
                try{
                    T result=action.read();
                    ready=true;
                    return result;
                }catch(IOException e){
                    if(session.wireLost()||!session.isCurrent())throw e;
                    session.log.record("adapter_reuse_fallback",session.mode,key,0);
                }
            }
            elm.init();
            T result=action.read();
            ready=true;
            return result;
        }
    }

    private void checkConfigurations(boolean full)throws IOException {
        UdsSetup setup=new UdsSetup();
        for(String block:new String[]{"OBD","DSG","ABS","EPS","GATEWAY","HALDEX"}){
            for(boolean dataset:new boolean[]{false,true}){
                String key=(dataset?"dataset_":"coding_")+block;
                check(block,key,full,()->{
                    if(!setup.read(key,()->configuration.read(dataset)))
                        throw new IOException("Блок не передал "+(dataset?"номер или версию параметрии":"кодировку"));
                });
            }
        }
    }
    private static List<Integer> integers(int[] values){
        List<Integer> result=new ArrayList<>();
        for(int value:values)result.add(value);
        return result;
    }

    private void checkGroups(String mode,List<Integer> selected,boolean full)throws IOException{
        boolean pending=false;
        for(int group:selected)if(full||!partialProgress.isVerified(groupKey(mode,group))&&!partialProgress.isVerified(unsupportedKey(mode,group))){pending=true;break;}
        if(!pending)return;
        if(!probe(mode,mode+"_channel",()->{elm.init();vag.open();},false))return;
        try{
            for(int group:selected){
                if(!full&&partialProgress.isVerified(unsupportedKey(mode,group))){
                    session.log.record("analysis_skip",mode,unsupportedKey(mode,group)+" confirmed unsupported",0);
                    continue;
                }
                check(mode,groupKey(mode,group),full,()->{
                    if(!vag.hasChannel())vag.open();
                    try{
                        if(!vag.readGroup(group))throw new VagReader.NoMeasurements("Нет расшифрованных полей группы "+group);
                    }catch(VagReader.NoMeasurements e){
                        // An explicit 7F refusal is a definitive negative answer,
                        // unlike silence. Remember it so «Новьё» stops retrying.
                        if(e.unsupported&&session.isCurrent())partialProgress.markVerified(unsupportedKey(mode,group));
                        throw e;
                    }catch(IOException e){
                        // A transport failure can leave the sequence out of sync.
                        if(!(e instanceof VagReader.NoMeasurements))vag.close();
                        throw e;
                    }
                },false);
            }
        }finally{vag.close();}
    }

    private String groupKey(String mode,int group){
        boolean corrected=mode.equals("ECM")&&Arrays.asList(20,31,32,91,93,106).contains(group)
            ||mode.equals("DSG")&&Arrays.asList(11,12,54,109).contains(group);
        return mode+"_group_"+(corrected?"formats2_":"v09_")+group;
    }
    private String unsupportedKey(String mode,int group){return mode+"_group_unsupported_"+group;}

    private void check(String mode,String key,boolean full,Probe action)throws IOException{
        check(mode,key,full,action,true);
    }
    private void check(String mode,String key,boolean full,Probe action,boolean close)throws IOException{
        if(!session.isCurrent())return;
        if(!full&&partialProgress.isVerified(key)){
            session.log.record("analysis_skip",mode,key+" already verified",0);
            return;
        }
        if(probe(mode,key,action,close))partialProgress.markVerified(key);
    }

    private boolean probe(String mode,String strategy,Probe probe) throws IOException {
        return probe(mode,strategy,probe,true);
    }
    private boolean probe(String mode,String strategy,Probe probe,boolean close) throws IOException {
        if(!session.analyzing.getAsBoolean()||!session.isCurrent())return false;
        session.mode=mode;session.state.status("Анализ · "+mode+" · "+strategy,false);
        session.log.record("analysis_attempt",mode,strategy+" start",0);
        try {
            probe.run();
            if(!session.isCurrent()){
                session.log.record("analysis_cancelled",mode,strategy,0);
                return false;
            }
            session.log.record("analysis_result",mode,strategy+" success",0);
            session.put("analysis_"+mode+"_"+strategy,"Анализ · "+mode+" · "+strategy,"Анализ","Успешно","","Чтение завершено");
            return true;
        } catch(IOException e) {
            if(session.wireLost())throw e;
            if(!session.isCurrent()){
                session.log.record("analysis_cancelled",mode,strategy,0);
                return false;
            }
            String detail=e.getMessage()==null?e.toString():e.getMessage();
            session.log.record("analysis_result",mode,strategy+" failed: "+detail,0);
            session.put("analysis_"+mode+"_"+strategy,"Анализ · "+mode+" · "+strategy,"Анализ","Не удалось","",detail);
            return false;
        } finally {if(close)vag.close();}
    }
    private void checkHaldexNew(boolean full)throws IOException{
        List<Haldex.Sensor> pending=new ArrayList<>();
        for(Haldex.Sensor sensor:Haldex.SENSORS)
            if(sensor.probe&& (full||!partialProgress.isVerified("haldex_"+sensor.did)))pending.add(sensor);
        if(pending.isEmpty())return;
        if(!probe("HALDEX","haldex_identification",()->{elm.init();haldex.initialize();}))return;
        for(Haldex.Sensor sensor:pending){
            check("HALDEX","haldex_"+sensor.did,full,()->{
                if(!haldex.read(sensor))throw new IOException("Показание "+sensor.did+" пока не подтверждено");
            });
        }
    }
}
