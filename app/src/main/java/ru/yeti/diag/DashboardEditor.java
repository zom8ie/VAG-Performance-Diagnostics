package ru.yeti.diag;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.util.List;

import static ru.yeti.diag.UiWidgets.*;

/** Phone-only editor for the three saved dashboard pages. */
final class DashboardEditor {
    private final UiWidgets ui;
    private final DashboardPreferences preferences;
    private final LinearLayout container;
    private int page;

    DashboardEditor(UiWidgets ui,LinearLayout parent,int initialPage){
        this.ui=ui;
        preferences=new DashboardPreferences(ui.activity);
        container=new LinearLayout(ui.activity);
        container.setOrientation(LinearLayout.VERTICAL);
        parent.addView(container);
        showPage(initialPage);
    }

    int page(){return page;}

    private void showPage(int index){
        page=index;
        container.removeAllViews();
        LinearLayout tabs=new LinearLayout(ui.activity);
        container.addView(tabs);
        for(int i=0;i<DashboardCatalog.PAGES;i++){
            int target=i;
            Button tab=ui.blockActionButton("Страница "+(i+1));
            tab.setSelected(i==page);
            tab.setStateDescription(i==page?"Выбрана":null);
            if(i==page)tab.setBackground(ui.ripple(0x33006B50,ui.panel(0xFFBFE0D0,16),16));
            tab.setOnClickListener(view->showPage(target));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,ui.dp(48),1);
            if(i>0)params.setMarginStart(ui.dp(6));
            tabs.addView(tab,params);
        }
        List<DashboardCatalog.Parameter> parameters=preferences.page(page);
        FrameLayout grid=new SwipeGrid();
        container.addView(grid);
        LinearLayout rows=new LinearLayout(ui.activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        grid.addView(rows,new FrameLayout.LayoutParams(-1,-2));
        for(int row=0;row<DashboardCatalog.ROWS;row++){
            LinearLayout line=new LinearLayout(ui.activity);
            rows.addView(line);
            for(int column=0;column<DashboardCatalog.COLUMNS;column++){
                int cell=row*DashboardCatalog.COLUMNS+column;
                addCell(line,cell,parameters.get(cell));
            }
        }
    }

    private void addCell(LinearLayout line,int cell,DashboardCatalog.Parameter parameter){
        View view;
        if(parameter==null){
            ImageButton plus=ui.iconButton(R.drawable.ic_add,"Добавить параметр, ячейка "+(cell+1),GREEN,false);
            plus.setScaleType(ImageView.ScaleType.CENTER);
            plus.setOnClickListener(v->chooseBlock(cell));
            view=plus;
        }else{
            Button button=ui.labelButton(parameter.name,13,TEXT);
            button.setPadding(ui.dp(5),ui.dp(4),ui.dp(5),ui.dp(4));
            button.setMaxLines(4);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setOnClickListener(v->new AlertDialog.Builder(ui.activity)
                .setTitle(parameter.name).setMessage(parameter.description)
                .setPositiveButton("Закрыть",null).show());
            button.setOnLongClickListener(v->{confirmRemoval(cell,parameter);return true;});
            view=button;
        }
        view.setBackground(ui.ripple(0x22006B50,ui.panel(parameter==null?CARD:TONAL,16),16));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,ui.dp(96),1);
        params.topMargin=ui.dp(8);
        if(cell%DashboardCatalog.COLUMNS!=0)params.setMarginStart(ui.dp(8));
        line.addView(view,params);
    }

    private void chooseBlock(int cell){
        int editedPage=page;
        String[] labels=new String[DashboardCatalog.BLOCKS.length];
        for(int i=0;i<labels.length;i++)labels[i]=DashboardCatalog.blockTitle(DashboardCatalog.BLOCKS[i]);
        new AlertDialog.Builder(ui.activity).setTitle("Выбрать блок")
            .setItems(labels,(dialog,index)->chooseParameter(editedPage,cell,DashboardCatalog.BLOCKS[index]))
            .setNegativeButton("Отмена",null).show();
    }

    private void chooseParameter(int editedPage,int cell,String mode){
        List<DashboardCatalog.Parameter> choices=DashboardCatalog.forBlock(mode);
        LinearLayout content=new LinearLayout(ui.activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding=ui.dp(20);
        content.setPadding(padding,0,padding,0);
        EditText search=new EditText(ui.activity);
        search.setSingleLine(true);
        search.setHint("Поиск по названию параметра");
        search.setContentDescription("Поиск параметра");
        content.addView(search,new LinearLayout.LayoutParams(-1,-2));
        ListView list=new ListView(ui.activity);
        content.addView(list,new LinearLayout.LayoutParams(-1,ui.dp(360)));
        List<DashboardCatalog.Parameter> visible=new java.util.ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<>(ui.activity,android.R.layout.simple_list_item_2,
            android.R.id.text1,new java.util.ArrayList<>());
        adapter.setDropDownViewResource(android.R.layout.simple_list_item_2);
        list.setAdapter(adapter);
        Runnable update=()->{
            visible.clear();
            visible.addAll(DashboardCatalog.search(choices,search.getText().toString()));
            adapter.clear();
            for(DashboardCatalog.Parameter parameter:visible)adapter.add(parameter.name+"\n"+parameter.description);
            adapter.notifyDataSetChanged();
        };
        update.run();
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){update.run();}
            public void afterTextChanged(Editable value){}
        });
        AlertDialog dialog=new AlertDialog.Builder(ui.activity).setTitle(DashboardCatalog.blockTitle(mode))
            .setView(content).setNegativeButton("Отмена",null).create();
        list.setOnItemClickListener((parent,view,index,id)->{
            preferences.set(editedPage,cell,visible.get(index));
            dialog.dismiss();
            showPage(page);
        });
        dialog.show();
    }

    private void confirmRemoval(int cell,DashboardCatalog.Parameter parameter){
        int editedPage=page;
        new AlertDialog.Builder(ui.activity).setTitle("Удалить параметр?")
            .setMessage(parameter.name).setNegativeButton("Отмена",null)
            .setPositiveButton("Удалить",(dialog,which)->{preferences.set(editedPage,cell,null);showPage(page);}).show();
    }

    private final class SwipeGrid extends FrameLayout {
        private float startX,startY;
        SwipeGrid(){super(ui.activity);}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                startX=event.getX();
                startY=event.getY();
            }
            return event.getActionMasked()==MotionEvent.ACTION_MOVE
                && Math.abs(event.getX()-startX)>ui.dp(18)
                && Math.abs(event.getX()-startX)>Math.abs(event.getY()-startY)*1.5f;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_UP){
                float distance=event.getX()-startX;
                int next=page+(distance<0?1:-1);
                if(Math.abs(distance)>ui.dp(48)&&next>=0&&next<DashboardCatalog.PAGES)showPage(next);
            }
            return true;
        }
    }
}
