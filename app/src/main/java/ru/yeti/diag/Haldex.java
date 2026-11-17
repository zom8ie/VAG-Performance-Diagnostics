package ru.yeti.diag;

import java.util.*;

/** Haldex Gen5 catalogue, scoped to one identified software variant.
 * Data reference: OpenHaldex-S3 compact manifest, MIT (see THIRD_PARTY.md).
 */
public final class Haldex {
    public static final class Sensor {
        public final String did,name,unit; public final int length,max;public final double scale,offset;
        public final boolean probe;
        Sensor(String did,String name,String unit,int length,int max,double scale,double offset){
            this(did,name,unit,length,max,scale,offset,false);
        }
        Sensor(String did,String name,String unit,int length,int max,double scale,double offset,boolean probe){
            this.did=did;this.name=name;this.unit=unit;this.length=length;this.max=max;this.scale=scale;this.offset=offset;this.probe=probe;
        }
        public Protocol.Reading decode(byte[] d){
            if(d==null)return null;
            if(d.length!=length && !(did.equals("028D")&&d.length==2))return null;
            int n=0;
            if(did.equals("028D")&&d.length==2)n=Protocol.u(d[0]);
            else if(did.equals("2BF1")||did.equals("2BE4"))n=Protocol.u(d[1])*256+Protocol.u(d[0]);
            else for(byte b:d)n=n*256+Protocol.u(b);
            if(n>max||!Double.isFinite(scale))return null;return new Protocol.Reading(n*scale+offset,unit);
        }
        String description(){
            switch(did){
                case "2BE6":case "2BE7":case "2BE9":return "Насос муфты полного привода V181.";
                case "0286":return "Напряжение питания блока полного привода J492.";
                default:return "";
            }
        }
    }
    public static String profile(String part,String software){
        String p=part.replaceAll("[^A-Za-z0-9]","");String s=software.trim();
        if(p.equals("0CQ907554D")&&s.equals("7076"))return p+" / "+s;
        if(p.equals("0AY907554J")&&s.equals("7522"))return p+" / "+s;
        return "";
    }
    public static boolean matches(String part,String software){return !profile(part,software).isEmpty();}
    public static final List<Sensor> SENSORS=Collections.unmodifiableList(Arrays.asList(
        new Sensor("0286","Напряжение питания Haldex","В",1,254,.1,0),
        new Sensor("2BE6","Ток насоса Haldex","А",2,20000,.001,0),
        new Sensor("2BE7","Управление насосом Haldex","%",1,100,1,0),
        new Sensor("2BE9","Напряжение насоса Haldex","В",1,254,.1,0,true),
        new Sensor("028D","Температура блока Haldex","°C",1,205,1,-55),
        new Sensor("2BF1","Температура муфты Haldex","°C",2,65534,.01,-227.67),
        new Sensor("2BE4","Температура охлаждающей поверхности Haldex","°C",2,65534,.01,-227.67,true),
        new Sensor("2BE5","Длительность перегрева Haldex","",2,65534,Double.NaN,0,true),
        new Sensor("2BF3","Степень замыкания муфты Haldex","%",1,254,.3937,0,true)
    ));
}
