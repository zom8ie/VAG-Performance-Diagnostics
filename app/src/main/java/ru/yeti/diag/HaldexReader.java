package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

/** Reads measurements from the identified all-wheel-drive controller. */
final class HaldexReader {
    private final DiagnosticSession session;
    private final BlockInfoReader info;
    private final ReadAvailability availability=new ReadAvailability();
    private boolean haldexMatched, haldexCatalogMatch, haldexProbed;

    HaldexReader(DiagnosticSession session,BlockInfoReader info){
        this.session=session;
        this.info=info;
    }

    void initialize() throws IOException {
        haldexProbed=false;availability.clear();
        Map<String,String> identity=info.read("HALDEX");
        if(identity.isEmpty())throw new IOException("Haldex 5 не ответил по UDS; журнал сохранён");
        String part=identity.getOrDefault("F187",""),software=identity.getOrDefault("F189","");
        haldexMatched=Haldex.matches(part,software);
        String profile=Haldex.profile(part,software);
        haldexCatalogMatch=profile.equals("0CQ907554D / 7076");
        session.put("profile","Профиль Haldex 5","Информация",haldexMatched?"Совпадает":"Требует расшифровки","",haldexMatched?"Идентификатор найден; показания ещё нужно проверить на машине":"Будут сохранены исходные ответы; числовые значения не подтверждены для этой версии блока.");
        if(haldexMatched)session.put("profileVariant","Вариант блока Haldex","Информация",profile,"","Пятый Haldex; карта параметров для этой ревизии подтверждается журналом и требует проверки на машине");
    }
    void poll() throws IOException {
        if(haldexProbed&&!haldexMatched){DiagnosticSession.pause(200);return;}
        boolean requestedAny=false;
        for(Haldex.Sensor sensor:Haldex.SENSORS) {
            if(!session.isCurrent())return;
            if(!session.isEnabled("h"+sensor.did))continue;
            if(!session.analyzing.getAsBoolean()&&!availability.ready(sensor.did))continue;
            requestedAny=true;
            if(read(sensor))availability.success(sensor.did);
            else {
                long delay=availability.failed(sensor.did);
                session.log.record("measurement_retry","22"+sensor.did,"retry_ms="+delay,0);
            }
            DiagnosticSession.pause(DiagnosticSession.LIVE_REQUEST_PAUSE_MS);
        }
        haldexProbed=true;
        if(session.dashboard==null||!requestedAny)DiagnosticSession.pause(200);
    }

    boolean read(Haldex.Sensor sensor)throws IOException{
        byte[] payload=info.payload(sensor.did,DiagnosticBlock.HALDEX.rx);
        if(payload==null){
            session.put("h"+sensor.did,sensor.name,"Измерения","Недоступно","","Нет положительного ответа на 22"+sensor.did);
            return false;
        }
        boolean provisional=sensor.probe&&!sensor.did.equals("2BE9");
        Protocol.Reading v=haldexMatched&&(!provisional||haldexCatalogMatch)?sensor.decode(payload):null;
        String note=sensor.description().isEmpty()?"22"+sensor.did:sensor.description();
        if(v==null){
            session.log.record("measurement_format","22"+sensor.did,
                "positive; bytes="+payload.length+"; expected="+sensor.length+"; profile_match="+(haldexMatched&&(!provisional||haldexCatalogMatch)),0);
            session.put("h"+sensor.did,sensor.name,"Измерения","Ответ сохранён","",note+" · формат требует сверки: "+Protocol.hex(payload));
            return false;
        }
        session.put("h"+sensor.did,sensor.name,"Измерения",DiagnosticSession.format(v.value),v.unit,note+" · требуется сверка на машине");
        return true;
    }
}
