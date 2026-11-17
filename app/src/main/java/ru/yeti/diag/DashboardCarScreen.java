package ru.yeti.diag;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.pm.ApplicationInfo;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.*;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-screen instrument panel painted on the car display's navigation surface. */
final class DashboardCarScreen extends Screen {
    private final DashboardPreferences preferences;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final DashboardReadings readings;
    private static final class PageState {int page;}
    private final PageState state;
    private final Map<String,DashboardNeedle> needles=new HashMap<>();
    private final DashboardGauge gauge=new DashboardGauge();
    private final DashboardSurface surface=new DashboardSurface();
    private List<DashboardCatalog.Parameter> frameCells=Collections.emptyList();
    private List<String> frameValues=Collections.emptyList();
    private List<String> frameUnits=Collections.emptyList();
    private List<DashboardReadings.Sample> frameScales=Collections.emptyList();
    private List<Integer> framePositions=Collections.emptyList();
    private final boolean demo;
    private final long demoStarted=SystemClock.elapsedRealtime();
    private ConnectionService connection;
    private DashboardReadings.Plan plan;
    private List<DashboardCatalog.Parameter> shown=Collections.emptyList();
    private List<String> lastText=Collections.emptyList();
    private final AtomicBoolean updateQueued=new AtomicBoolean();
    private final DashboardConnection connectionRetry=new DashboardConnection();
    private String connectionProblem="";
    private boolean visible;
    private long startedAt;
    private int zoom=-1;
    private final Runnable animate;

    private final Runnable refreshValues=()->{
        updateQueued.set(false);
        if(visible)refreshIfChanged();
    };
    private final Runnable checkScreenState=new Runnable(){
        @Override public void run(){
            ensureConnection();
            synchronizePage();
            if(demo)DashboardDemo.record(readings,shown,SystemClock.elapsedRealtime()-demoStarted);
            refreshIfChanged();
            handler.postDelayed(this,500);
        }
    };

    DashboardCarScreen(CarContext context){
        this(context,new PageState(),new DashboardReadings());
    }

    private DashboardCarScreen(CarContext context,PageState state,DashboardReadings readings){
        super(context);
        this.state=state;
        animate=()->{
            if(!visible)return;
            computeFrame();
            surface.redraw();
        };
        this.readings=readings;
        demo=(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        preferences=new DashboardPreferences(context);
        surface.setPainter((canvas,w,h)->{
            if(zoom>=0&&zoom<frameCells.size())
                gauge.drawSingle(canvas,w,h,frameCells.get(zoom),frameValues.get(zoom),
                    frameUnits.get(zoom),frameScales.get(zoom),framePositions.get(zoom));
            else gauge.drawPanel(canvas,w,h,frameCells,frameValues,frameUnits,frameScales,framePositions);
        });
        surface.setClicks((x,y)->{
            if(inputLocked())return;
            if(zoom>=0){
                zoom=-1;
                computeFrame();
                surface.redraw();
                return;
            }
            int cell=DashboardTouch.cellAt(x,y,surface.viewWidth(),surface.viewHeight());
            if(cell>=0)shiftPage(DashboardTouch.pageAction(cell));
        });
        surface.setScales((x,y,factor)->{
            if(inputLocked())return;
            if(factor>1f){
                int cell=DashboardTouch.cellAt(x,y,surface.viewWidth(),surface.viewHeight());
                if(cell>=0)zoom=cell;
            }else zoom=-1;
            computeFrame();
            surface.redraw();
        });
        getLifecycle().addObserver(new DefaultLifecycleObserver(){
            @Override public void onStart(LifecycleOwner owner){
                visible=true;
                startedAt=SystemClock.elapsedRealtime();
                getCarContext().getCarService(AppManager.class).setSurfaceCallback(surface);
                checkScreenState.run();
                invalidate();
            }
            @Override public void onStop(LifecycleOwner owner){
                visible=false;
                zoom=-1;
                handler.removeCallbacks(checkScreenState);
                handler.removeCallbacks(refreshValues);
                handler.removeCallbacks(animate);
                needles.clear();
                updateQueued.set(false);
                detach();
            }
        });
    }

    private List<DashboardCatalog.Parameter> configured(){
        List<DashboardCatalog.Parameter> configured=DashboardCatalog.gridCells(preferences.page(state.page));
        return demo&&DashboardDemo.isEmpty(configured)?DashboardDemo.page(state.page):configured;
    }

    private void ensureConnection(){
        if(demo)return;
        CarContext context=getCarContext();
        android.content.SharedPreferences settings=AdapterPicker.settings(context);
        boolean selected=!settings.getString("address","").isEmpty();
        boolean permitted=AdapterPicker.hasPermissions(context);
        boolean wanted=settings.getBoolean("connectionWanted",true);
        ConnectionService current=ConnectionService.current;
        boolean active=current!=null&&current.active();
        if(!selected)connectionProblem="Выбери адаптер на телефоне";
        else if(!permitted)connectionProblem="Разреши Bluetooth на телефоне";
        else if(!wanted)connectionProblem="Подключи адаптер на телефоне";
        else if(active)connectionProblem=current.bluetoothConnected()?"":current.message;
        if(!connectionRetry.shouldStart(SystemClock.elapsedRealtime(),active,wanted,selected,permitted))return;
        try{
            ConnectionService.startConnection(context,false);
            connectionProblem="Подключаемся";
        }catch(RuntimeException e){
            connectionProblem="Открой подключение на телефоне";
            android.util.Log.w("YetiConnection","Не удалось запустить подключение с экрана автомобиля",e);
        }
    }

    private void synchronizePage(){
        List<DashboardCatalog.Parameter> next=configured();
        ConnectionService current=ConnectionService.current;
        if(current==connection&&next.equals(shown))return;
        boolean hasParameters=next.stream().anyMatch(Objects::nonNull);
        if(current!=connection||!hasParameters)detach();
        needles.clear();
        shown=next;
        connection=current;
        if(connection!=null&&hasParameters){
            plan=new DashboardReadings.Plan(shown,this::onSample);
            connection.showDashboard(plan);
        }
    }

    private void onSample(String mode,Engine.Row row){
        if(readings.record(mode,row)&&updateQueued.compareAndSet(false,true))
            handler.post(refreshValues);
    }

    private void refreshIfChanged(){
        List<String> next=visibleText();
        if(next.equals(lastText))return;
        lastText=next;
        computeFrame();
        surface.redraw();
    }

    private void detach(){
        if(connection!=null&&plan!=null)connection.hideDashboard(plan);
        connection=null;
        plan=null;
    }

    private List<String> visibleText(){
        List<String> text=new ArrayList<>();
        boolean connected=adapterConnected();
        long now=System.currentTimeMillis();
        for(DashboardCatalog.Parameter parameter:shown){
            text.add(parameter==null?"":parameter.id);
            if(parameter!=null){
                DashboardReadings.Sample sample=readings.sample(parameter.id);
                text.add(currentReading(sample,connected,now));
                DashboardReadings.Sample scale=activeScale(sample,connected,now);
                if(scale!=null){
                    text.add(Integer.toString(scale.scalePosition));
                    text.add(scale.minimum);
                    text.add(scale.maximum);
                }
            }
        }
        return text;
    }

    private boolean adapterConnected(){
        if(demo)return true;
        ConnectionService service=ConnectionService.current;
        return service!=null&&service.bluetoothConnected();
    }

    private static String readingText(DashboardReadings.Sample sample,boolean connected,long now){
        if(!connected)return "Нет связи";
        if(sample==null)return "Ждём данные";
        if(sample.value.equals("Ответ сохранён"))return "Сырой ответ";
        if(sample.value.equals("Недоступно")||sample.value.equals("—"))return "Нет данных";
        String value=sample.value.replace(" · ","\n");
        return now-sample.time>=DashboardReadings.STALE_AFTER_MS?value+"\nУстар.":value;
    }

    private String currentReading(DashboardReadings.Sample sample,boolean connected,long now){
        if(!connected&&!connectionProblem.isEmpty())return connectionProblem;
        return readingText(sample,connected,now);
    }

    private static DashboardReadings.Sample activeScale(DashboardReadings.Sample sample,boolean connected,long now){
        if(!connected||sample==null||sample.scalePosition==DashboardReadings.NO_SCALE
            ||now-sample.time>=DashboardReadings.STALE_AFTER_MS)return null;
        return sample;
    }

    private boolean computeFrame(){
        boolean connected=adapterConnected();
        long now=System.currentTimeMillis();
        long frameTime=SystemClock.uptimeMillis();
        List<DashboardCatalog.Parameter> cells=configured();
        List<String> values=new ArrayList<>();
        List<String> units=new ArrayList<>();
        List<DashboardReadings.Sample> scales=new ArrayList<>();
        List<Integer> positions=new ArrayList<>();
        boolean moving=false;
        for(DashboardCatalog.Parameter parameter:cells){
            DashboardReadings.Sample sample=parameter==null?null:readings.sample(parameter.id);
            DashboardReadings.Sample scale=activeScale(sample,connected,now);
            values.add(currentReading(sample,connected,now));
            units.add(sample==null?"":sample.unit);
            scales.add(scale);
            int position=DashboardReadings.NO_SCALE;
            if(parameter!=null){
                DashboardNeedle needle=needles.computeIfAbsent(parameter.id,id->new DashboardNeedle());
                needle.moveTo(scale==null?DashboardReadings.NO_SCALE:scale.scalePosition,frameTime);
                position=Math.round(needle.positionAt(frameTime));
                moving|=needle.running(frameTime);
            }
            positions.add(position);
        }
        frameCells=cells;
        frameValues=values;
        frameUnits=units;
        frameScales=scales;
        framePositions=positions;
        handler.removeCallbacks(animate);
        // Keep at most one pending frame. The host may throttle or discard intermediate templates.
        if(visible&&moving)handler.postDelayed(animate,100);
        return moving;
    }

    @Override public Template onGetTemplate(){
        computeFrame();
        surface.redraw();
        // The navigation template is only a host for the surface; it still demands one action.
        return new NavigationTemplate.Builder()
            .setActionStrip(new ActionStrip.Builder()
                .addAction(new Action.Builder().setIcon(gauge.control((state.page+1)+"/3"))
                    .setOnClickListener(this::nextPage).build())
                .build())
            .build();
    }

    // A tap that switches screens can land on the new screen as well; ignore that echo.
    private boolean inputLocked(){return SystemClock.elapsedRealtime()-startedAt<500;}

    private void shiftPage(int delta){
        if(delta==0||getScreenManager().getTop()!=this||inputLocked()||zoom>=0)return;
        state.page=DashboardTouch.shifted(state.page,delta);
        synchronizePage();
        needles.clear();
        lastText=visibleText();
        computeFrame();
        surface.redraw();
        // Same template type and titles: a refresh rather than another navigation step.
        invalidate();
    }

    private void nextPage(){shiftPage(1);}
}
