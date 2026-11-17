package ru.yeti.diag;

import java.util.*;

/** Stable parameter IDs shared by the editor and the car screen. */
final class DashboardCatalog {
    static final int PAGES=3, COLUMNS=3, ROWS=2, CELLS=COLUMNS*ROWS;
    static final String[] BLOCKS={Engine.COMBINED_ENGINE,"DSG","HALDEX","ABS","EPS"};
    static final class Parameter {
        final String mode,key,id,name,description;
        Parameter(String mode,String key,String name,String description) {
            this.mode=mode;
            this.key=key;
            this.id=mode+"/"+key;
            this.name=name;
            this.description=description;
        }
    }

    private static final Map<String,Parameter> PARAMETERS=build();

    static Parameter find(String id){return PARAMETERS.get(id);}

    static List<Parameter> forBlock(String mode){
        List<Parameter> result=new ArrayList<>();
        for(Parameter parameter:PARAMETERS.values()){
            if(parameter.mode.equals(mode)||mode.equals(Engine.COMBINED_ENGINE)
                &&(parameter.mode.equals("OBD")||parameter.mode.equals("ECM")))result.add(parameter);
        }
        return result;
    }

    /** Normalizes a saved page to the fixed 3×2 layout used on the car display. */
    static List<Parameter> gridCells(List<Parameter> saved){
        List<Parameter> result=new ArrayList<>(CELLS);
        for(int cell=0;cell<CELLS;cell++)result.add(cell<saved.size()?saved.get(cell):null);
        return result;
    }

    /** Finds parameters by their visible name or short explanation. */
    static List<Parameter> search(List<Parameter> source,String query){
        String needle=searchText(query);
        if(needle.isEmpty())return new ArrayList<>(source);
        List<Parameter> result=new ArrayList<>();
        for(Parameter parameter:source){
            String text=searchText(parameter.name+" "+parameter.description);
            if(text.contains(needle))result.add(parameter);
        }
        return result;
    }
    private static String searchText(String text){
        return (text==null?"":text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+"," ").trim();
    }

    static String blockTitle(String mode){
        return VehicleData.modeName(mode);
    }

    private static Map<String,Parameter> build(){
        Map<String,Parameter> result=new LinkedHashMap<>();
        for(Protocol.Pid pid:Protocol.PIDS){
            String description=VehicleData.obdDescription(pid.id);
            add(result,"OBD","pid"+pid.id,pid.name,description.isEmpty()?pid.group+" · OBD":description);
        }
        add(result,"OBD","adapterV","Напряжение на адаптере","Напряжение питания диагностического адаптера");
        add(result,"OBD","oxygenCurrent","Ток передней лямбды","Передний широкополосный лямбда-зонд G39");
        add(result,"OBD","maf_power",VehicleData.POWER_NAME,VehicleData.POWER_NOTE);
        add(result,"OBD","lambda_power",VehicleData.LAMBDA_POWER_NAME,VehicleData.LAMBDA_POWER_NOTE);
        add(result,"OBD","fuel_power",VehicleData.FUEL_POWER_NAME,VehicleData.FUEL_POWER_NOTE);
        for(Haldex.Sensor sensor:Haldex.SENSORS)
            add(result,"HALDEX","h"+sensor.did,sensor.name,sensor.description());
        for(String mode:new String[]{"ECM","DSG","ABS","EPS"}){
            for(int group:VehicleData.groups(mode)){
                for(int index=0;index<4;index++){
                    VehicleData.Field field=VehicleData.field(mode,group,index);
                    if(field!=null)add(result,mode,"g"+group+"f"+index,field.name,
                        VehicleData.parameterDescription("g"+group+"f"+index,field.name,"Измерения",field.description));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void add(Map<String,Parameter> result,String mode,String key,String name,String description){
        Parameter parameter=new Parameter(mode,key,name,description);
        result.put(parameter.id,parameter);
    }
}
