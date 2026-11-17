package ru.yeti.diag;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BiConsumer;

final class JournalPanel {
    private final UiWidgets ui;
    private final BiConsumer<String,String> alert;

    JournalPanel(UiWidgets ui,BiConsumer<String,String> alert){
        this.ui=ui;
        this.alert=alert;
    }

    void show(LinearLayout body){
        File[] sessions=Journal.sessions(ui.activity);
        if(sessions.length==0){
            ui.messageCard(body,R.drawable.ic_journal,"Журналов пока нет","Первый журнал появится после подключения к адаптеру.");
            return;
        }
        for(File file:sessions){
            LinearLayout row=ui.cardRow(body,R.drawable.ic_journal,"Дневной журнал",file.getName());
            ImageButton share=ui.iconButton(R.drawable.ic_share,"Поделиться журналом "+file.getName(),UiWidgets.GREEN,false);
            share.setOnClickListener(view->share(file));
            row.addView(share,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        }
    }

    private void share(File file){
        new Thread(()->{
            try{
                File dir=new File(ui.activity.getCacheDir(),"shared");
                dir.mkdirs();
                File shared=new File(dir,file.getName());
                ConnectionService service=ConnectionService.current;
                Journal journal=service==null?null:service.journal;
                if(journal!=null&&journal.file.equals(file)){
                    synchronized(journal){
                        journal.flush();
                        export(file,shared);
                    }
                }else export(file,shared);
                Uri uri=Uri.parse("content://"+ui.activity.getPackageName()+".files/shared/"+Uri.encode(shared.getName()));
                Intent send=new Intent(Intent.ACTION_SEND).setType("application/x-ndjson")
                    .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri("journal",uri));
                ui.activity.runOnUiThread(()->ui.activity.startActivity(Intent.createChooser(send,"Поделиться журналом")));
            }catch(Exception e){
                ui.activity.runOnUiThread(()->alert.accept("Не удалось подготовить журнал",e.getMessage()));
            }
        },"journal-share").start();
    }

    private void export(File source,File target)throws IOException{
        try(OutputStream out=new FileOutputStream(target)){
            Journal.export(source,out);
        }
    }
}
