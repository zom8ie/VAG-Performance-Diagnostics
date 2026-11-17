package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

/** Reads coding and dataset metadata in the default diagnostic session. */
final class BlockConfigurationReader {
    private final DiagnosticSession session;
    private final BlockInfoReader info;

    BlockConfigurationReader(DiagnosticSession session,BlockInfoReader info){
        this.session=session;
        this.info=info;
    }

    boolean read(boolean dataset)throws IOException{
        DiagnosticBlock block=DiagnosticBlock.forMode(session.mode);
        info.select(block);
        String rx=block.rx;
        if(!dataset){
            byte[] data=info.payload("0600",rx);
            boolean found=data!=null&&data.length>0;
            session.put("coding","Кодировка","Информация",found?Protocol.hex(data):"Блок не передал кодировку","",
                "Чтение кодировки · UDS 0600");
            return found;
        }
        String number=datasetValue(info.payload("F1A0",rx));
        String version=datasetValue(info.payload("F1A1",rx));
        boolean found=!number.isEmpty()||!version.isEmpty();
        List<String> fields=new ArrayList<>();
        if(!number.isEmpty())fields.add("Номер набора: "+number);
        if(!version.isEmpty())fields.add("Версия набора: "+version);
        if(!found){
            fields.add("Блок не передал номер или версию параметрии.");
            String odx=datasetValue(info.payload("F19E",rx)),odxVersion=datasetValue(info.payload("F1A2",rx));
            if(!odx.isEmpty())fields.add("Диагностический профиль: "+odx);
            if(!odxVersion.isEmpty())fields.add("Версия диагностического профиля: "+odxVersion);
        }
        session.put("dataset","Параметрия","Информация",String.join("\n",fields),"","Сведения о наборе данных блока");
        return found;
    }
    static String datasetValue(byte[] data){
        if(data==null)return "";
        String text=BlockInfoReader.printable(data,0,data.length);
        return text.matches("[-_\\s]*")?"":text;
    }
}
