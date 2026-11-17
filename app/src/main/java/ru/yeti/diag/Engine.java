package ru.yeti.diag;

import java.io.*;
import java.util.*;

public final class Engine implements Runnable {
    public static final String FULL_ANALYSIS="ANALYSIS";
    public static final String PARTIAL_ANALYSIS="PARTIAL_ANALYSIS";
    /** One engine screen, combining standard OBD and VAG measurement groups. */
    static final String COMBINED_ENGINE="ENGINE";
    static final String IDLE="";
    public static final class Row {
        public final String key,name,group,value,unit,note;public final long time;
        Row(String key,String name,String group,String value,String unit,String note){this.key=key;this.name=name;this.group=group;this.value=value;this.unit=unit;this.note=note;time=System.currentTimeMillis();}
    }
    public interface State {
        void status(String message,boolean ready);
        void row(Row row);
        void clear();
        void failure(String message);
    }
    public interface PartialProgress {
        boolean isVerified(String key);
        void markVerified(String key);
    }
    interface Log extends Elm.Events {
        void value(String mode,String key,String name,String value,String unit,String status);
    }
    private final Elm elm;private final Log journal;private final State state;
    private final DiagnosticSession session;
    private final ObdReader obd;
    private final VagReader vag;
    private final HaldexReader haldex;
    private final BlockConfigurationReader configuration;
    private final AnalysisRunner analysisRunner;
    private final ReadAvailability vagChannelAvailability=new ReadAvailability();
    private final ReadAvailability dashboardAvailability=new ReadAvailability();
    private boolean combinedObdReady;
    public volatile boolean stopped;
    private volatile String requestedMode="OBD";
    private volatile boolean phoneInForeground=true;
    private String phoneModeBeforeDashboard=IDLE;
    private boolean returningFromDashboard;
    private volatile DashboardReadings.Plan dashboard;
    private int dashboardRoute;
    private boolean dashboardNeedsSetup;
    private String runningAnalysis="";
    private boolean ecuInitialized;
    private volatile PollingBenchmark requestedAnalysis;
    private PollingBenchmark completedAnalysis;
    private volatile String pendingFaults="";
    private volatile String pendingFaultClear="";
    private final Queue<ConfigRead> configReads=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final class ConfigRead{
        final String mode;
        final boolean dataset;
        ConfigRead(String mode,boolean dataset){this.mode=mode;this.dataset=dataset;}
    }
    private volatile long diagnosticRequests;
    private long completedDiagnostics;
    Engine(Elm elm,Log journal,State state){this(elm,journal,state,new PartialProgress(){public boolean isVerified(String key){return false;}public void markVerified(String key){}},ParameterSelection.ALL_ENABLED);}
    Engine(Elm elm,Log journal,State state,PartialProgress partialProgress){
        this(elm,journal,state,partialProgress,ParameterSelection.ALL_ENABLED);
    }
    Engine(Elm elm,Log journal,State state,PartialProgress partialProgress,ParameterSelection parameterSelection){
        this.elm=elm;
        this.journal=journal;
        this.state=state;
        session=new DiagnosticSession(elm,journal,state,()->stopped,()->!runningAnalysis.isEmpty(),this::continueMode,parameterSelection);
        BlockInfoReader info=new BlockInfoReader(session);
        obd=new ObdReader(session,info);
        vag=new VagReader(session,info);
        haldex=new HaldexReader(session,info);
        configuration=new BlockConfigurationReader(session,info);
        analysisRunner=new AnalysisRunner(session,partialProgress,obd,vag,haldex,info,configuration);
    }

    public String activeMode(){return session.mode;}
    public String requestedMode(){return requestedMode;}
    void setDashboard(DashboardReadings.Plan plan){dashboard=plan;}
    void setPhoneInForeground(boolean foreground){phoneInForeground=foreground;}
    public void setFilter(String filter){obd.filter=filter;}
    public void requestEngineFaults(){obd.readErrors=true;}
    public synchronized void choose(String mode){
        if(!VehicleData.sensorMode(mode))return;
        if(mode.equals("GATEWAY"))diagnosticRequests++;
        requestedMode=mode;
    }
    synchronized void refreshGateway(){
        requestedMode="GATEWAY";
        diagnosticRequests++;
    }
    synchronized void pauseMeasurements(){
        if(isAnalysis(requestedMode))return;
        requestedMode=IDLE;
        pendingFaults="";
        pendingFaultClear="";
        obd.readErrors=false;
        configReads.clear();
    }
    public void requestFaults(String mode){if(VehicleData.sensorMode(mode))pendingFaults=mode;}
    public void requestFaultClear(String mode){if(VehicleData.sensorMode(mode))pendingFaultClear=mode;}
    public void requestConfig(String mode,boolean dataset){if(VehicleData.sensorMode(mode))configReads.add(new ConfigRead(mode,dataset));}
    public void requestAnalysis(){requestAnalysis(FULL_ANALYSIS,new PollingBenchmark());}
    public void requestPartialAnalysis(){requestAnalysis(PARTIAL_ANALYSIS,new PollingBenchmark());}
    synchronized void requestAnalysis(String mode,PollingBenchmark benchmark){
        requestedAnalysis=benchmark;
        requestedMode=mode;
    }
    static boolean isAnalysis(String mode){return FULL_ANALYSIS.equals(mode)||PARTIAL_ANALYSIS.equals(mode);}
    private boolean continueMode(String mode){
        String target=requestedMode;
        if(session.dashboard!=null)return !stopped&&dashboard==session.dashboard&&dashboardMayPoll(target);
        if(!runningAnalysis.isEmpty())
            return !stopped&&target.equals(runningAnalysis)&&completedAnalysis==requestedAnalysis;
        DashboardReadings.Plan plan=dashboard;
        return !stopped&&target.equals(mode)&&(plan==null||plan.routes.isEmpty()||!dashboardMayPoll(target));
    }
    private boolean dashboardMayPoll(String target){
        if(isAnalysis(target))return completedAnalysis==requestedAnalysis;
        return target.equals(IDLE)||!phoneInForeground;
    }
    @Override public void run() {
        try {
            while(!stopped) {
                PollingBenchmark analysis;
                long requestedDiagnostics;
                String target;
                synchronized(this){
                    target=requestedMode;
                    analysis=requestedAnalysis;
                    requestedDiagnostics=diagnosticRequests;
                }
                DashboardReadings.Plan plan=dashboard;
                if(plan!=null&&!plan.routes.isEmpty()&&dashboardMayPoll(target)){
                    pollDashboard(plan);
                    continue;
                }
                leaveDashboard();
                if(isAnalysis(target)){
                    if(completedAnalysis!=analysis)runAnalysis(target,analysis);
                    DiagnosticSession.pause(200);
                    continue;
                }
                if(target.equals(IDLE)){
                    waitForScreen();
                    continue;
                }
                if(!session.mode.equals(target)||target.equals("GATEWAY")&&completedDiagnostics!=requestedDiagnostics){
                    startMode(target,requestedDiagnostics);
                    continue;
                }
                if(pendingFaults.equals(session.mode)&&requestedMode.equals(session.mode)){
                    pendingFaults="";
                    session.put("faults","Ошибки · "+VehicleData.modeName(session.mode),"Состояние","Чтение…","","Запрашивается память блока");
                    try{
                        if(session.mode.equals("OBD")||session.mode.equals(COMBINED_ENGINE)){
                            if(session.mode.equals(COMBINED_ENGINE))prepareCombinedObd();
                            obd.readDtcs();
                        }else vag.readBlockFaults();
                    }
                    catch(IOException e){if(session.wireLost())throw e;vag.close();vag.showFaults(VehicleData.modeName(session.mode),null);}
                    continue;
                }
                if(pendingFaultClear.equals(session.mode)&&requestedMode.equals(session.mode)){
                    pendingFaultClear="";
                    session.put("faults","Ошибки · "+VehicleData.modeName(session.mode),"Состояние","Удаление…","","Ожидается подтверждение блока. Не отключайте адаптер.");
                    try{
                        if(session.mode.equals("OBD")||session.mode.equals(COMBINED_ENGINE)){
                            if(session.mode.equals(COMBINED_ENGINE))prepareCombinedObd();
                            obd.clearDtcs();
                            obd.readErrors=true;
                        }else{
                            vag.clearBlockFaults();
                            pendingFaults=session.mode;
                        }
                    }catch(IOException e){
                        if(session.wireLost())throw e;
                        session.put("faults","Ошибки · "+VehicleData.modeName(session.mode),"Состояние","Не удалены","",e.getMessage());
                    }
                    continue;
                }
                ConfigRead config=configReads.poll();
                if(config!=null&&config.mode.equals(session.mode)&&requestedMode.equals(session.mode)){
                    readConfigInCurrentMode(config.dataset);
                    continue;
                }
                pollMeasurements(target);
            }
        } catch(Exception ex){if(!stopped)state.failure(ex.getMessage()==null?ex.toString():ex.getMessage());}
        finally {vag.close();}
    }

    private void pollDashboard(DashboardReadings.Plan plan)throws IOException{
        if(session.dashboard!=plan){
            boolean starting=session.dashboard==null;
            if(starting)phoneModeBeforeDashboard=session.mode;
            // Chassis readers discover or probe measurements for the selected page.
            dashboardNeedsSetup=starting||dashboardNeedsSetup
                ||session.mode.equals("ABS")||session.mode.equals("EPS")||session.mode.equals("HALDEX");
            session.dashboard=plan;
            dashboardRoute=0;
            dashboardAvailability.clear();
            journal.record("dashboard_poll","start",String.join(", ",plan.routes.keySet()),0);
        }
        List<String> modes=new ArrayList<>(plan.routes.keySet());
        String mode=modes.get(dashboardRoute);
        dashboardRoute=(dashboardRoute+1)%modes.size();
        if(!dashboardRouteReady(mode,plan)){
            if(modes.size()==1||modes.stream().noneMatch(route->dashboardRouteReady(route,plan)))DiagnosticSession.pause(200);
            return;
        }
        try{
            if(dashboardNeedsSetup||!session.mode.equals(mode)){
                dashboardNeedsSetup=true;
                startMode(mode,0);
                if(!session.isCurrent())return;
                dashboardNeedsSetup=false;
            }
            if(session.isCurrent())pollMeasurements(mode);
            dashboardAvailability.success(mode);
        }catch(IOException e){
            if(session.wireLost())throw e;
            vag.close();
            dashboardNeedsSetup=true;
            if(session.isCurrent()){
                long delay=dashboardAvailability.failed(mode);
                journal.record("dashboard_read",mode,e.getMessage()+"; retry_ms="+delay,0);
            }
        }
    }

    private boolean dashboardRouteReady(String mode,DashboardReadings.Plan plan){
        return dashboardAvailability.ready(mode)&&(!mode.equals("OBD")||obd.hasPendingDashboardPoll(plan));
    }

    private void leaveDashboard(){
        if(session.dashboard==null)return;
        journal.record("dashboard_poll","stop",dashboard==null?"screen_detached":"phone_has_priority",0);
        vag.close();
        session.dashboard=null;
        session.mode=IDLE;
        returningFromDashboard=true;
    }

    private void waitForScreen()throws IOException{
        returningFromDashboard=false;
        if(!ecuInitialized){
            elm.init();
            session.mode="OBD";
            obd.initialize();
            ecuInitialized=true;
        }
        if(!session.mode.equals(IDLE)){
            vag.close();
            session.mode=IDLE;
            state.status("Подключено · выбери блок",true);
        }
        DiagnosticSession.pause(200);
    }

    private void runAnalysis(String analysis,PollingBenchmark request)throws IOException{
        returningFromDashboard=false;
        completedAnalysis=request;
        vag.close();
        session.mode=analysis;
        state.clear();
        runningAnalysis=analysis;
        try{
            boolean partial=analysis.equals(PARTIAL_ANALYSIS);
            state.status(partial?"Частичный анализ…":"Полный анализ…",false);
            if(partial)analysisRunner.runPartial(request);else analysisRunner.runFull(request);
        }finally{
            runningAnalysis="";
        }
        if(requestedMode.equals(analysis)&&requestedAnalysis==request){
            session.mode=analysis;
            state.status("Анализ завершён · журнал сохранён",true);
        }else session.mode="";
    }

    private void startMode(String mode,long diagnostics)throws IOException{
        vag.close();
        boolean retainRows=session.dashboard==null&&returningFromDashboard
            &&phoneModeBeforeDashboard.equals(mode)&&!mode.equals("GATEWAY");
        if(session.dashboard==null)returningFromDashboard=false;
        if(session.dashboard==null&&!retainRows)state.clear();
        session.mode=mode;
        vagChannelAvailability.success(mode);
        if(session.dashboard==null)state.status("Подготовка · "+VehicleData.modeName(mode),false);
        boolean reuseObd=false;
        boolean directTp=mode.equals("ECM")&&session.dashboard!=null&&ecuInitialized;
        if(mode.equals("OBD")&&session.dashboard!=null&&obd.hasCapabilities()){
            try{
                obd.prepareForPolling();
                reuseObd=true;
                journal.record("adapter_reuse","OBD","dashboard_capabilities",0);
            }catch(IOException e){
                if(session.wireLost()||!session.isCurrent())throw e;
                journal.record("adapter_reuse_fallback","OBD","dashboard_capabilities",0);
            }
        }
        if(!reuseObd&&!directTp)elm.init();
        if(mode.equals("OBD")){
            if(!reuseObd)obd.initialize(!retainRows);
        }
        else if(mode.equals(COMBINED_ENGINE)){
            obd.initialize(!retainRows);
            combinedObdReady=true;
            vag.prepareCombinedMeasurements();
        }
        else if(mode.equals("ABS")||mode.equals("EPS")||mode.equals("GATEWAY")){
            completedDiagnostics=diagnostics;
            initializeChassisOrGateway(mode);
        }else{
            try{
                if(mode.equals("HALDEX"))haldex.initialize();else vag.initialize();
            }catch(IOException e){
                if(session.wireLost()||session.dashboard!=null)throw e;
                journal.record("vag_unavailable",mode,e.getMessage(),0);
                vag.close();
                vag.clearMeasurements();
                session.put("unavailable","Расширенные параметры","Информация","Нет подтверждённых данных","","Причина: "+e.getMessage());
            }
        }
        if(session.isCurrent()&&(session.dashboard==null||!ecuInitialized))state.status("Подключено · "+VehicleData.modeName(mode),true);
        ecuInitialized=true;
    }

    private void initializeChassisOrGateway(String mode)throws IOException{
        try{
            if(mode.equals("ABS"))vag.readAbs();
            else if(mode.equals("EPS")){
                if(!vag.readSteering())
                    session.put("eps_status","Измерения усилителя","Информация","Нет данных","","Блок не подтвердил доступные параметры усилителя.");
            }else vag.readGateway();
        }catch(IOException e){
            vag.close();
            if(session.wireLost()||session.dashboard!=null)throw e;
            session.put("unavailable","Результат чтения","Информация","—","",e.getMessage());
        }finally{
            if(mode.equals("GATEWAY")||!vag.hasChassisMeasurements())vag.close();
        }
    }

    private void pollMeasurements(String mode)throws IOException{
        if(mode.equals("OBD"))obd.poll();
        else if(mode.equals(COMBINED_ENGINE)){
            boolean polledObd=false;
            if(obd.hasPendingPoll()){
                prepareCombinedObd();
                if(!session.isCurrent())return;
                obd.poll();
                polledObd=true;
            }
            if(session.isCurrent()&&vag.hasPendingMeasurements()&&vagChannelAvailability.ready(mode)){
                combinedObdReady=false;
                pollVag(mode);
            }else if(!polledObd)DiagnosticSession.pause(200);
        }
        else if(mode.equals("HALDEX"))haldex.poll();
        else if(mode.equals("ABS")||mode.equals("EPS")){
            if(!vag.hasChassisMeasurements()){
                DiagnosticSession.pause(200);
                return;
            }
            try{
                vag.pollChassis();
                if(session.dashboard==null)DiagnosticSession.pause(150);
            }catch(IOException e){
                if(session.wireLost()||session.dashboard!=null)throw e;
                vag.close();
                journal.record("live_group_retry",mode,e.getMessage(),0);
                DiagnosticSession.pause(1500);
            }
        }else if(vag.hasMeasurements()&&!mode.equals("GATEWAY")&&vagChannelAvailability.ready(mode))pollVag(mode);
        else DiagnosticSession.pause(200);
    }
    private void pollVag(String mode)throws IOException{
        try{
            vag.poll();
            vagChannelAvailability.success(mode);
        }catch(IOException e){
            vag.close();
            if(session.wireLost()||session.dashboard!=null)throw e;
            if(!session.isCurrent())return;
            long delay=vagChannelAvailability.failed(mode);
            journal.record("vag_unavailable",mode,e.getMessage()+"; retry_ms="+delay,0);
        }
    }
    private void prepareCombinedObd()throws IOException{
        vag.close();
        if(combinedObdReady||!session.isCurrent())return;
        obd.prepareForPolling();
        combinedObdReady=true;
    }
    private void readConfigInCurrentMode(boolean dataset)throws IOException{
        session.put(dataset?"dataset":"coding",dataset?"Параметрия":"Кодировка","Информация","Чтение…","","Запрос данных блока");
        boolean resumeTp=vag.hasChannel();
        if(resumeTp)vag.close();
        if(session.mode.equals(COMBINED_ENGINE))combinedObdReady=false;
        try{elm.init();configuration.read(dataset);}
        catch(IOException e){
            if(session.wireLost())throw e;
            session.put(dataset?"dataset":"coding",dataset?"Параметрия":"Кодировка","Информация","Ошибка чтения","",e.getMessage());
        }finally{
            if(resumeTp&&session.isCurrent()&&!session.wireLost()){
                try{vag.open();}
                catch(IOException e){if(session.wireLost())throw e;session.state.status("Не удалось возобновить опрос измерений. Открой раздел заново.",true);}
            }
        }
    }
}
