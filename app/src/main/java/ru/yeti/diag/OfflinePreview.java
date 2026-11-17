package ru.yeti.diag;

import java.util.*;

/** Demo values. Shares the same parameter catalog as the live screen. */
final class OfflinePreview {
    static String title(String chosen){
        switch(chosen){
            case "OBD":
            case "ECM":
            case "ENGINE": return "Двигатель";
            case "DSG": return "Коробка DQ250";
            case "ABS": return "ABS / ESP";
            case "EPS": return "Усилитель руля";
            case "GATEWAY": return "Блоки автомобиля";
            default: return "Haldex 5";
        }
    }

    void render(String mode,String filter,DiagnosticRowsView view){
        List<Engine.Row> rows=new ArrayList<>();
        if(mode.equals("OBD")||mode.equals(Engine.COMBINED_ENGINE)){
            for(Protocol.Pid pid:Protocol.PIDS){
                Protocol.Reading value=pid.decode(new byte[pid.size]);
                add(rows,"pid"+pid.id,pid.name,pid.group,example(value.unit),value.unit.equals("с")?"":value.unit,VehicleData.obdDescription(pid.id));
            }
            add(rows,"maf_power",VehicleData.POWER_NAME,"Основные","2,6 кВт · 3,6 л.с.","",VehicleData.POWER_NOTE);
            add(rows,"lambda_power",VehicleData.LAMBDA_POWER_NAME,"Основные","2,7 кВт · 3,7 л.с.","",VehicleData.LAMBDA_POWER_NOTE);
            add(rows,"fuel_power",VehicleData.FUEL_POWER_NAME,"Основные","2,5 кВт · 3,4 л.с.","",VehicleData.FUEL_POWER_NOTE);
            add(rows,"oxygenCurrent","Ток передней лямбды","Смесь и топливо","0.12","мА","");
            add(rows,"adapterV","Напряжение на адаптере","Основные","13.8","В","");
            add(rows,"mil","Лампа Check Engine","Состояние","Выключена","","");
            add(rows,"dtccount","Число сохранённых ошибок","Состояние","0","шт.","");
            add(rows,"fuel_system","Режим топливной системы","Состояние","По сигналу лямбда-зонда","","");
            add(rows,"oxygen_sensors","Лямбда-зонды · ряд 1","Состояние","B1S1, B1S2","","");
            add(rows,"obd_standard","Стандарт OBD","Состояние","EOBD","","");
            add(rows,"monitors","Мониторы текущего цикла","Состояние","Завершено 4 из 7","","");
            for(String label:new String[]{"Сохранённые ошибки","Ожидающие ошибки","Постоянные ошибки"})add(rows,"state_"+label,label,"Состояние","Не обнаружены","","");
            if(mode.equals("OBD")){view.renderExamples(mode,rows);return;}
        }
        add(rows,"sample_faults","Ошибки · "+title(mode),"Состояние","Не обнаружены","","");
        if(mode.equals("HALDEX")){
            add(rows,"profile","Профиль Haldex 5","Информация","Пример","","");
            add(rows,"profileVariant","Вариант блока Haldex","Информация","Пример","","");
            for(Haldex.Sensor sensor:Haldex.SENSORS)add(rows,"h"+sensor.did,sensor.name,"Измерения",example(sensor.unit),sensor.unit,sensor.description());
            view.renderExamples(mode,rows);return;
        }
        if(mode.equals("GATEWAY")){
            for(String address:new String[]{"01","02","03","08","09","17","19","22","44"})add(rows,"module"+address,VehicleData.moduleName(address),"Информация","Установлен","","");
            view.renderExamples(mode,rows);return;
        }
        for(int group:VehicleData.groups(mode)){
            for(int index=0;index<4;index++){
                VehicleData.Field field=VehicleData.field(mode,group,index);
                if(field!=null)add(rows,"g"+group+"f"+index,field.name,"Измерения",example(field.unit),field.unit.equals("с")?"":field.unit,field.description);
            }
        }
        view.renderExamples(mode,rows);
    }
    private static void add(List<Engine.Row> rows,String key,String name,String group,String value,String unit,String note){
        rows.add(new Engine.Row(key,name,group,value,unit,note));
    }
    private static String example(String unit){
        switch(unit){
            case "об/мин":return "760";
            case "°C":return "54";
            case "В":return "13.8";
            case "бар":return "1.05";
            case "г/с":return "3.2";
            case "%":return "12";
            case "с":return "2 мин 53 сек";
            case "1":return "1.00";
            case "":return "—";
            default:return "0";
        }
    }
}
