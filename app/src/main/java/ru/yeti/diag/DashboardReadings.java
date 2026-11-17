package ru.yeti.diag;

import java.util.*;
import java.util.function.BiConsumer;

/** One visible page's read plan and its latest samples. */
final class DashboardReadings {
    static final long STALE_AFTER_MS=20000;
    static final int NO_SCALE=-1;
    static final class Plan {
        final Map<String,Set<String>> routes;
        final BiConsumer<String,Engine.Row> receiver;

        Plan(List<DashboardCatalog.Parameter> page,BiConsumer<String,Engine.Row> receiver){
            Map<String,Set<String>> selected=new LinkedHashMap<>();
            for(DashboardCatalog.Parameter parameter:page)
                if(parameter!=null)selected.computeIfAbsent(parameter.mode,key->new LinkedHashSet<>()).add(parameter.key);
            Map<String,Set<String>> immutable=new LinkedHashMap<>();
            selected.forEach((mode,keys)->immutable.put(mode,Collections.unmodifiableSet(keys)));
            routes=Collections.unmodifiableMap(immutable);
            this.receiver=receiver;
        }

        boolean contains(String mode,String key){
            return routes.getOrDefault(mode,Collections.emptySet()).contains(key);
        }

        void acceptPhoneSample(String mode,Engine.Row row){
            String source=mode;
            if(mode.equals(Engine.COMBINED_ENGINE))
                source=row.key.startsWith("g")?"ECM":"OBD";
            if(contains(source,row.key))receiver.accept(source,row);
        }
    }

    static final class Sample {
        final String value, range, minimum, maximum, unit;
        final long time;
        final int scalePosition;
        Sample(String value,String range,String minimum,String maximum,String unit,long time,int scalePosition){
            this.value=value;
            this.range=range;
            this.minimum=minimum;
            this.maximum=maximum;
            this.unit=unit;
            this.time=time;
            this.scalePosition=scalePosition;
        }
    }

    private static final class Range {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        String unit="";
        void add(double value,String newUnit){
            if(!unit.equals(newUnit)){min=Double.POSITIVE_INFINITY;max=Double.NEGATIVE_INFINITY;unit=newUnit;}
            min=Math.min(min,value);
            max=Math.max(max,value);
        }

        int positionOf(double value){
            if(min==max)return NO_SCALE;
            return (int)Math.round(100*(value-min)/(max-min));
        }
    }

    private final Map<String,Range> ranges=new HashMap<>();
    private final Map<String,Sample> samples=new HashMap<>();

    synchronized boolean record(String mode,Engine.Row row){
        if(row.note.equals("Ожидание первого ответа"))return false;
        String id=mode+"/"+row.key;
        Sample previous=samples.get(id);
        if(previous!=null&&MeasurementRetention.isTemporaryMissing(row)
            &&MeasurementRetention.hasSuccessfulValue(previous.value))return false;
        String unit=row.unit;
        String numeric=row.value;
        if((row.key.equals("maf_power")||row.key.equals("lambda_power")||row.key.equals("fuel_power"))&&numeric.contains(" кВт")){
            numeric=numeric.substring(0,numeric.indexOf(" кВт"));
            unit="кВт";
        }
        String range=previous==null?"Мин — · Макс —":previous.range;
        String minimum="",maximum="";
        int scalePosition=NO_SCALE;
        try{
            double value=Double.parseDouble(numeric);
            if(Double.isFinite(value)){
                Range bounds=ranges.computeIfAbsent(id,key->new Range());
                bounds.add(value,unit);
                minimum=DiagnosticSession.format(bounds.min);
                maximum=DiagnosticSession.format(bounds.max);
                String suffix=unit.isEmpty()?"":" "+unit;
                range="Мин "+minimum+suffix+" · Макс "+maximum+suffix;
                scalePosition=bounds.positionOf(value);
            }
        }catch(NumberFormatException ignored){}
        String value=row.value+(row.unit.isEmpty()?"":" "+row.unit);
        samples.put(id,new Sample(value,range,minimum,maximum,unit,row.time,scalePosition));
        return previous==null||!value.equals(previous.value)||!range.equals(previous.range)
            ||scalePosition!=previous.scalePosition||row.time-previous.time>=STALE_AFTER_MS;
    }

    synchronized Sample sample(String id){return samples.get(id);}
}
