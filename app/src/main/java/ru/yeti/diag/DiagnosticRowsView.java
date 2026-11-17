package ru.yeti.diag;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import static ru.yeti.diag.UiWidgets.*;

/** Renders diagnostic rows, identity fields and fault results. */
final class DiagnosticRowsView {
    private final UiWidgets ui;
    private final BiConsumer<String,String> alerts;
    private final BiConsumer<String,String> showFaults;
    private final ParameterSelection parameterSelection;
    private LinearLayout rowsBox;
    private TextView faultResult;
    private long shownEpoch=-1;
    private final Map<String,TextView[]> rowViews=new LinkedHashMap<>();
    private final Map<String,LinearLayout> rowCards=new LinkedHashMap<>();
    private final Map<String,Switch> rowTrackingToggles=new LinkedHashMap<>();
    private final Map<String,LinearLayout> rowTrackingRows=new LinkedHashMap<>();
    private final Map<String,TextView> rowTrackingStatuses=new LinkedHashMap<>();
    private final Map<String,TextView> identityViews=new LinkedHashMap<>();
    private final Set<String> expandedRows=new HashSet<>();
    private String shownFaults="";
    private String searchQuery="";
    private List<Engine.Row> renderedRows=Collections.emptyList();
    private String renderedMode="";

    DiagnosticRowsView(UiWidgets ui,BiConsumer<String,String> alerts,BiConsumer<String,String> showFaults,ParameterSelection parameterSelection){this.ui=ui;this.alerts=alerts;this.showFaults=showFaults;this.parameterSelection=parameterSelection;}

    void reset(){
        rowViews.clear();
        rowCards.clear();
        rowTrackingToggles.clear();
        rowTrackingRows.clear();
        rowTrackingStatuses.clear();
        identityViews.clear();
        expandedRows.clear();
        shownFaults="";
        searchQuery="";
        renderedRows=Collections.emptyList();
        renderedMode="";
        faultResult=null;
        rowsBox=null;
        shownEpoch=-1;
    }
    void attach(LinearLayout parent){
        rowsBox=new LinearLayout(ui.activity);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        parent.addView(rowsBox);
    }
    void clearMeasurements(){
        rowViews.clear();
        rowCards.clear();
        rowTrackingToggles.clear();
        rowTrackingRows.clear();
        rowTrackingStatuses.clear();
        expandedRows.clear();
        renderedRows=Collections.emptyList();
        if(rowsBox!=null)rowsBox.removeAllViews();
    }
    boolean isAttached(){return rowsBox!=null;}
    /** Receives text from the persistent header search control. */
    void setSearchQuery(String value){
        searchQuery=searchText(value);
        applySearch();
    }
    /** Uses the same interactive cards for the demonstration data as for live measurements. */
    void renderExamples(String mode,List<Engine.Row> examples){
        render(mode,"Все",0,false,()->examples);
    }
    void attachFaultResult(LinearLayout parent){
        faultResult=ui.text("",15,TEXT);
        faultResult.setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(10));
        faultResult.setBackground(ui.panel(CARD,16));
        faultResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        faultResult.setVisibility(View.GONE);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin=ui.dp(8);
        parent.addView(faultResult,params);
    }

    void showIdentityCard(LinearLayout body,String chosen,boolean interfaceTest,java.util.function.Consumer<Boolean> readConfig,Runnable readFaults){
        LinearLayout card=new LinearLayout(ui.activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
        card.setBackground(ui.panel(CARD,16));
        TextView heading=ui.text(OfflinePreview.title(chosen),17,TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        card.addView(heading);
        addIdentityField(card,"address","Диагностический адрес",VehicleData.blockAddress(chosen));
        for(String did:VehicleData.CORE_ID_FIELDS){
            String label=VehicleData.identityName(did).replace(" ("+did+")","");
            addIdentityField(card,"id"+did,label,interfaceTest?"Пример":"—");
        }
        LinearLayout actions=new LinearLayout(ui.activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addBlockAction(actions,"Кодировки",()->showConfigResult(interfaceTest,readConfig,false));
        addBlockAction(actions,"Параметрии",()->showConfigResult(interfaceTest,readConfig,true));
        addBlockAction(actions,"Ошибки",readFaults);
        LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,ui.dp(48));
        actionsParams.topMargin=ui.dp(6);
        card.addView(actions,actionsParams);
        addConfigResult(card,"coding");
        addConfigResult(card,"dataset");
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);
        cardParams.bottomMargin=ui.dp(12);
        body.addView(card,cardParams);
    }
    private void addBlockAction(LinearLayout actions,String label,Runnable action){
        Button button=ui.blockActionButton(label);
        button.setOnClickListener(view->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);
        if(actions.getChildCount()>0)params.setMarginStart(ui.dp(6));
        actions.addView(button,params);
    }
    private void showConfigResult(boolean interfaceTest,java.util.function.Consumer<Boolean> readConfig,boolean dataset){
        String key=dataset?"dataset":"coding";
        TextView result=identityViews.get(key);
        if(result==null)return;
        if(interfaceTest){
            result.setText(dataset?"Параметрия блока:\nНомер набора: пример\nВерсия набора: 0001":"Кодировка блока:\n00 12 34");
            result.setVisibility(View.VISIBLE);
        }else{
            result.setText(dataset?"Считывается параметрия блока…":"Считывается кодировка блока…");
            result.setVisibility(View.VISIBLE);
            readConfig.accept(dataset);
        }
    }
    private void addConfigResult(LinearLayout card,String key){
        TextView result=ui.text("",13,TEXT);
        result.setTextIsSelectable(true);
        result.setVisibility(View.GONE);
        card.addView(result,new LinearLayout.LayoutParams(-1,-2));
        identityViews.put(key,result);
    }
    private void addIdentityField(LinearLayout card,String key,String name,String value){
        TextView[] fields=measurementFields(card,name);
        fields[1].setText(value);
        fields[2].setVisibility(View.GONE);
        identityViews.put(key,fields[1]);
    }
    void exampleRow(String name,String value,String unit){
        exampleRow(name,value,unit,"");
    }
    void exampleRow(String name,String value,String unit,String description){
        LinearLayout item=measurementCard();
        TextView[] fields=measurementFields(item,name);
        fields[1].setText(value+(unit.isEmpty()?"":" "+unit));
        final String descriptionText=VehicleData.parameterDescription("",name,"",description);
        fields[2].setText(descriptionText);
        TextView collapse=addCollapseAction(item);
        fields[2].setVisibility(View.GONE);
        collapse.setVisibility(View.GONE);
        item.setOnClickListener(view->{
            boolean expanded=fields[2].getVisibility()!=View.VISIBLE;
            fields[2].setVisibility(expanded&&!descriptionText.isEmpty()?View.VISIBLE:View.GONE);
            collapse.setVisibility(expanded?View.VISIBLE:View.GONE);
        });
    }

    private LinearLayout measurementCard(){
        LinearLayout item=new LinearLayout(ui.activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
        item.setBackground(ui.panel(CARD,16));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin=ui.dp(8);
        rowsBox.addView(item,params);
        return item;
    }
    private TextView[] measurementFields(LinearLayout item,String label){
        LinearLayout top=new LinearLayout(ui.activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=ui.text(label,15,TEXT);
        top.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView value=ui.text("",18,GREEN);
        value.setGravity(Gravity.END);
        value.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        int availableWidth=ui.activity.getResources().getDisplayMetrics().widthPixels-ui.dp(72);
        value.setMaxWidth(Math.min(ui.dp(170),Math.max(ui.dp(64),availableWidth*2/5)));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);
        params.setMarginStart(ui.dp(12));
        top.addView(value,params);
        item.addView(top);
        TextView note=ui.text("",12,MUTED);
        note.setPadding(0,0,0,ui.dp(4));
        item.addView(note);
        return new TextView[]{name,value,note};
    }
    private TextView addCollapseAction(LinearLayout item){
        TextView collapse=ui.text("Свернуть",14,GREEN);
        collapse.setGravity(Gravity.START);
        collapse.setPadding(0,ui.dp(4),0,0);
        collapse.setOnClickListener(view->{
            View parent=(View)view.getParent();
            if(parent instanceof LinearLayout){
                LinearLayout card=(LinearLayout)parent;
                if(card.getChildCount()>1)card.getChildAt(card.getChildCount()-2).setVisibility(View.GONE);
                view.setVisibility(View.GONE);
            }
        });
        item.addView(collapse);
        return collapse;
    }
    void render(String mode,String filter,long epoch,boolean active,Supplier<List<Engine.Row>> snapshot){
        if(shownEpoch!=epoch){
            rowViews.clear();rowCards.clear();rowTrackingToggles.clear();rowTrackingRows.clear();rowTrackingStatuses.clear();rowsBox.removeAllViews();shownEpoch=epoch;
            expandedRows.clear();shownFaults="";
            for(String did:VehicleData.CORE_ID_FIELDS){
                TextView field=identityViews.get("id"+did);
                if(field!=null)field.setText("—");
            }
            for(String key:new String[]{"coding","dataset"}){
                TextView result=identityViews.get(key);
                if(result!=null)result.setVisibility(View.GONE);
            }
            if(faultResult!=null)faultResult.setVisibility(View.GONE);
        }
        List<Engine.Row> current=snapshot.get();
        renderedRows=new ArrayList<>(current);
        renderedMode=mode;
        for(Engine.Row r:current){
            TextView identity=identityViews.get(r.key);
            if(identity!=null){
                String value=r.key.equals("coding")?"Кодировка блока:\n"+r.value:r.key.equals("dataset")?"Параметрия блока:\n"+r.value:r.value;
                if(!TextUtils.equals(identity.getText(),value))identity.setText(value);
                identity.setVisibility(View.VISIBLE);
                continue;
            }
            if(faultResult!=null&&r.key.equals("faults")){
                String result=r.name+"\n"+r.value;
                if(!TextUtils.equals(faultResult.getText(),result))faultResult.setText(result);
                faultResult.setVisibility(View.VISIBLE);
                if(FaultCatalog.hasCodes(r.value)&&!shownFaults.equals(result)){
                    shownFaults=result;
                    showFaults.accept(r.name,r.value);
                }
                continue;
            }
            boolean powerInAir=r.key.equals("maf_power")&&filter.equals("Воздух и наддув");
            if(mode.equals("OBD")&&!filter.equals("Все")&&!r.group.equals(filter)&&!r.group.equals("Информация")&&!powerInAir)continue;
            TextView[] views=rowViews.get(r.key);
            if(views==null){
                LinearLayout item=measurementCard();
                item.setBackground(ui.ripple(0x22006B50,ui.panel(CARD,16),16));
                TextView[] fields=measurementFields(item,r.name);
                Switch trackingToggle=null;
                if(isSelectableParameter(r)){
                    LinearLayout trackingRow=new LinearLayout(ui.activity);
                    trackingRow.setGravity(Gravity.CENTER_VERTICAL);
                    TextView trackingLabel=ui.text("Отслеживать",14,MUTED);
                    trackingLabel.setPadding(0,ui.dp(3),0,ui.dp(3));
                    trackingRow.addView(trackingLabel,new LinearLayout.LayoutParams(0,-2,1));
                    TextView trackingStatus=ui.text("Вкл",14,GREEN);
                    trackingStatus.setGravity(Gravity.CENTER);
                    trackingStatus.setPadding(0,0,ui.dp(8),0);
                    trackingRow.addView(trackingStatus,new LinearLayout.LayoutParams(-2,-2));
                    trackingToggle=new Switch(ui.activity);
                    trackingToggle.setShowText(false);
                    trackingToggle.setContentDescription("Отслеживать параметр "+r.name);
                    LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(-2,-2);
                    toggleParams.setMarginStart(ui.dp(8));
                    trackingRow.addView(trackingToggle,toggleParams);
                    trackingRow.setVisibility(View.GONE);
                    item.addView(trackingRow,new LinearLayout.LayoutParams(-1,-2));
                    rowTrackingRows.put(r.key,trackingRow);
                    rowTrackingStatuses.put(r.key,trackingStatus);
                }
                TextView collapse=addCollapseAction(item);
                collapse.setVisibility(View.GONE);
                final TextView[] created=new TextView[]{fields[0],fields[1],fields[2],collapse};
                views=created;
                rowViews.put(r.key,created);
                rowCards.put(r.key,item);
                if(trackingToggle!=null){
                    final Switch toggle=trackingToggle;
                    rowTrackingToggles.put(r.key,toggle);
                    toggle.setOnClickListener(view->{
                        boolean enabled=toggle.isChecked();
                        parameterSelection.setEnabled(mode,r.key,enabled);
                        applyParameterState(item,created,r,active,enabled);
                        animateReorderRows(renderedMode);
                    });
                }
                item.setOnClickListener(v->{
                    if(expandedRows.contains(r.key))expandedRows.remove(r.key);else expandedRows.add(r.key);
                    boolean expanded=expandedRows.contains(r.key);
                    created[2].setVisibility(expanded?View.VISIBLE:View.GONE);
                    created[3].setVisibility(expanded?View.VISIBLE:View.GONE);
                });
                collapse.setOnClickListener(v->{
                    expandedRows.remove(r.key);
                    created[2].setVisibility(View.GONE);
                    created[3].setVisibility(View.GONE);
                });
                if(isSelectableParameter(r)){
                    item.setOnTouchListener(new View.OnTouchListener(){
                        private float downX,downY;
                        private boolean horizontal;
                        private final int touchSlop=ViewConfiguration.get(ui.activity).getScaledTouchSlop();
                        @Override public boolean onTouch(View view,android.view.MotionEvent event){
                            if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){
                                downX=event.getX();downY=event.getY();horizontal=false;
                                view.animate().cancel();return true;
                            }
                            if(event.getAction()==android.view.MotionEvent.ACTION_MOVE){
                                float distance=event.getX()-downX;
                                float vertical=event.getY()-downY;
                                if(!horizontal&&Math.abs(distance)>touchSlop&&Math.abs(distance)>Math.abs(vertical)){
                                    horizontal=true;view.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                                if(horizontal){
                                    float offset=Math.max(-ui.dp(48),Math.min(ui.dp(48),distance));
                                    view.setTranslationX(offset);
                                }
                                return true;
                            }
                            if(event.getAction()==android.view.MotionEvent.ACTION_UP){
                                float distance=event.getX()-downX;
                                view.getParent().requestDisallowInterceptTouchEvent(false);
                                if(horizontal&&Math.abs(distance)>=ui.dp(24)){
                                    boolean enabled=distance>0;
                                    parameterSelection.setEnabled(mode,r.key,enabled);
                                    applyParameterState(item,created,r,active,enabled);
                                    Switch toggle=rowTrackingToggles.get(r.key);
                                    if(toggle!=null)toggle.setChecked(enabled);
                                    float offset=enabled?ui.dp(48):-ui.dp(48);
                                    item.animate().translationX(offset).alpha(.82f).setDuration(380).withEndAction(()->{
                                        item.setTranslationX(0);item.setAlpha(1);animateReorderRows(renderedMode);
                                    }).start();
                                }else{
                                    view.animate().translationX(0).setDuration(120).start();
                                    if(!horizontal)view.performClick();
                                }
                                return true;
                            }
                            if(event.getAction()==android.view.MotionEvent.ACTION_CANCEL){
                                view.getParent().requestDisallowInterceptTouchEvent(false);
                                view.animate().translationX(0).setDuration(120).start();
                            }
                            return true;
                        }
                    });
                }
            }
            views[0].setText(r.name);
            LinearLayout card=rowCards.get(r.key);
            if(card!=null)card.setVisibility(matchesSearch(r)?View.VISIBLE:View.GONE);
            if(isSelectableParameter(r)){
                boolean enabled=parameterSelection.isEnabled(mode,r.key);
                applyParameterState(card,views,r,active,enabled);
                Switch toggle=rowTrackingToggles.get(r.key);
                LinearLayout trackingRow=rowTrackingRows.get(r.key);
                if(toggle!=null&&trackingRow!=null){
                    toggle.setChecked(enabled);
                    trackingRow.setVisibility(!searchQuery.isEmpty()&&matchesSearch(r)?View.VISIBLE:View.GONE);
                }
            }
            else {
                views[1].setText(displayValue(r));long age=Math.max(0,(System.currentTimeMillis()-r.time)/1000);boolean stale=active&&age>5;String freshness=!active?" · соединение закрыто":stale?" · "+age+" с назад":"";views[2].setText(detail(r)+freshness);views[2].setVisibility(expandedRows.contains(r.key)||stale?View.VISIBLE:View.GONE);views[3].setVisibility(expandedRows.contains(r.key)?View.VISIBLE:View.GONE);views[1].setTextColor(!active||age>15?MUTED:GREEN);
            }
        }
        reorderRows(mode);
    }
    private boolean matchesSearch(Engine.Row row){
        return searchQuery.isEmpty()||searchText(row.name).contains(searchQuery);
    }
    private void applySearch(){
        for(Map.Entry<String,LinearLayout> entry:rowCards.entrySet()){
            TextView[] fields=rowViews.get(entry.getKey());
            String name=fields==null?"":searchText(fields[0].getText().toString());
            boolean matched=searchQuery.isEmpty()||name.contains(searchQuery);
            entry.getValue().setVisibility(matched?View.VISIBLE:View.GONE);
            LinearLayout trackingRow=rowTrackingRows.get(entry.getKey());
            if(trackingRow!=null)trackingRow.setVisibility(!searchQuery.isEmpty()&&matched?View.VISIBLE:View.GONE);
        }
    }
    private static String searchText(String value){
        return (value==null?"":value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+"," ").trim();
    }
    private boolean isSelectableParameter(Engine.Row row){return ParameterOrder.isSelectable(row);}
    private void applyParameterState(LinearLayout card,TextView[] views,Engine.Row row,boolean active,boolean enabled){
        if(card==null)return;
        TextView trackingStatus=rowTrackingStatuses.get(row.key);
        if(trackingStatus!=null){
            trackingStatus.setText(enabled?"Вкл":"Выкл");
            trackingStatus.setTextColor(enabled?GREEN:RED);
        }
        card.setAlpha(1f);
        GradientDrawable background=ui.panel(enabled?CARD:0xFFF0D8D5,16);
        background.setStroke(ui.dp(2),enabled?GREEN:RED);
        card.setBackground(ui.ripple(0x22006B50,background,16));
        if(!enabled){
            views[1].setText("Выключен");views[1].setTextColor(MUTED);
            views[2].setText("Свайп вправо — включить");views[2].setVisibility(View.VISIBLE);views[3].setVisibility(View.GONE);
            card.setContentDescription(row.name+": выключен. Свайп вправо, чтобы включить.");
            return;
        }
        long age=Math.max(0,(System.currentTimeMillis()-row.time)/1000);boolean stale=active&&age>5;String freshness=!active?" · соединение закрыто":stale?" · "+age+" с назад":"";
        views[1].setText(displayValue(row));views[1].setTextColor(!active||age>15?MUTED:GREEN);
        views[2].setText(detail(row)+freshness);views[2].setVisibility(expandedRows.contains(row.key)||stale?View.VISIBLE:View.GONE);views[3].setVisibility(expandedRows.contains(row.key)?View.VISIBLE:View.GONE);
        card.setContentDescription(row.name+": включен. Свайп влево, чтобы выключить.");
    }
    private String detail(Engine.Row row){return VehicleData.parameterDescription(row.key,row.name,row.group,row.note);}
    private void reorderRows(String mode){
        if(rowsBox==null||renderedRows.isEmpty())return;
        List<LinearLayout> cards=new ArrayList<>();
        for(Engine.Row row:ParameterOrder.displayOrder(renderedRows,mode,parameterSelection)){
            LinearLayout card=rowCards.get(row.key);
            if(card!=null&&!cards.contains(card))cards.add(card);
        }
        for(LinearLayout card:cards)rowsBox.removeView(card);
        for(LinearLayout card:cards)rowsBox.addView(card);
    }
    private void animateReorderRows(String mode){
        if(rowsBox==null||!rowsBox.isLaidOut()){
            reorderRows(mode);
            return;
        }
        TransitionManager.beginDelayedTransition(rowsBox,new ChangeBounds().setDuration(420));
        reorderRows(mode);
    }
    private String displayValue(Engine.Row r){
        if(!r.unit.equals("с"))return r.value+(r.unit.isEmpty()?"":" "+r.unit);
        try{long total=Math.max(0,Math.round(Double.parseDouble(r.value)));long minutes=total/60,seconds=total%60;if(minutes==0)return seconds+" сек";return minutes+" мин "+seconds+" сек";}catch(Exception e){return r.value+" сек";}
    }
}
