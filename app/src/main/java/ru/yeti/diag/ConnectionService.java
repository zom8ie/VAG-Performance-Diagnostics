package ru.yeti.diag;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns the Bluetooth socket and supervises reconnects without changing ECU data. */
public final class ConnectionService extends Service implements Engine.State {
    public static volatile ConnectionService current;
    public volatile String message="Нет подключения",deviceName="Viecar";
    public volatile boolean ready,connecting,ecuReady;public volatile long revision,rowEpoch;
    private final LinkedHashMap<String,Engine.Row> rows=new LinkedHashMap<>();
    private String rowsMode=Engine.IDLE;
    private volatile BluetoothSocket socket;private volatile Elm.StreamWire wire;
    private volatile Engine engine;public volatile Journal journal;private volatile boolean stopping;
    private String wantedMode=Engine.IDLE;
    private boolean phoneInForeground;
    private PollingBenchmark analysisBenchmark;
    private DashboardReadings.Plan dashboard;
    private String wantedFilter="Основные";
    private final Set<String> verifiedPartialChecks=new HashSet<>();
    private android.content.SharedPreferences progressPreferences;
    private PowerManager.WakeLock wake;private Thread thread;
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor();

    static void startConnection(Context context,boolean phoneInForeground){
        android.content.SharedPreferences settings=AdapterPicker.settings(context);
        context.startForegroundService(new Intent(context,ConnectionService.class)
            .putExtra("address",settings.getString("address",""))
            .putExtra("name",settings.getString("name","Viecar"))
            .putExtra("phoneInForeground",phoneInForeground));
        settings.edit().putBoolean("connectionWanted",true).apply();
    }

    static void stopConnection(Context context){
        AdapterPicker.settings(context).edit().putBoolean("connectionWanted",false).apply();
        context.stopService(new Intent(context,ConnectionService.class));
    }

    @Override public void onCreate(){
        super.onCreate();current=this;progressPreferences=getSharedPreferences("analysis_progress",MODE_PRIVATE);
        verifiedPartialChecks.addAll(progressPreferences.getStringSet("verified",Collections.emptySet()));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTaskRemoved(Intent rootIntent){
        setPhoneInForeground(false);
        pauseMeasurements();
        super.onTaskRemoved(rootIntent);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("STOP".equals(intent.getAction())){
            AdapterPicker.settings(this).edit().putBoolean("connectionWanted",false).apply();
            stopSelf();return START_NOT_STICKY;
        }
        if(thread!=null&&thread.isAlive())return START_NOT_STICKY;
        setPhoneInForeground(intent.getBooleanExtra("phoneInForeground",false));
        String address=intent.getStringExtra("address");deviceName=intent.getStringExtra("name");
        if(deviceName==null)deviceName="Viecar";
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("connection","Диагностика Yeti",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ConnectionService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_car).setContentTitle("Yeti · чтение параметров")
            .setContentText("Журнал начнёт записываться после подключения.").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Отключить",stop).build()).build();
        startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        try{
            journal=new Journal(this);
            journal.record("analysis_progress","restore","verified_checks="+verifiedPartialChecks.size(),0);
            journal.record("connection_request",intent.getBooleanExtra("phoneInForeground",false)?"phone":"android_auto",
                "Начато подключение к выбранному адаптеру",0);
        }catch(IOException e){
            failure("Не удалось открыть журнал подключения: "+e.getMessage());
            stopSelf();return START_NOT_STICKY;
        }
        connecting=true;status("Соединение с "+deviceName+"…",false);
        thread=new Thread(()->connect(address),"vehicle-reader");thread.start();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("MissingPermission") private void connect(String address) {
        try {
            int retries=0;long sessionStarted=0;
            while(!stopping) {
                try {
                    openSocket(address);
                    if(stopping)break;
                    sessionStarted=SystemClock.elapsedRealtime();connecting=false;
                    Engine next=new Engine(new Elm(wire,journal),journal,this,new Engine.PartialProgress(){
                        public boolean isVerified(String key){synchronized(verifiedPartialChecks){return verifiedPartialChecks.contains(key);}}
                        public void markVerified(String key){synchronized(verifiedPartialChecks){
                            if(!verifiedPartialChecks.add(key))return;
                            progressPreferences.edit().putStringSet("verified",new HashSet<>(verifiedPartialChecks)).apply();
                            journal.record("analysis_verified",key,"verified_checks="+verifiedPartialChecks.size(),0);
                        }}
                    },new PreferenceParameterSelection(this));
                    restoreReader(next);
                    next.run();
                    if(stopping)break;
                    journal.record("reconnect","","Сеанс завершился: "+message,0);
                } catch(Exception e) {
                    if(stopping)break;
                    String detail=e.getMessage()==null?e.toString():e.getMessage();
                    if(journal!=null)journal.record("reconnect_error","",detail,0);
                } finally {
                    if(sessionStarted>0&&SystemClock.elapsedRealtime()-sessionStarted>30000)retries=0;
                    sessionStarted=0;closeSocketResources();
                }
                if(stopping)break;
                retries++;
                if(retries>6){
                    failure(journal==null?"Не удалось подключиться к адаптеру.":"Не удалось восстановить Bluetooth после 6 попыток. Журнал сохранён.");
                    break;
                }
                long delay=Math.min(30000L,1000L<<(retries-1));
                String retry=journal==null?"Нет связи с адаптером · повтор через ":"Связь потеряна · повтор через ";
                status(retry+(delay/1000)+" сек ("+retries+"/6)",false);
                if(journal!=null)journal.record("reconnect","attempt"+retries,"delay_ms="+delay,0);
                try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }catch(Exception e){if(!stopping)failure(e.getMessage()==null?e.toString():e.getMessage());}
        finally {
            closeSocketResources();if(journal!=null)journal.close();connecting=false;ready=false;
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private synchronized void restoreReader(Engine next){
        next.setDashboard(dashboard);
        next.setPhoneInForeground(phoneInForeground);
        next.setFilter(wantedFilter);
        if(Engine.isAnalysis(wantedMode))next.requestAnalysis(wantedMode,analysisBenchmark);
        else if(Engine.IDLE.equals(wantedMode))next.pauseMeasurements();
        else next.choose(wantedMode);
        engine=next;
    }

    @SuppressWarnings("MissingPermission") private void openSocket(String address) throws IOException {
        BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null||!adapter.isEnabled())throw new IOException("Включи Bluetooth");
        if(address==null||!BluetoothAdapter.checkBluetoothAddress(address))throw new IOException("Выбери адаптер");
        adapter.cancelDiscovery();BluetoothDevice device=adapter.getRemoteDevice(address);
        UUID spp=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
        IOException last=null;
        for(boolean secure:new boolean[]{true,false}) {
            if(stopping)throw new IOException("Остановлено");
            BluetoothSocket candidate=secure?device.createRfcommSocketToServiceRecord(spp):device.createInsecureRfcommSocketToServiceRecord(spp);
            socket=candidate;
            ScheduledFuture<?> timeout=timers.schedule(()->{try{candidate.close();}catch(IOException ignored){}},12,TimeUnit.SECONDS);
            try {candidate.connect();last=null;break;}
            catch(IOException e){
                last=e;
                try{candidate.close();}catch(IOException ignored){}
                if(journal!=null)journal.record("bluetooth_attempt",secure?"secure_spp":"insecure_spp",e.getClass().getSimpleName(),0);
            }
            finally{timeout.cancel(false);}
        }
        if(last!=null)throw new IOException("Нет связи с Viecar. Освободи адаптер, проверь его питание и сопряжение Bluetooth.",last);
        if(stopping)return;
        InputStream input=socket.getInputStream();
        OutputStream output=socket.getOutputStream();
        if(journal==null)journal=new Journal(this);
        journal.record("bluetooth","SPP","Connected; address omitted",0);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"yeti:reader");wake.acquire(2*60*60*1000L);
        wire=new Elm.StreamWire(input,output,journal);
        ready=false;status("Bluetooth подключён · подготовка ЭБУ…",false);
    }

    private void closeSocketResources(){
        ecuReady=false;
        if(wire!=null)wire.closed=true;
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
        wire=null;socket=null;
        if(wake!=null&&wake.isHeld())wake.release();
    }

    public synchronized List<Engine.Row> snapshot(){return new ArrayList<>(rows.values());}
    synchronized void showDashboard(DashboardReadings.Plan plan){
        dashboard=plan;
        if(journal!=null)journal.record("dashboard_attached","",plan==null?"screen_detached":String.join(", ",plan.routes.keySet()),0);
        if(engine!=null){
            if(plan!=null)for(Engine.Row row:rows.values())plan.acceptPhoneSample(rowsMode,row);
            engine.setDashboard(plan);
        }
    }
    synchronized void hideDashboard(DashboardReadings.Plan plan){
        if(dashboard==plan)showDashboard(null);
    }
    @Override public synchronized void row(Engine.Row row){
        rowsMode=engine==null?Engine.IDLE:engine.activeMode();
        rows.put(row.key,MeasurementRetention.keepLastSuccessful(rows.get(row.key),row));
        if(dashboard!=null)dashboard.acceptPhoneSample(rowsMode,row);
        revision++;
    }
    @Override public synchronized void clear(){rows.clear();rowsMode=Engine.IDLE;rowEpoch++;revision++;}
    @Override public void status(String s,boolean r){message=s;ready=r;if(r)ecuReady=true;revision++;}
    @Override public void failure(String m){status("Ошибка: "+m,false);if(journal!=null)try{journal.record("error","",m,0);}catch(Exception ignored){}}

    public synchronized void selectMode(String mode){
        if(!VehicleData.sensorMode(mode)||wantedMode.equals(mode))return;
        wantedMode=mode;Engine e=engine;if(e!=null)e.choose(mode);
    }
    synchronized void pauseMeasurements(){
        if(Engine.isAnalysis(wantedMode))return;
        wantedMode=Engine.IDLE;
        if(engine!=null)engine.pauseMeasurements();
    }
    synchronized void setPhoneInForeground(boolean foreground){
        phoneInForeground=foreground;
        if(engine!=null)engine.setPhoneInForeground(foreground);
    }
    synchronized void setFilter(String filter){
        wantedFilter=filter;
        if(engine!=null)engine.setFilter(filter);
    }
    boolean displays(String mode,boolean analysis){
        Engine reader=engine;
        if(reader==null)return false;
        return mode.equals(analysis?reader.requestedMode():reader.activeMode());
    }
    public void requestAnalysis(){startAnalysis(Engine.FULL_ANALYSIS);}
    public void requestPartialAnalysis(){startAnalysis(Engine.PARTIAL_ANALYSIS);}
    private synchronized void startAnalysis(String mode){
        wantedMode=mode;
        analysisBenchmark=new PollingBenchmark();
        if(engine!=null)engine.requestAnalysis(mode,analysisBenchmark);
    }
    /** Explicit action for the gateway's installed-module list. */
    public synchronized void refreshGateway(){
        wantedMode="GATEWAY";
        Engine reader=engine;
        if(reader!=null)reader.refreshGateway();
    }
    public void requestEngineFaults(){
        Engine reader=engine;
        if(reader!=null)reader.requestEngineFaults();
    }
    public void requestBlockFaults(String mode){
        Engine reader=engine;
        if(reader!=null)reader.requestFaults(mode);
    }
    public void requestFaultClear(String mode){
        Engine reader=engine;
        if(reader!=null)reader.requestFaultClear(mode);
    }
    public void requestBlockConfig(String mode,boolean dataset){
        Engine reader=engine;
        if(reader!=null)reader.requestConfig(mode,dataset);
    }

    public boolean active(){return thread!=null&&thread.isAlive()&&!stopping;}
    public boolean bluetoothConnected(){
        Elm.StreamWire connection=wire;
        return connection!=null&&!connection.closed;
    }
    @Override public void onDestroy(){
        stopping=true;if(engine!=null)engine.stopped=true;
        if(wire!=null)wire.closed=true;
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
        if(thread!=null)thread.interrupt();
        timers.shutdownNow();closeSocketResources();if(journal!=null)journal.close();current=null;super.onDestroy();
    }
}
