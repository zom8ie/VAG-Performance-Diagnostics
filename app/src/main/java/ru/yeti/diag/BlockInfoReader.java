package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

/** Reads and presents block identity. Keeps the engine identity shared by both engine screens. */
final class BlockInfoReader {
    private final DiagnosticSession session;
    private final Elm elm;
    private final Set<String> knownIdentity=new HashSet<>();
    private final Map<String,String> engineIdentity=new LinkedHashMap<>();

    BlockInfoReader(DiagnosticSession session){this.session=session;this.elm=session.elm;}

    void select(DiagnosticBlock block)throws IOException{
        elm.ok("ATSP6");
        elm.isoPhysical(block.tx,block.rx);
    }
    void readEngineIdentity()throws IOException{
        engineIdentity.clear();
        engineIdentity.putAll(read("OBD"));
    }
    void publishEngineIdentity(){publishIdentity("ECM",engineIdentity);}
    boolean hasPartNumber(){return knownIdentity.contains("F187");}
    boolean hasSoftwareVersion(){return knownIdentity.contains("F189");}

    Map<String,String> read(String mode)throws IOException{
        return readIdentity(mode,DiagnosticBlock.forMode(mode));
    }
    void publishIdentity(String mode,Map<String,String> identity){
        knownIdentity.clear();
        knownIdentity.addAll(identity.keySet());
        String address=VehicleData.blockAddress(mode);
        session.put("address","Диагностический адрес блока","Информация",address,"","Адрес раздела автомобиля");
        for(String did:VehicleData.ID_FIELDS){
            String value=identity.get(did);
            if(value!=null||Arrays.asList(VehicleData.CORE_ID_FIELDS).contains(did))
                session.put("id"+did,VehicleData.identityName(did),"Информация",value==null?"—":value,"",
                    value==null?"Блок не передал это поле":"Идентификатор "+did);
        }
    }
    private Map<String,String> readIdentity(String mode,DiagnosticBlock block)throws IOException{
        select(block);
        Map<String,String> identity=new LinkedHashMap<>();
        for(String did:VehicleData.ID_FIELDS){
            if(!session.isCurrent())break;
            byte[] data=payload(did,block.rx);
            if(data==null||data.length==0)continue;
            String value=printable(data,0,data.length);
            if(value.isEmpty())value=Protocol.hex(data);
            identity.put(did,value);
        }
        publishIdentity(mode,identity);
        return identity;
    }
    byte[] payload(String did,String rx)throws IOException{
        byte[] response=Protocol.messages(elm.udsRead(did)).get(rx);
        byte[] id=Protocol.bytes(did);
        if(response==null||response.length<3||Protocol.u(response[0])!=0x62||response[1]!=id[0]||response[2]!=id[1]){
            String outcome=response==null?"no_complete_response":response.length==3&&Protocol.u(response[0])==0x7F&&Protocol.u(response[1])==0x22
                ?"negative; nrc="+String.format(Locale.ROOT,"%02X",Protocol.u(response[2])):"unexpected_response";
            session.log.record("read_outcome","22"+did,rx+"; "+outcome,0);
            return null;
        }
        return Arrays.copyOfRange(response,3,response.length);
    }
    static String printable(byte[] data,int start,int end){
        StringBuilder text=new StringBuilder();
        for(int i=start;i<end;i++){
            int c=Protocol.u(data[i]);
            if(c>=32&&c<127)text.append((char)c);
        }
        return text.toString().trim();
    }

    String readKwpIdentity(Tp20 channel)throws IOException{
        byte[] id=channel.request("1A9B");
        if(id.length<3||Protocol.u(id[0])!=0x5A||Protocol.u(id[1])!=0x9B)return "";
        String detail=printable(id,2,id.length);
        if(!detail.isEmpty())session.put("kwpIdentity","Идентификация блока","Информация",detail,"","KWP · 1A9B");
        for(Map.Entry<String,String> field:kwpIdentityFields(id).entrySet()){
            if(!knownIdentity.contains(field.getKey())){
                session.put("id"+field.getKey(),VehicleData.identityName(field.getKey()),"Информация",field.getValue(),"","Идентификация блока по KWP");
                knownIdentity.add(field.getKey());
                if(session.mode.equals("ECM")||session.mode.equals(Engine.COMBINED_ENGINE))engineIdentity.put(field.getKey(),field.getValue());
            }
        }
        return detail;
    }
    static Map<String,String> kwpIdentityFields(byte[] id){
        Map<String,String> fields=new LinkedHashMap<>();
        if(id==null||id.length<18||Protocol.u(id[0])!=0x5A||Protocol.u(id[1])!=0x9B)return fields;
        for(int i=2;i<18;i++)if(Protocol.u(id[i])<32||Protocol.u(id[i])>126)return fields;
        String part=printable(id,2,14),software=printable(id,14,18);
        if(!part.matches("[0-9][A-Z0-9]{8}[A-Z]{0,2}")||!software.matches("[0-9]{4}"))return fields;
        fields.put("F187",part);fields.put("F189",software);
        return fields;
    }
}
