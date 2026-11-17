package ru.yeti.diag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/** Stores the selected device and handles the system Bluetooth permission. */
final class AdapterPicker {
    static final int PERMISSION_REQUEST=1;
    private final Activity activity;
    private final SharedPreferences preferences;
    private final BiConsumer<String,String> alert;
    private final Runnable onSelected;
    private String address,name;

    AdapterPicker(Activity activity,BiConsumer<String,String> alert,Runnable onSelected){
        this.activity=activity;
        this.alert=alert;
        this.onSelected=onSelected;
        preferences=settings(activity);
        address=preferences.getString("address","");
        name=preferences.getString("name","Viecar");
    }

    String address(){return address;}
    String name(){return name;}
    boolean hasSelection(){return !address.isEmpty();}

    static SharedPreferences settings(Context context){
        return context.getSharedPreferences(MainActivity.class.getSimpleName(),Context.MODE_PRIVATE);
    }

    static boolean hasPermissions(Context context){
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
            &&context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
    }

    boolean ensurePermissions(){
        List<String> missing=new ArrayList<>();
        for(String permission:new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN}){
            if(activity.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)missing.add(permission);
        }
        if(missing.isEmpty())return true;
        activity.requestPermissions(missing.toArray(new String[0]),PERMISSION_REQUEST);
        return false;
    }

    void onPermissionsResult(int[] grants){
        boolean allowed=grants.length>0;
        for(int grant:grants)allowed&=grant==PackageManager.PERMISSION_GRANTED;
        if(allowed)choose();
        else alert.accept("Нужен Bluetooth","Разреши доступ к устройствам поблизости в настройках приложения.");
    }

    @SuppressWarnings("MissingPermission")
    void choose(){
        if(!ensurePermissions())return;
        BluetoothAdapter adapter=activity.getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null){
            alert.accept("Bluetooth","Телефон не сообщает о Bluetooth-адаптере.");
            return;
        }
        if(!adapter.isEnabled()){
            activity.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        List<BluetoothDevice> devices=new ArrayList<>(adapter.getBondedDevices());
        if(devices.isEmpty()){
            alert.accept("Нет сопряжённых устройств","Сначала добавь Viecar в системных настройках Bluetooth.");
            return;
        }
        devices.sort(Comparator.comparing(AdapterPicker::deviceName));
        String[] labels=devices.stream().map(device->deviceName(device)+"\n"+device.getAddress()).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("Выбери Bluetooth-адаптер")
            .setItems(labels,(dialog,index)->select(devices.get(index))).setNegativeButton("Отмена",null).show();
    }

    @SuppressWarnings("MissingPermission")
    private void select(BluetoothDevice device){
        address=device.getAddress();
        String selectedName=device.getName();
        name=selectedName==null?"Viecar":selectedName;
        preferences.edit().putString("address",address).putString("name",name).apply();
        onSelected.run();
    }

    @SuppressWarnings("MissingPermission")
    private static String deviceName(BluetoothDevice device){
        String name=device.getName();
        return name==null?"Устройство":name;
    }
}
