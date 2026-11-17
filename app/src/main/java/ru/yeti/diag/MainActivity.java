package ru.yeti.diag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import static ru.yeti.diag.UiWidgets.*;

public final class MainActivity extends Activity {
    private static volatile boolean foreground;

    static boolean isForeground(){return foreground;}

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final UiWidgets ui=new UiWidgets(this);
    private DiagnosticRowsView rows;
    private DashboardEditor dashboard;
    private final OfflinePreview preview=new OfflinePreview();
    private AppShell shell;
    private LinearLayout body;
    private String screen="home",mode=Engine.COMBINED_ENGINE,filter="Все",homeState="";
    private AdapterPicker adapter;
    private final JournalPanel journals=new JournalPanel(ui,this::alert);
    private boolean interfaceTest;
    private OnBackInvokedCallback backCallback;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,500);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        rows=new DiagnosticRowsView(ui,this::alert,this::showFaultDialog,new PreferenceParameterSelection(this));
        adapter=new AdapterPicker(this,this::alert,()->{if(screen.equals("home"))home();else updateHeader();});
        if(state!=null)interfaceTest=state.getBoolean("interfaceTest");
        Journal.cleanup(this);
        shell=new AppShell(ui,()->{toggleConnection();updateHeader();},
            ()->analysis(false),()->analysis(true),this::journalsScreen,()->{interfaceTest=!interfaceTest;home();},this::dashboardScreen);
        body=shell.body;
        if(state!=null&&state.containsKey("dashboardPage"))showDashboard(state.getInt("dashboardPage"));
        else if(state!=null&&VehicleData.sensorMode(state.getString("sensorMode",""))){
            ConnectionService service=ConnectionService.current;
            if(interfaceTest||service!=null&&service.active())sensors(state.getString("sensorMode"),true);
            else home();
        }
        else home();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        out.putBoolean("interfaceTest",interfaceTest);
        if(screen.equals("dashboard"))out.putInt("dashboardPage",dashboard.page());
        if(screen.equals("sensors"))out.putString("sensorMode",mode);
    }
    @Override protected void onResume(){
        super.onResume();
        foreground=true;
        ConnectionService service=ConnectionService.current;
        if(service!=null)service.setPhoneInForeground(true);
        handler.post(refresh);
    }
    @Override protected void onPause(){
        foreground=false;
        handler.removeCallbacks(refresh);
        ConnectionService service=ConnectionService.current;
        if(service!=null)service.setPhoneInForeground(false);
        super.onPause();
    }
    @Override protected void onDestroy(){
        if(isFinishing())pauseScreenMeasurements();
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        super.onDestroy();
    }


    private void title(String t){
        TextView heading=ui.text(t,28,TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        heading.setPadding(0,0,0,ui.dp(8));
        body.addView(heading);
    }
    private void hint(String t){body.addView(ui.text(t,13,MUTED));}
    private void button(String title,boolean primary,Runnable action){
        Button b=ui.labelButton(title,15,primary?Color.WHITE:TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        b.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
        b.setBackground(ui.ripple(primary?0x33FFFFFF:0x22006B50,ui.panel(primary?GREEN:TONAL,16),16));
        b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,ui.dp(6),0,ui.dp(6));
        body.addView(b,params);
    }
    private void reset(String next){
        if(!next.equals("sensors"))pauseScreenMeasurements();
        screen=next;
        updateKeepScreenOn();
        shell.setHeaderSearchListener(null);
        shell.updateHeaderNavigation(next.equals("home"),this::home);
        updateBackCallback();
        shell.scroll.scrollTo(0,0);
        body.removeAllViews();
        rows.reset();
        updateBottomBar();
    }
    private void pauseScreenMeasurements(){
        ConnectionService service=ConnectionService.current;
        if(screen.equals("sensors")&&!interfaceTest&&service!=null)service.pauseMeasurements();
    }

    private void updateHeader(){
        ConnectionService c=ConnectionService.current;
        boolean active=c!=null&&c.active();
        String status=interfaceTest?"Тестовый режим · демонстрационные данные":
            c!=null?c.message:!adapter.hasSelection()?"Адаптер не подключён":adapter.name()+" · не подключён";
        shell.updateHeader(active,active&&c.bluetoothConnected(),adapter.hasSelection(),adapter.name(),status,
            c!=null&&c.message.startsWith("Ошибка")&&!interfaceTest);
        updateBottomBar();
    }
    private void updateBottomBar(){
        shell.updateBottomBar(screen.equals("home"),connectionState().equals("off"),interfaceTest);
    }
    private String connectionState(){
        ConnectionService c=ConnectionService.current;
        if(c==null||!c.active())return "off";
        return c.ecuReady&&c.bluetoothConnected()?"ready":"connecting";
    }

    private void home(){
        reset("home");homeState=connectionState();boolean connected=homeState.equals("ready"),connecting=homeState.equals("connecting"),ready=connected||interfaceTest;
        title(ready?"Параметры автомобиля":connecting?"Подключаемся":"Диагностика");
        if(ready){
            ui.menuCard(body,R.drawable.ic_engine,"Двигатель","Все параметры в одном списке",()->sensors(Engine.COMBINED_ENGINE));
            ui.menuCard(body,R.drawable.ic_gearbox,"Коробка передач","DQ250",()->sensors("DSG"));
            ui.menuCard(body,R.drawable.ic_awd,"Полный привод","Haldex 5",()->sensors("HALDEX"));
            ui.menuCard(body,R.drawable.ic_brakes,"ABS / ESP","Тормоза и стабилизация",()->sensors("ABS"));
            ui.menuCard(body,R.drawable.ic_steering_wheel,"Усилитель руля","Момент и угол руля",()->sensors("EPS"));
            ui.menuCard(body,R.drawable.ic_journal,"Блоки автомобиля","Комплектация через CAN-шлюз",()->sensors("GATEWAY"));
        } else if(!connecting){
            String instruction=!adapter.hasSelection()?"Нажми эту плашку и выбери Viecar, затем включи тумблер.":
                "Включи тумблер, чтобы подключиться к "+adapter.name()+".";
            LinearLayout connection=ui.messageCard(body,R.drawable.ic_bluetooth,"Подключи адаптер",instruction);
            connection.setBackground(ui.ripple(0x22006B50,ui.panel(TONAL,20),20));
            connection.setOnClickListener(view->adapter.choose());
            connection.setFocusable(true);
            connection.setContentDescription("Подключи адаптер. "+instruction);
        } else {
            ui.messageCard(body,R.drawable.ic_bluetooth,"Ожидание связи","Параметры появятся после ответа автомобиля.");
        }
        updateHeader();
    }



    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==AdapterPicker.PERMISSION_REQUEST)adapter.onPermissionsResult(grants);
    }

    private void toggleConnection(){ConnectionService c=ConnectionService.current;if(c!=null&&c.active()){disconnect();return;}if(!adapter.hasSelection()){adapter.choose();return;}connect();}
    private void connect(){
        if(!adapter.ensurePermissions())return;ConnectionService c=ConnectionService.current;if(c!=null&&c.active())return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
        try{
            ConnectionService.startConnection(this,true);
            if(interfaceTest){interfaceTest=false;home();}
            else updateHeader();
        }
        catch(Exception e){alert("Не удалось начать подключение",e.getMessage());}
    }
    private void disconnect(){ConnectionService.stopConnection(this);home();}

    private void sensors(String chosen){
        sensors(chosen,false);
    }
    private void sensors(String chosen,boolean restoring){
        ConnectionService c=ConnectionService.current;
        if(!interfaceTest&&(c==null||!c.active()||!restoring&&!screen.equals("sensors")&&!c.bluetoothConnected())){
            alert("Нет соединения","Подключись к Viecar через тумблер в шапке.");home();return;
        }
        reset("sensors");mode=chosen;
        shell.setHeaderSearchListener(rows::setSearchQuery);
        rows.showIdentityCard(body,chosen,interfaceTest,dataset->c.requestBlockConfig(chosen,dataset),()->{
            if(interfaceTest){
                alert("Ошибки · "+OfflinePreview.title(chosen),"Тестовый результат: не обнаружены.");
            }else if(chosen.equals("OBD")||chosen.equals(Engine.COMBINED_ENGINE)){
                c.requestEngineFaults();
            }else{
                c.requestBlockFaults(chosen);
            }
        });
        rows.attachFaultResult(body);
        if(chosen.equals("GATEWAY")){
            button("Обновить список блоков",false,()->{
                if(!interfaceTest)c.refreshGateway();
            });
        }else if(chosen.equals(Engine.COMBINED_ENGINE)||chosen.equals("DSG")||chosen.equals("HALDEX")){
            if(!interfaceTest)hint("Назначение расширенных полей зависит от версии блока.");
        }
        rows.attach(body);
        if(interfaceTest){preview.render(mode,filter,rows);return;}
        c.setFilter(filter);c.selectMode(chosen);update();
    }
    private void analysis(boolean partial){
        ConnectionService c=ConnectionService.current;
        if(interfaceTest){testAnalysis(partial);return;}
        if(c==null||!c.active()||!c.bluetoothConnected()){alert("Нет соединения","Подключи Viecar тумблером в шапке.");return;}
        reset("analysis");mode=partial?Engine.PARTIAL_ANALYSIS:Engine.FULL_ANALYSIS;title(partial?"Новьё":"Полный анализ");
        hint(partial?"Новые и пока неподтверждённые проверки.":"Проверяются двигатель, DQ250, Haldex 5, ABS/ESP, усилитель руля и CAN-шлюз.");
        button("Повторить анализ",false,()->{if(partial)c.requestPartialAnalysis();else c.requestAnalysis();update();});
        rows.attach(body);if(partial)c.requestPartialAnalysis();else c.requestAnalysis();update();
    }


    private void testAnalysis(boolean partial){
        reset("analysis");mode=partial?Engine.PARTIAL_ANALYSIS:Engine.FULL_ANALYSIS;title(partial?"Новьё":"Полный анализ");hint("Демонстрация результатов анализа.");rows.attach(body);rows.exampleRow(partial?"Проверка проблемных групп двигателя":"Проверка всех блоков","Успешно","");rows.exampleRow("Статус соединения","Тестовый режим","");
    }


    private void update(){
        ConnectionService c=ConnectionService.current;updateKeepScreenOn();updateHeader();String state=connectionState();
        if(screen.equals("home")&&!state.equals(homeState)){home();return;}
        if(interfaceTest||(!screen.equals("sensors")&&!screen.equals("analysis")))return;
        if(!rows.isAttached()||c==null||!c.displays(mode,screen.equals("analysis")))return;
        rows.render(mode,filter,c.rowEpoch,c.active()&&c.bluetoothConnected(),c::snapshot);
    }

    private void updateKeepScreenOn(){
        ConnectionService service=ConnectionService.current;
        boolean readingScreen=screen.equals("analysis")||screen.equals("sensors");
        boolean keepOn=readingScreen&&!interfaceTest&&service!=null&&service.active();
        int flag=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        boolean alreadySet=(getWindow().getAttributes().flags&flag)!=0;
        if(keepOn==alreadySet)return;
        if(keepOn)getWindow().addFlags(flag);else getWindow().clearFlags(flag);
    }

    private void journalsScreen(){
        reset("logs");
        title("Журналы");
        journals.show(body);
    }
    private void dashboardScreen(){if(!screen.equals("dashboard"))showDashboard(0);}
    private void showDashboard(int page){
        reset("dashboard");
        title("Android Auto");
        dashboard=new DashboardEditor(ui,body,page);
    }
    private void showFaultDialog(String title,String codes){
        AlertDialog.Builder dialog=new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(FaultCatalog.describe(codes))
            .setPositiveButton("Закрыть",null);
        if(!interfaceTest)dialog.setNegativeButton("Удалить ошибки",(ignored,which)->confirmFaultClear());
        dialog.show();
    }
    private void confirmFaultClear(){
        new AlertDialog.Builder(this)
            .setTitle("Удалить ошибки?")
            .setMessage("Будет очищена память ошибок только выбранного блока. Это не устраняет неисправность; у двигателя также сбрасываются контрольные проверки готовности. Сначала сохраните журнал.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Удалить",(ignored,which)->{
                ConnectionService service=ConnectionService.current;
                if(service==null||!service.active()||!service.bluetoothConnected()){
                    alert("Нет соединения","Подключитесь к адаптеру, затем повторите действие.");
                    return;
                }
                service.requestFaultClear(mode);
            }).show();
    }
    private void alert(String title,String message){if(!isFinishing())new AlertDialog.Builder(this).setTitle(title).setMessage(message==null?"Неизвестная ошибка":message).setPositiveButton("Понятно",null).show();}
    private boolean handleBack(){
        if(!screen.equals("home")){home();return true;}
        if(interfaceTest){interfaceTest=false;home();return true;}
        return false;
    }
    private void updateBackCallback(){
        if(Build.VERSION.SDK_INT<33)return;
        boolean insideApp=!screen.equals("home")||interfaceTest;
        if(insideApp&&backCallback==null){
            backCallback=this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }else if(!insideApp&&backCallback!=null){
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback=null;
        }
    }
    @Override public void onBackPressed(){if(!handleBack())super.onBackPressed();}
}
