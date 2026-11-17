package ru.yeti.diag;

import java.util.*;

/** Places service rows first, then active measurements, and disabled measurements last. */
final class ParameterOrder {
    private ParameterOrder(){}

    static List<Engine.Row> displayOrder(List<Engine.Row> rows,String mode,ParameterSelection selection){
        List<Engine.Row> service=new ArrayList<>(),active=new ArrayList<>(),disabled=new ArrayList<>();
        for(Engine.Row row:rows){
            if(!isSelectable(row))service.add(row);
            else if(selection.isEnabled(mode,row.key))active.add(row);
            else disabled.add(row);
        }
        List<Engine.Row> result=new ArrayList<>(service);
        result.addAll(keepMisfiresTogether(active));
        result.addAll(keepMisfiresTogether(disabled));
        return result;
    }

    static boolean isSelectable(Engine.Row row){
        return !row.group.equals("Информация")&&!row.group.equals("Состояние")&&!row.group.equals("Анализ");
    }

    private static List<Engine.Row> keepMisfiresTogether(List<Engine.Row> rows){
        List<Engine.Row> result=new ArrayList<>(),misfires=new ArrayList<>();
        int first=-1;
        for(Engine.Row row:rows){
            if(!row.name.startsWith("Пропуски")){result.add(row);continue;}
            if(first<0)first=result.size();
            misfires.add(row);
        }
        if(first<0)return result;
        Collections.sort(misfires,(left,right)->Integer.compare(misfireOrder(left),misfireOrder(right)));
        result.addAll(first,misfires);
        return result;
    }

    private static int misfireOrder(Engine.Row row){
        if(row.name.contains("общий"))return 100;
        java.util.regex.Matcher cylinder=java.util.regex.Pattern.compile("цилиндр (\\d)").matcher(row.name);
        if(cylinder.find())return 100+Integer.parseInt(cylinder.group(1));
        if(row.name.contains("распознавание"))return 110;
        return 999;
    }
}
