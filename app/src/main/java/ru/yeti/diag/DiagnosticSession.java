package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Worker-owned diagnostic context and the single route for publishing measurements. */
final class DiagnosticSession {
    /** A short pacing gap prevents ELM327-compatible adapters from dropping frames. */
    static final int LIVE_REQUEST_PAUSE_MS=40;
    static final int OBD_REQUEST_PAUSE_MS=20;
    final Elm elm;
    final Engine.Log log;
    final Engine.State state;
    final BooleanSupplier stopped, analyzing;
    private final Predicate<String> continueMode;
    private final ParameterSelection parameterSelection;
    DashboardReadings.Plan dashboard;
    volatile String mode="";

    DiagnosticSession(Elm elm, Engine.Log log, Engine.State state, BooleanSupplier stopped,
                      BooleanSupplier analyzing, Predicate<String> continueMode) {
        this(elm,log,state,stopped,analyzing,continueMode,ParameterSelection.ALL_ENABLED);
    }
    DiagnosticSession(Elm elm, Engine.Log log, Engine.State state, BooleanSupplier stopped,
                      BooleanSupplier analyzing, Predicate<String> continueMode,ParameterSelection parameterSelection) {
        this.elm=elm;
        this.log=log;
        this.state=state;
        this.stopped=stopped;
        this.analyzing=analyzing;
        this.continueMode=continueMode;
        this.parameterSelection=parameterSelection;
    }

    boolean isCurrent(){return continueMode.test(mode);}
    boolean isEnabled(String key){
        if(dashboard==null)return analyzing.getAsBoolean()||parameterSelection.isEnabled(mode,key);
        return dashboardEnabled(dashboard,mode,key);
    }
    static boolean dashboardEnabled(DashboardReadings.Plan dashboard,String mode,String key){
        if(mode.equals("OBD")){
            if(key.equals("pid16")&&dashboard.contains(mode,"maf_power"))return true;
            if(key.equals("pid52")&&dashboard.contains(mode,"oxygenCurrent"))return true;
        }
        return dashboard.contains(mode,key);
    }

    boolean wireLost(){
        return stopped.getAsBoolean() || elm.wire instanceof Elm.StreamWire && ((Elm.StreamWire)elm.wire).closed;
    }

    void put(String key,String name,String group,String value,String unit,String note) {
        String rowKey=analyzing.getAsBoolean()?mode+"/"+key:key;
        String label=analyzing.getAsBoolean()&&!group.equals("Анализ")?VehicleData.modeName(mode)+" · "+name:name;
        Engine.Row row=new Engine.Row(rowKey,label,group,value,unit,note);
        if(dashboard==null)state.row(row);
        else if(isCurrent()&&dashboard.contains(mode,key))dashboard.receiver.accept(mode,row);
        log.value(mode,key,name,value,unit,note);
    }

    static String format(double v){return String.format(Locale.ROOT,Math.abs(v)>=1000?"%.0f":Math.abs(v)>=10?"%.1f":"%.3f",v).replaceAll("(\\.[0-9]*?)0+$","$1").replaceAll("\\.$","");}
    static void pause(int ms)throws IOException{try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Остановлено",e);}}
}
