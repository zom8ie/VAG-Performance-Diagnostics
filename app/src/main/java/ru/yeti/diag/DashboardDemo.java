package ru.yeti.diag;

import java.util.*;

/** Changing readings used only by the separate Android Auto test package. */
final class DashboardDemo {
    private static final String[][] IDS={
        {"ECM/g115f2","ECM/g115f3","OBD/pid12","OBD/pid5","OBD/adapterV","OBD/maf_power"},
        {"DSG/g11f3","DSG/g12f3","DSG/g10f2","DSG/g4f0","ECM/g14f2","OBD/pid13"},
        {"HALDEX/h2BE6","HALDEX/h2BE7","HALDEX/h2BE9","HALDEX/h2BF3","OBD/lambda_power","OBD/fuel_power"}
    };

    private DashboardDemo(){}

    static List<DashboardCatalog.Parameter> page(int page){
        List<DashboardCatalog.Parameter> parameters=new ArrayList<>(DashboardCatalog.CELLS);
        for(String id:IDS[page%IDS.length])parameters.add(DashboardCatalog.find(id));
        return parameters;
    }

    static boolean isEmpty(List<DashboardCatalog.Parameter> parameters){
        for(DashboardCatalog.Parameter parameter:parameters)if(parameter!=null)return false;
        return true;
    }

    static boolean record(DashboardReadings readings,List<DashboardCatalog.Parameter> parameters,long elapsedMs){
        boolean changed=false;
        for(int index=0;index<parameters.size();index++){
            DashboardCatalog.Parameter parameter=parameters.get(index);
            if(parameter==null)continue;
            Value value=value(parameter,elapsedMs,index);
            changed|=readings.record(parameter.mode,new Engine.Row(parameter.key,parameter.name,
                "Демонстрация",value.number,value.unit,""));
        }
        return changed;
    }

    private static Value value(DashboardCatalog.Parameter parameter,long elapsedMs,int index){
        double wave=Math.sin(elapsedMs/1200d+index*.73d);
        switch(parameter.id){
            case "ECM/g115f2": return decimal(1.18+.16*wave,"бар");
            case "ECM/g115f3": return decimal(1.11+.19*wave,"бар");
            case "OBD/pid12": return integer(780+180*wave,"об/мин");
            case "OBD/pid5": return integer(88+3*wave,"°C");
            case "OBD/adapterV": return decimal(13.8+.2*wave,"В");
            case "OBD/maf_power": return decimal(28+9*wave,"кВт");
            case "DSG/g11f3":
            case "DSG/g12f3": return decimal(6.1+.7*wave,"бар");
            case "DSG/g10f2": return integer(148+18*wave,"Н·м");
            case "DSG/g4f0": return integer(3+wave,"1");
            case "ECM/g14f2": return decimal(8.5+1.3*wave,"°");
            case "OBD/pid13": return integer(60+15*wave,"км/ч");
            case "HALDEX/h2BE6": return decimal(4.2+.8*wave,"А");
            case "HALDEX/h2BE7": return integer(42+19*wave,"%");
            case "HALDEX/h2BE9": return decimal(13.7+.2*wave,"В");
            case "HALDEX/h2BF3": return integer(36+18*wave,"%");
            case "OBD/lambda_power":
            case "OBD/fuel_power": return decimal(27+8*wave,"кВт");
            default: return decimal(50+20*wave,"ед.");
        }
    }

    private static Value decimal(double value,String unit){return new Value(String.format(Locale.ROOT,"%.2f",value),unit);}
    private static Value integer(double value,String unit){return new Value(Integer.toString((int)Math.round(value)),unit);}

    private static final class Value {
        final String number,unit;
        Value(String number,String unit){this.number=number;this.unit=unit;}
    }
}
