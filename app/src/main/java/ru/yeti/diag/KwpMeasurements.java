package ru.yeti.diag;

import java.util.*;

/** Splits a complete measuring-block payload before any field is published. */
final class KwpMeasurements {
    static final class Field {
        final int type;
        final byte[] data;
        Field(int type,byte[] data){this.type=type;this.data=data;}
        int a(){return Protocol.u(data[0]);}
        int b(){return Protocol.u(data[1]);}
        String raw(){return String.format(Locale.ROOT,"Тип %02X · %s",type,Protocol.hex(data));}
        String text(){
            if(type!=0x11&&type!=0x5F&&type!=0x3F)return "";
            for(byte b:data)if(Protocol.u(b)!=0&&(Protocol.u(b)<32||Protocol.u(b)>=127))return "";
            return BlockInfoReader.printable(data,0,data.length);
        }
    }

    static List<Field> parse(byte[] response,int group){
        if(response==null||response.length<3||Protocol.u(response[0])!=0x61||Protocol.u(response[1])!=group)return null;
        List<Field> fields=new ArrayList<>();
        for(int offset=2;offset<response.length;){
            if(fields.size()>=8)return null;
            int type=Protocol.u(response[offset++]),size=2;
            if(type==0x5F||type==0x76){
                if(offset>=response.length)return null;
                size=Protocol.u(response[offset++]);
            }else if(type==0x3F)size=response.length-offset;
            else if(type==0xA0)size=5;
            if(offset+size>response.length)return null;
            fields.add(new Field(type,Arrays.copyOfRange(response,offset,offset+size)));
            offset+=size;
        }
        return fields;
    }

    /** Only self-described layouts observed on this platform may bypass missing ABS identity. */
    static boolean knownAbsLayout(int group,List<Field> fields){
        if(group==1)return types(fields,7,7,7,7);
        if(group==4)return types(fields,0x51,0x52,0x55);
        if(group==5)return types(fields,0x53,0x25);
        if(group==6)return types(fields,0x54,6,6);
        return false;
    }
    private static boolean types(List<Field> fields,int... expected){
        if(fields.size()!=expected.length)return false;
        for(int i=0;i<expected.length;i++)if(fields.get(i).type!=expected[i])return false;
        return true;
    }
}
