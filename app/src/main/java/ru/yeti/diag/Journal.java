package ru.yeti.diag;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Daily, version-specific diagnostic journal. Every line is a JSON record. */
public final class Journal implements Engine.Log, Closeable {
    private static final String JOURNAL_SUFFIX=".jsonl";
    public final File file;
    private final Writer journal;
    private final String runId;
    private final PackageInfo application;
    private final String packageName;
    private long bytes;
    private boolean closed;

    public Journal(Context context) throws IOException {
        cleanup(context);
        File root=new File(context.getFilesDir(),"sessions");root.mkdirs();
        Date now=new Date();
        application=application(context);
        packageName=context.getPackageName();
        file=new File(root,filePrefix(context)+new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(now)+JOURNAL_SUFFIX);
        runId=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(now);
        bytes=file.isFile()?file.length():0;
        journal=new OutputStreamWriter(new FileOutputStream(file,true),StandardCharsets.UTF_8);
        record("run_start","","Yeti; CDAB; DQ250; Haldex 5; app "+application.versionName+"; code "+application.getLongVersionCode()
            +"; package "+packageName+"; "+Build.MANUFACTURER+" "+Build.MODEL+"; Android "+Build.VERSION.RELEASE+"; API "+Build.VERSION.SDK_INT,0);
    }

    public static String appVersion(Context context){return application(context).versionName;}
    private static String filePrefix(Context context){return "v"+appVersion(context)+"-yeti-";}
    private static PackageInfo application(Context context){
        try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0);}
        catch(PackageManager.NameNotFoundException e){throw new IllegalStateException("Не найдена версия установленного приложения",e);}
    }

    /** Remove journals from older versions and retain the latest eight current calendar days. */
    public static void cleanup(Context context) {
        String prefix=filePrefix(context);
        File root=new File(context.getFilesDir(),"sessions");root.mkdirs();
        File[] entries=root.listFiles();
        if(entries==null)return;
        // A journal is tied to the decoder/protocol version that created it.
        // Do not mix old formulas and old transport experiments with a new APK.
        for(File entry:entries)if(!entry.getName().startsWith(prefix))delete(entry);
        File[] current=root.listFiles(entry->entry.isFile()&&entry.getName().startsWith(prefix)&&entry.getName().endsWith(JOURNAL_SUFFIX));
        if(current==null)return;
        Arrays.sort(current,Comparator.comparing(File::getName));
        for(int i=0;i<current.length-7;i++)delete(current[i]);
    }

    @Override public synchronized void record(String kind,String command,String detail,long duration) {
        if(closed)return;
        try{
            JSONObject entry=baseEntry(kind);
            entry.put("command",command);entry.put("detail",detail);entry.put("duration_ms",duration);
            write(entry);
        }catch(Exception e){throw new IllegalStateException("Не удалось записать журнал: "+e.getMessage(),e);}
    }

    public synchronized void value(String mode,String key,String name,String value,String unit,String status){
        if(closed)return;
        try{
            JSONObject entry=baseEntry("value");
            entry.put("mode",mode);entry.put("key",key);entry.put("name",name);entry.put("value",value);entry.put("unit",unit);entry.put("status",status);
            write(entry);
        }catch(Exception e){throw new IllegalStateException("Не удалось сохранить показание",e);}
    }

    private JSONObject baseEntry(String kind)throws org.json.JSONException {
        JSONObject entry=new JSONObject();
        entry.put("utc_ms",System.currentTimeMillis());entry.put("elapsed_ms",SystemClock.elapsedRealtime());
        entry.put("run_id",runId);entry.put("kind",kind);
        entry.put("app_version",application.versionName);
        entry.put("version_code",application.getLongVersionCode());
        entry.put("package_name",packageName);
        return entry;
    }
    private void write(JSONObject entry)throws IOException{
        String line=entry.toString()+"\n";
        long size=line.getBytes(StandardCharsets.UTF_8).length;
        if(bytes+size>16*1024*1024)throw new IOException("Дневной журнал достиг 16 МиБ. Сохрани его и подключись заново.");
        journal.write(line);journal.flush();bytes+=size;
    }

    @Override public synchronized void close(){
        if(closed)return;
        try{record("run_end","","Завершение подключения",0);}catch(IllegalStateException ignored){}
        closed=true;
        try{journal.close();}catch(IOException ignored){}
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}

    public static File[] sessions(Context context){
        String prefix=filePrefix(context);
        File root=new File(context.getFilesDir(),"sessions");
        File[] journals=root.listFiles(candidate->candidate.isFile()&&candidate.getName().startsWith(prefix)
            &&candidate.getName().endsWith(JOURNAL_SUFFIX));
        if(journals==null)return new File[0];
        Arrays.sort(journals,Comparator.comparing(File::getName).reversed());
        return journals;
    }
    public synchronized void flush() throws IOException {if(!closed)journal.flush();}
    public static void export(File source,OutputStream target) throws IOException {
        try(InputStream input=new FileInputStream(source)){
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1)target.write(buffer,0,count);
        }
    }
}
