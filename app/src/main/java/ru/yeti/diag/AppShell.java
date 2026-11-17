package ru.yeti.diag;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;

import java.util.function.Consumer;

import static ru.yeti.diag.UiWidgets.*;

/** Persistent header, scroll container and bottom navigation. */
final class AppShell {
    private final Activity activity;
    private final UiWidgets ui;
    private final Runnable onToggleConnection,onFullAnalysis,onPartialAnalysis,onJournals,onTest,onDashboard;
    private LinearLayout root,footer,bottomBar;
    LinearLayout body;
    ScrollView scroll;
    private TextView headerTitle;
    private ImageButton headerSearch,dashboard;
    private EditText headerSearchField;
    private Consumer<String> searchListener;
    private ConnectionToggle connectionToggle;
    private Button testBottomButton;
    private boolean syncingToggle;
    private int switchColor;

    AppShell(UiWidgets ui,Runnable onToggleConnection,Runnable onFullAnalysis,
             Runnable onPartialAnalysis,Runnable onJournals,Runnable onTest,Runnable onDashboard){
        this.ui=ui;
        this.activity=ui.activity;
        this.onToggleConnection=onToggleConnection;
        this.onFullAnalysis=onFullAnalysis;
        this.onPartialAnalysis=onPartialAnalysis;
        this.onJournals=onJournals;
        this.onTest=onTest;
        this.onDashboard=onDashboard;
        build();
    }

    private void build(){
        root=new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            return insets;
        });
        root.addView(buildHeader(),new LinearLayout.LayoutParams(-1,-2));

        scroll=new ScrollView(activity);
        scroll.setFillViewport(true);
        body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20),ui.dp(16),ui.dp(20),ui.dp(16));
        scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        buildBottomBar();
        activity.setContentView(root);
        root.requestApplyInsets();
    }

    private LinearLayout buildHeader(){
        LinearLayout header=new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(4));
        header.setBackgroundColor(CARD);
        LinearLayout controls=new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        headerTitle=ui.text("Yeti Diagnostics",22,TEXT);
        headerTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        controls.addView(headerTitle,new LinearLayout.LayoutParams(0,-2,1));

        headerSearchField=new EditText(activity);
        headerSearchField.setHint("Поиск параметра");
        headerSearchField.setSingleLine(true);
        headerSearchField.setTextSize(16);
        headerSearchField.setTextColor(TEXT);
        headerSearchField.setHintTextColor(MUTED);
        headerSearchField.setContentDescription("Поиск по названию параметра");
        headerSearchField.setVisibility(View.GONE);
        controls.addView(headerSearchField,new LinearLayout.LayoutParams(0,-2,1));
        headerSearchField.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){if(searchListener!=null)searchListener.accept(value.toString());}
            public void afterTextChanged(Editable value){}
        });

        headerSearch=ui.iconButton(R.drawable.ic_search,"Поиск параметров",GREEN,true);
        headerSearch.setBackground(ui.ripple(0x19006B50,ui.panel(TONAL,20),20));
        headerSearch.setOnClickListener(view->toggleHeaderSearch());
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));
        searchParams.setMarginEnd(ui.dp(8));
        controls.addView(headerSearch,searchParams);
        headerSearch.setVisibility(View.GONE);

        dashboard=ui.iconButton(R.drawable.ic_android_auto,"Настройки Android Auto",Color.WHITE,true);
        dashboard.setBackground(ui.ripple(0x33FFFFFF,ui.panel(GREEN,20),20));
        dashboard.setPadding(ui.dp(9),ui.dp(9),ui.dp(9),ui.dp(9));
        dashboard.setOnClickListener(view->onDashboard.run());
        LinearLayout.LayoutParams dashboardParams=new LinearLayout.LayoutParams(ui.dp(52),ui.dp(52));
        dashboardParams.setMarginEnd(ui.dp(8));
        controls.addView(dashboard,dashboardParams);

        connectionToggle=new ConnectionToggle(activity,ui);
        connectionToggle.setOnChanged(checked->{
            if(syncingToggle)return;
            onToggleConnection.run();
        });
        controls.addView(connectionToggle,new LinearLayout.LayoutParams(ui.dp(64),ui.dp(40)));
        header.addView(controls,new LinearLayout.LayoutParams(-1,-2));

        return header;
    }

    private void buildBottomBar(){
        footer=new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(CARD);
        footer.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),0);
        root.addView(footer,new LinearLayout.LayoutParams(-1,-2));

        bottomBar=new LinearLayout(activity);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(bottomBar,new LinearLayout.LayoutParams(-1,-2));
        addBottomAction("Анализ",R.drawable.ic_scan,onFullAnalysis);
        addBottomAction("Новьё",R.drawable.ic_spark,onPartialAnalysis);
        addBottomAction("Журнал",R.drawable.ic_journal,onJournals);

        LinearLayout footerInfo=new LinearLayout(activity);
        footerInfo.setGravity(Gravity.CENTER_VERTICAL);
        TextView version=ui.text("Версия "+Journal.appVersion(activity),12,MUTED);
        footerInfo.addView(version,new LinearLayout.LayoutParams(0,-2,1));
        testBottomButton=ui.labelButton("Тест",14,MUTED);
        testBottomButton.setBackground(ui.ripple(0x19006B50,null,24));
        testBottomButton.setPadding(ui.dp(12),0,ui.dp(12),0);
        testBottomButton.setOnClickListener(v->onTest.run());
        footerInfo.addView(testBottomButton,new LinearLayout.LayoutParams(-2,-2));
        footer.addView(footerInfo,new LinearLayout.LayoutParams(-1,-2));
    }

    private void addBottomAction(String label,int icon,Runnable action){
        int ink=GREEN;
        Button button=ui.labelButton(label,14,ink);
        button.setGravity(Gravity.CENTER);
        button.setPadding(ui.dp(2),ui.dp(8),ui.dp(2),ui.dp(8));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0,icon,0,0);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
        button.setCompoundDrawablePadding(ui.dp(3));
        button.setBackground(ui.ripple(0x19006B50,ui.panel(Color.WHITE,18),18));
        button.setOnClickListener(v->action.run());
        // All three actions occupy the same fixed-height cell, even when their labels differ.
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,ui.dp(64),1);
        if(bottomBar.getChildCount()>0)params.setMarginStart(ui.dp(8));
        bottomBar.addView(button,params);
    }

    void updateHeader(boolean active,boolean linked,boolean adapterSelected,String deviceName,String status,boolean error){
        syncingToggle=true;
        connectionToggle.setChecked(active);
        syncingToggle=false;
        connectionToggle.setContentDescription("Связь с адаптером");
        connectionToggle.setStateDescription(linked?"Подключено":active?"Подключение":"Отключено");
        int color=linked?ACCENT:active?AMBER:RED;
        if(switchColor!=color){
            switchColor=color;
            connectionToggle.setTrackColor(color);
        }
    }
    void updateHeaderNavigation(boolean home,Runnable openHome){
        if(home){
            headerTitle.setText("Yeti Diagnostics");
            headerTitle.setTextSize(22);
            headerTitle.setTextColor(TEXT);
            headerTitle.setPadding(0,ui.dp(5),0,ui.dp(5));
            headerTitle.setBackground(null);
            headerTitle.setOnClickListener(null);
            headerTitle.setClickable(false);
            headerTitle.setContentDescription("Yeti Diagnostics");
        }else{
            headerTitle.setText("‹  Главное меню");
            headerTitle.setTextSize(18);
            headerTitle.setTextColor(GREEN);
            headerTitle.setPadding(ui.dp(4),ui.dp(5),ui.dp(12),ui.dp(5));
            headerTitle.setBackground(ui.ripple(0x19006B50,null,20));
            headerTitle.setOnClickListener(view->openHome.run());
            headerTitle.setClickable(true);
            headerTitle.setContentDescription("Вернуться в главное меню");
        }
    }
    void setHeaderSearchListener(Consumer<String> listener){
        searchListener=listener;
        headerSearch.setVisibility(listener==null?View.GONE:View.VISIBLE);
        if(listener==null)closeHeaderSearch();
    }
    private void toggleHeaderSearch(){
        if(headerSearchField.getVisibility()==View.VISIBLE)closeHeaderSearch();
        else {
            headerTitle.setVisibility(View.GONE);
            dashboard.setVisibility(View.GONE);
            headerSearchField.setVisibility(View.VISIBLE);
            headerSearch.setContentDescription("Скрыть поиск параметров");
            headerSearchField.requestFocus();
            ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE))
                .showSoftInput(headerSearchField,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }
    private void closeHeaderSearch(){
        if(headerSearchField==null)return;
        headerSearchField.setText("");
        headerSearchField.clearFocus();
        headerSearchField.setVisibility(View.GONE);
        headerTitle.setVisibility(View.VISIBLE);
        dashboard.setVisibility(View.VISIBLE);
        if(headerSearch!=null)headerSearch.setContentDescription("Поиск параметров");
        ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(headerSearchField.getWindowToken(),0);
    }
    void updateBottomBar(boolean home,boolean disconnected,boolean interfaceTest){
        footer.setVisibility(home?View.VISIBLE:View.GONE);
        testBottomButton.setVisibility(disconnected?View.VISIBLE:View.GONE);
        String label=interfaceTest?"Выйти из теста":"Тест";
        if(!TextUtils.equals(testBottomButton.getText(),label))testBottomButton.setText(label);
    }

    /** A fixed-geometry switch: its track is intentionally taller than the thumb. */
    private static final class ConnectionToggle extends View {
        private final UiWidgets ui;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track=new RectF();
        private boolean checked;
        private int trackColor=RED;
        private java.util.function.Consumer<Boolean> onChanged;

        ConnectionToggle(Activity activity,UiWidgets ui){
            super(activity);this.ui=ui;
            setClickable(true);
            setFocusable(true);
            setContentDescription("Связь с адаптером");
            setAccessibilityDelegate(new AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(view,info);
                    info.setClassName(android.widget.Switch.class.getName());
                    info.setCheckable(true);info.setChecked(checked);
                }
            });
        }
        void setOnChanged(java.util.function.Consumer<Boolean> listener){onChanged=listener;}
        void setChecked(boolean value){if(checked!=value){checked=value;invalidate();}}
        void setTrackColor(int color){if(trackColor!=color){trackColor=color;invalidate();}}
        @Override public boolean performClick(){
            super.performClick();
            if(onChanged!=null)onChanged.accept(!checked);
            return true;
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent event){
            if(event.getAction()==android.view.MotionEvent.ACTION_UP&&isEnabled())performClick();
            return true;
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float trackHeight=ui.dp(32),thumbDiameter=ui.dp(20),centerY=getHeight()/2f;
            track.set(0,centerY-trackHeight/2f,getWidth(),centerY+trackHeight/2f);
            paint.setColor(trackColor);canvas.drawRoundRect(track,trackHeight/2f,trackHeight/2f,paint);
            float thumbX=checked?getWidth()-trackHeight/2f:trackHeight/2f;
            paint.setColor(Color.WHITE);canvas.drawCircle(thumbX,centerY,thumbDiameter/2f,paint);
        }
    }
}
