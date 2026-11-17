package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class MeasurementTest {
    static void run() throws Exception {
        near(pid(12).decode(Protocol.bytes("1AF8")).value,1726,"rpm");
        check(pid(12).decode(Protocol.bytes("1AF8")).unit.equals("об/мин"),"rpm unit");
        near(pid(13).decode(Protocol.bytes("24")).value,36,"kmh");
        check(pid(13).decode(Protocol.bytes("24")).unit.equals("км/ч"),"speed unit");
        near(pid(5).decode(Protocol.bytes("82")).value,90,"coolant");
        near(pid(6).decode(Protocol.bytes("80")).value,0,"trim neutral");
        near(pid(7).decode(Protocol.bytes("60")).value,-25,"negative trim");
        near(pid(0x56).decode(Protocol.positive("7E8 03 41 56 80\r>","7E8",0x41,0x56)).value,0,"logged secondary trim does not require absent bank 3");
        near(pid(0x56).decode(Protocol.bytes("9080")).value,12.5,"optional bank 3 byte does not change bank 1 decoding");
        check(pid(0x56).completeLength(1)&&pid(0x56).completeLength(2)&&!pid(0x56).completeLength(0),"secondary trim accepts both complete response lengths");
        near(pid(0x23).decode(Protocol.bytes("1388")).value,500,"rail pressure bar");
        near(pid(0x42).decode(Protocol.bytes("36B0")).value,14,"voltage");
        near(pid(0x10).decode(Protocol.bytes("01F4")).value,5,"airflow");
        near(pid(0x44).decode(Protocol.bytes("8000")).value,1,"lambda");
        near(pid(0x5E).decode(Protocol.bytes("0048")).value,3.6,"fuel litres/hour");
        boolean shortRejected=false;try{pid(12).decode(new byte[]{1});}catch(IllegalArgumentException e){shortRejected=true;}check(shortRejected,"short sensor payload");
        for(Protocol.Pid p:Protocol.PIDS){
            check(Elm.allowedObd(String.format(Locale.ROOT,"01%02X",p.id)),"catalog read guard");
            check(Double.isFinite(p.decode(new byte[p.size]).value),"finite decoder");
            check(!VehicleData.obdDescription(p.id).trim().isEmpty(),"every OBD parameter has an explanation");
        }
        near(Protocol.kwpValue(54,1,2).value,258,"VAG count");
        near(Protocol.kwpValue(18,250,100).value,1,"VAG pressure mbar to bar");
        check(Protocol.kwpValue(18,250,100).unit.equals("бар"),"VAG pressure unit");
        near(Protocol.kwpValue(4,10,117).value,1,"VAG angle");
        check(Protocol.kwpValue(255,10,20)==null,"unknown VAG format never guessed");
        check(Protocol.kwpValue(33,0,20)==null,"division by zero");
        check(VehicleData.field("DSG",19,1).name.contains("G510")&&VehicleData.field("DSG",19,2).name.contains("G509"),"DQ250 temperatures field order");
        near(VehicleData.reading("DSG",11,2,0x18,6,255).value,1.53,"DQ250 valve current");
        near(VehicleData.reading("DSG",12,3,0x53,0,19).value,.19,"DQ250 logged K2 pressure");
        near(VehicleData.reading("DSG",12,3,0x53,255,255).value,-.01,"DQ250 signed pressure");
        near(VehicleData.reading("DSG",16,1,0x0D,100,45).value,-8.2,"DQ250 logged selector travel");
        check(VehicleData.reading("ECM",11,2,0x18,6,255)==null,"DSG format not used for engine");
        check(Haldex.matches("0CQ 907 554 D","7076"),"Haldex exact match");
        check(Haldex.matches("0AY907554J","7522"),"Yeti Haldex Gen5 match");
        check(!Haldex.matches("0AY907554J","7076")&&!Haldex.matches("0CQ907554D","7077"),"unknown Haldex variant gated");
        near(VehicleData.mafPowerKw(100),81.6326530612,"power from 100 g/s");
        check(Double.isNaN(VehicleData.mafPowerKw(-1))&&Double.isNaN(VehicleData.mafPowerKw(Double.NaN)),"invalid airflow is not a power value");
        near(VehicleData.lambdaPowerKw(100,.8),102.0408163265,"power from airflow with rich lambda");
        near(VehicleData.fuelRatePowerKw(3.6),8.94,"power from hourly fuel rate");
        check(Double.isNaN(VehicleData.lambdaPowerKw(100,0))&&Double.isNaN(VehicleData.fuelRatePowerKw(-1)),
            "invalid lambda or fuel rate is not a power value");
        Engine.Row retained=new Engine.Row("rpm","Обороты","Основные","760","об/мин","");
        Engine.Row missing=new Engine.Row("rpm","Обороты","Основные","—","","Нет данных / неполный ответ");
        check(MeasurementRetention.keepLastSuccessful(retained,missing)==retained
            &&MeasurementRetention.keepLastSuccessful(null,missing)==missing,
            "temporary missing response retains only a previously successful value");
        check(VehicleData.field("DSG",19,3)==null&&VehicleData.field("ABS",6,3)==null,"undocumented field omitted");
        check(VehicleData.field("ABS",6,0).unit.equals("м/с²"),"logged ABS longitudinal acceleration is the first field");
        check(VehicleData.field("DSG",11,2).description.contains("N215")&&VehicleData.obdDescription(0x10).contains("G70"),"sensor designation in description");
        check(VehicleData.field("ENGINE",15,0).description.contains("цилиндра")&&VehicleData.field("ABS",1,0).description.contains("ABS"),
            "VAG parameters have useful explanations");
        for(String mode:new String[]{"ECM","DSG","ABS","EPS"})for(int group:VehicleData.groups(mode))for(int index=0;index<4;index++){
            VehicleData.Field field=VehicleData.field(mode,group,index);
            if(field!=null)check(!field.description.trim().isEmpty(),"every VAG parameter has an explanation");
        }
        check(VehicleData.sensorMode(Engine.COMBINED_ENGINE)&&VehicleData.groups(Engine.COMBINED_ENGINE).equals(VehicleData.groups("ECM")),
            "combined engine screen keeps the VAG group catalogue");
        checkParameterOrder();
        check(DiagnosticBlock.forMode(Engine.COMBINED_ENGINE)==DiagnosticBlock.ENGINE&&VehicleData.blockAddress(Engine.COMBINED_ENGINE).equals("01"),
            "combined engine screen uses the engine diagnostic route");
        near(Haldex.SENSORS.get(1).decode(Protocol.bytes("1388")).value,5,"Haldex pump current");
        check(Haldex.SENSORS.get(1).decode(Protocol.bytes("FFFF"))==null,"Haldex invalid sentinel");
        check(Haldex.SENSORS.get(2).decode(Protocol.bytes("65"))==null,"Haldex out of definition range");
        near(Haldex.SENSORS.get(4).decode(Protocol.bytes("3D00")).value,6,"Haldex variant two-byte temperature");
        near(Haldex.SENSORS.get(5).decode(Protocol.bytes("835B")).value,6.6,"Haldex little-endian clutch temperature");
        check(Haldex.SENSORS.get(3).decode(Protocol.bytes("1200"))==null
            &&Haldex.SENSORS.get(8).decode(Protocol.bytes("0300"))==null,
            "logged two-byte Haldex payloads remain raw until their variant is mapped");
        check(Haldex.SENSORS.get(6).decode(Protocol.bytes("835B00"))==null,
            "unexpected payload length is not treated as CAN padding");
        near(Haldex.SENSORS.get(3).decode(Protocol.bytes("12")).value,1.8,"Haldex exact-length payload still decodes");
    }

    private static void checkParameterOrder(){
        List<Engine.Row> rows=Arrays.asList(
            row("rpm","Обороты двигателя","Измерения"),
            row("misfire3","Пропуски · цилиндр 3","Измерения"),
            row("misfire1","Пропуски · цилиндр 1","Измерения"),
            row("temp","Воздух на впуске","Измерения"),
            row("state","Лампа Check Engine","Состояние")
        );
        ParameterSelection selection=new ParameterSelection(){
            public boolean isEnabled(String mode,String key){return !key.equals("temp")&&!key.equals("misfire3");}
            public void setEnabled(String mode,String key,boolean enabled){}
        };
        List<Engine.Row> ordered=ParameterOrder.displayOrder(rows,"ECM",selection);
        check(ordered.get(0).key.equals("state")&&ordered.get(1).key.equals("rpm")&&ordered.get(2).key.equals("misfire1")
                &&ordered.get(ordered.size()-2).key.equals("misfire3")&&ordered.get(ordered.size()-1).key.equals("temp"),
            "service parameters come first, disabled parameters last, and active misfires stay in cylinder order");
    }
    private static Engine.Row row(String key,String name,String group){return new Engine.Row(key,name,group,"0","","");}
}
