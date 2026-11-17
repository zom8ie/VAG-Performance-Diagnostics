package ru.yeti.diag;

import java.util.*;

/** Short in-app explanations for the most common powertrain fault codes. */
final class FaultCatalog {
    private FaultCatalog(){}
    static boolean hasCodes(String value){return value!=null&&value.matches("(?s).*\\b[PCBU][0-9A-F]{4}\\b.*");}
    static String describe(String codes){
        StringBuilder text=new StringBuilder();
        for(String code:codes.split("[,\\s]+")){
            if(!code.matches("[PCBU][0-9A-F]{4}"))continue;
            if(text.length()>0)text.append("\n\n");
            text.append(code).append(" — ").append(description(code));
        }
        if(text.length()==0)return "Блок сообщил о неисправности, но код не удалось распознать.";
        text.append("\n\nОписание носит справочный характер. Перед удалением сохраните журнал и устраните причину неисправности.");
        return text.toString();
    }
    private static String description(String code){
        switch(code){
            case "P0300":return "Случайные или множественные пропуски зажигания. ЭБУ видит нестабильную работу цилиндров.";
            case "P0301":return "Пропуски зажигания в цилиндре 1.";
            case "P0302":return "Пропуски зажигания в цилиндре 2.";
            case "P0303":return "Пропуски зажигания в цилиндре 3.";
            case "P0304":return "Пропуски зажигания в цилиндре 4.";
            case "P130A":return "Защита катализатора: ЭБУ ограничил работу цилиндра из-за риска повреждения катализатора.";
            default:
                if(code.startsWith("P"))return "Неисправность силового агрегата или трансмиссии. Точная расшифровка зависит от блока и его версии.";
                if(code.startsWith("C"))return "Неисправность шасси: тормоза, ABS/ESP, рулевое управление или датчики движения.";
                if(code.startsWith("B"))return "Неисправность кузовной электроники.";
                return "Неисправность обмена данными между электронными блоками.";
        }
    }
}
