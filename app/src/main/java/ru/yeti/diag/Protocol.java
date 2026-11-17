package ru.yeti.diag;

import java.util.*;
import java.io.ByteArrayOutputStream;

/** Pure protocol functions. No Android dependencies, shared by app and replay tests. */
public final class Protocol {
    private Protocol() {}
    public static int u(byte b) { return b & 255; }
    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02X", u(x)));
        return s.toString();
    }
    public static byte[] bytes(String s) {
        s = s.replace(" ", "");
        if (!s.matches("(?:[0-9a-fA-F]{2})+")) throw new IllegalArgumentException("Bad hex");
        byte[] b = new byte[s.length()/2];
        for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return b;
    }
    public static final class Frame {
        public final String id; public final byte[] data;
        Frame(String id, byte[] data) { this.id=id;this.data=data; }
    }
    /** ATH1, ATS1, ATD0. Also accepts a single DLC digit from clones ignoring ATD0. */
    public static List<Frame> frames(String raw) {
        List<Frame> result = new ArrayList<>();
        for (String line : raw.toUpperCase(Locale.ROOT).replace('>','\n').split("[\r\n]+")) {
            String[] fields=line.trim().split("\\s+");
            if (fields.length<2 || !fields[0].matches("[0-9A-F]{3}|[0-9A-F]{8}")) continue;
            int start=1, dlc=-1;
            if (fields[1].matches("[0-8]")) { dlc=Integer.parseInt(fields[1]);start=2; }
            if (fields.length-start<1 || fields.length-start>8) continue;
            byte[] data=new byte[fields.length-start]; boolean valid=true;
            for (int i=start;i<fields.length;i++) {
                if (!fields[i].matches("[0-9A-F]{2}")) {valid=false;break;}
                data[i-start]=(byte)Integer.parseInt(fields[i],16);
            }
            if (valid && (dlc<0 || dlc==data.length)) result.add(new Frame(fields[0],data));
        }
        return result;
    }
    /** Reassembles ISO-TP separately for each ECU; rejects missing/out-of-order segments. */
    public static Map<String,byte[]> messages(String raw) {
        Map<String,byte[]> result=new LinkedHashMap<>();
        Map<String,ByteArrayOutputStream> partial=new HashMap<>();
        Map<String,Integer> lengths=new HashMap<>(), seqs=new HashMap<>();
        for (Frame f:frames(raw)) {
            byte[] d=f.data; if(d.length==0)continue;
            int first=u(d[0]); int type=first>>4;
            // With ATD0 the DLC is removed by frames(), so a short positive
            // OBD/UDS response arrives directly as 41/43/62, not as ISO-TP 0x0N.
            if(first==0x41||first==0x43||first==0x44||first==0x47||first==0x4A||first==0x4E||first==0x50||first==0x51||first==0x54||first==0x61||first==0x62||first==0x63||first==0x7F) {
                result.put(f.id,Arrays.copyOf(d,d.length));
                continue;
            }
            if (type==0) {
                int n=u(d[0])&15;
                if (n>0 && n<=d.length-1) result.put(f.id,Arrays.copyOfRange(d,1,n+1));
            } else if (type==1 && d.length>=3) {
                int n=((u(d[0])&15)<<8)|u(d[1]);
                if(n<8 || n>4095)continue;
                ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(d,2,d.length-2);
                partial.put(f.id,b);lengths.put(f.id,n);seqs.put(f.id,1);
            } else if(type==2 && partial.containsKey(f.id)) {
                if((u(d[0])&15)!=seqs.get(f.id)) {partial.remove(f.id);continue;}
                ByteArrayOutputStream b=partial.get(f.id);b.write(d,1,d.length-1);
                seqs.put(f.id,(seqs.get(f.id)+1)&15);
                if(b.size()>=lengths.get(f.id)) {
                    result.put(f.id,Arrays.copyOf(b.toByteArray(),lengths.get(f.id)));
                    partial.remove(f.id);
                }
            }
        }
        return result;
    }
    public static byte[] positive(String raw,String ecu,int service,int param) {
        byte[] p=messages(raw).get(ecu);
        if(p==null || p.length<1 || u(p[0])!=service || (param>=0 && (p.length<2||u(p[1])!=param))) return null;
        return Arrays.copyOfRange(p,param<0?1:2,p.length);
    }
    public static Set<Integer> supported(int base,byte[] d) {
        Set<Integer> p=new LinkedHashSet<>();
        if(d==null||d.length!=4)return p;
        for(int i=0;i<32;i++) if((u(d[i/8])&(0x80>>(i%8)))!=0)p.add(base+i+1);
        return p;
    }
    public static List<String> dtcs(byte[] payload) {
        List<String> out=new ArrayList<>();
        if(payload==null||payload.length<1)return out;
        int start=1;
        // CAN OBD adds a DTC count; odd-length legacy payloads contain pairs directly.
        int pairs=(payload.length-1)/2;
        if(payload.length%2==0) {start=2;pairs=Math.min(u(payload[1]),(payload.length-2)/2);}
        for(int n=0;n<pairs;n++) {
            int a=u(payload[start+2*n]),b=u(payload[start+2*n+1]); if(a==0&&b==0)continue;
            out.add("PCBU".charAt(a>>6)+String.format(Locale.ROOT,"%01X%01X%02X",(a>>4)&3,a&15,b));
        }
        return out;
    }
    public static final class Reading {
        public final double value;public final String unit;
        public Reading(double value,String unit){this.value=value;this.unit=unit;}
    }
    public static final class Pid {
        public final int id,size;public final String name,group,formula;
        Pid(int id,int size,String name,String group,String formula){this.id=id;this.size=size;this.name=name;this.group=group;this.formula=formula;}
        boolean completeLength(int length){return length==size||id==0x56&&length==2;}
        public Reading decode(byte[] d) {
            if(d==null||d.length<size)throw new IllegalArgumentException("Short PID "+id);
            int a=u(d[0]),b=size>1?u(d[1]):0,w=a*256+b;
            switch(formula) {
                case "percent":return new Reading(a*100.0/255,"%");
                case "temp":return new Reading(a-40,"°C");
                case "trim":return new Reading((a-128)*100.0/128,"%");
                case "rpm":return new Reading(w/4.0,"об/мин");
                case "speed":return new Reading(a,"км/ч");
                case "angle":return new Reading(a/2.0-64,"°");
                case "air":return new Reading(w/100.0,"г/с");
                case "volt":return new Reading(w/1000.0,"В");
                case "o2volt":return new Reading(a/200.0,"В");
                case "kpa":return new Reading(a/100.0,"бар");
                case "rail":return new Reading(w*.1,"бар");
                case "railvac":return new Reading(w*.00079,"бар");
                case "seconds":return new Reading(w,"с");
                case "distance":return new Reading(w,"км");
                case "cat":return new Reading(w/10.0-40,"°C");
                case "ratio":return new Reading(w/32768.0,"1");
                case "load16":return new Reading(w*100.0/255,"%");
                case "torquePercent":return new Reading(a-125,"%");
                case "torque":return new Reading(w,"Н·м");
                case "count":return new Reading(a,"шт.");
                case "pressure3":return new Reading(a*3/100.0,"бар");
                case "fuelrate":return new Reading(w/20.0,"л/ч");
                default:throw new IllegalArgumentException("Unknown formula");
            }
        }
    }
    public static final List<Pid> PIDS=Collections.unmodifiableList(Arrays.asList(
        new Pid(0x0C,2,"Угловая скорость двигателя","Основные","rpm"),
        new Pid(0x0D,1,"Скорость автомобиля","Основные","speed"),
        new Pid(0x05,1,"Охлаждающая жидкость","Температуры","temp"),
        new Pid(0x5C,1,"Масло двигателя","Температуры","temp"),
        new Pid(0x0F,1,"Воздух на впуске","Температуры","temp"),
        new Pid(0x46,1,"Наружный воздух","Температуры","temp"),
        new Pid(0x06,1,"Краткосрочная коррекция · ряд 1","Смесь и топливо","trim"),
        new Pid(0x07,1,"Долгосрочная коррекция · ряд 1","Смесь и топливо","trim"),
        new Pid(0x0B,1,"Давление во впуске · абсолютное","Воздух и наддув","kpa"),
        new Pid(0x33,1,"Атмосферное давление","Воздух и наддув","kpa"),
        new Pid(0x10,2,"Массовый расход воздуха","Воздух и наддув","air"),
        new Pid(0x04,1,"Расчётная нагрузка","Основные","percent"),
        new Pid(0x43,2,"Абсолютная нагрузка","Основные","load16"),
        new Pid(0x0E,1,"Угол опережения зажигания","Основные","angle"),
        new Pid(0x11,1,"Дроссель · абсолютное положение","Воздух и наддув","percent"),
        new Pid(0x45,1,"Дроссель · относительное положение","Воздух и наддув","percent"),
        new Pid(0x47,1,"Дроссель · канал B","Воздух и наддув","percent"),
        new Pid(0x49,1,"Педаль газа · канал D","Основные","percent"),
        new Pid(0x4A,1,"Педаль газа · канал E","Основные","percent"),
        new Pid(0x4C,1,"Заданное положение дросселя","Воздух и наддув","percent"),
        new Pid(0x0A,1,"Давление топлива · стандарт OBD","Смесь и топливо","pressure3"),
        new Pid(0x22,2,"Давление рампы · относительно впуска","Смесь и топливо","railvac"),
        new Pid(0x23,2,"Давление рампы · непосредственный впрыск","Смесь и топливо","rail"),
        new Pid(0x59,2,"Давление рампы · абсолютное","Смесь и топливо","rail"),
        new Pid(0x2E,1,"Продувка адсорбера","Смесь и топливо","percent"),
        new Pid(0x2F,1,"Уровень топлива","Смесь и топливо","percent"),
        new Pid(0x44,2,"Заданная лямбда","Смесь и топливо","ratio"),
        new Pid(0x24,4,"Лямбда · датчик 1, метод напряжения","Смесь и топливо","ratio"),
        new Pid(0x34,4,"Лямбда · датчик 1, метод тока","Смесь и топливо","ratio"),
        new Pid(0x15,2,"Напряжение лямбда-зонда · ряд 1, датчик 2","Смесь и топливо","o2volt"),
        new Pid(0x3C,2,"Катализатор · ряд 1, датчик 1","Температуры","cat"),
        new Pid(0x3E,2,"Катализатор · ряд 1, датчик 2","Температуры","cat"),
        new Pid(0x42,2,"Напряжение блока двигателя","Основные","volt"),
        new Pid(0x1F,2,"Время работы двигателя","Основные","seconds"),
        new Pid(0x21,2,"Путь с включённой Check Engine","Состояние","distance"),
        new Pid(0x31,2,"Путь после сброса ошибок","Состояние","distance"),
        new Pid(0x30,1,"Прогревы после сброса ошибок","Состояние","count"),
        // Byte B describes bank 3 and may be absent; bank 1 needs only byte A.
        new Pid(0x56,1,"Долговременная коррекция задней лямбды · ряд 1","Смесь и топливо","trim"),
        new Pid(0x5E,2,"Объёмный расход топлива","Смесь и топливо","fuelrate"),
        new Pid(0x61,1,"Запрошенный крутящий момент","Основные","torquePercent"),
        new Pid(0x62,1,"Фактический крутящий момент","Основные","torquePercent"),
        new Pid(0x63,2,"Опорный крутящий момент","Основные","torque")
    ));
    /** Only documented, self-described KWP encodings are decoded. Others remain raw. */
    public static Reading kwpValue(int type,int a,int b) {
        switch(type) {
            case 1:return new Reading(a*b/5.0,"об/мин");
            case 4:return new Reading((127-b)*0.01*a,"°");
            case 5:return new Reading(a*(b-100)/10.0,"°C");
            case 6:return new Reading(a*b/1000.0,"В");
            case 7:return new Reading(.01*a*b,"км/ч");
            case 9:return new Reading(a*(b-127)*.02,"°");
            case 11:return new Reading(1+a*(b-128)*.0001,"1");
            case 14:return new Reading(a*b*.005,"бар");
            // VAG type 0x12 is mbar = A*B/25; converted to bar.
            case 18:return new Reading(a*b/25000.0,"бар");
            case 20:return new Reading(a*(b-128)/128.0,"%");
            case 21:return new Reading(a*b/1000.0,"В");
            case 22:return new Reading(.001*a*b,"мс");
            case 23:return new Reading(a*b/256.0,"%");
            case 26:return new Reading(b-a,"°C");
            case 31:return new Reading(a*b/2560.0,"1");
            case 33:return a==0?null:new Reading(100.0*b/a,"%");
            case 34:return new Reading((b-128)*a/100.0,"°");
            case 36:return new Reading((a*256+b)*10.0,"км");
            case 54:return new Reading(a*256+b,"шт.");
            case 55:return new Reading(a*b/200.0,"с");
            case 59:return new Reading((a*256+b)/32768.0,"1");
            case 81:return new Reading((short)(a*256+b)*.04375,"°");
            case 82:return new Reading((short)(a*256+b)*.00981,"м/с²");
            case 83:return new Reading((short)(a*256+b)/100.0,"бар");
            case 84:return new Reading((short)(a*256+b)*.0973,"м/с²");
            case 93:return new Reading((b-128)*a/1000.0,"Н·м");
            case 94:return new Reading((b-128)*a/10.0,"Н·м");
            default:return null;
        }
    }

    public static List<String> udsDtcs(byte[] response){
        if(response==null||response.length<3||u(response[0])!=0x59||u(response[1])!=0x02
            ||(response.length-3)%4!=0)return null;
        List<String> codes=new ArrayList<>();
        for(int i=3;i<response.length;i+=4){
            int hi=u(response[i]),lo=u(response[i+1]);
            codes.add(String.format(Locale.ROOT,"%c%d%X%02X%02X · %02X",
                "PCBU".charAt(hi>>>6),(hi>>>4)&3,hi&15,lo,u(response[i+2]),u(response[i+3])));
        }
        return codes;
    }

    public static List<String> kwpDtcs(byte[] response){
        if(response==null||response.length<2||u(response[0])!=0x58
            ||response.length!=2+u(response[1])*3)return null;
        List<String> codes=new ArrayList<>();
        for(int i=2;i<response.length;i+=3)
            codes.add(String.format(Locale.ROOT,"%05d · %02X",u(response[i])*256+u(response[i+1]),u(response[i+2])));
        return codes;
    }

    static List<Integer> gatewayModules(byte[] data){
        if(data==null||data.length<5||u(data[0])!=0x5A||u(data[1])!=0x9F)return null;
        int start=2, firstLength=u(data[start]), second=start+firstLength;
        if(firstLength<1||(firstLength-1)%4!=0||second>=data.length)return null;
        int secondLength=u(data[second]), end=second+secondLength;
        if(secondLength<1||end>data.length)return null;
        if(end<data.length&&(end+1!=data.length||u(data[end])!=0xFF))return null;
        Set<Integer> modules=new LinkedHashSet<>();
        for(int i=start+1;i<second;i+=4){
            if(u(data[i+1])!=0x13&&(u(data[i+3])&1)!=0)modules.add(u(data[i]));
        }
        return new ArrayList<>(modules);
    }
}
