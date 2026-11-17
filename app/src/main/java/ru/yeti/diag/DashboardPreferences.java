package ru.yeti.diag;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

final class DashboardPreferences {
    private final SharedPreferences preferences;

    DashboardPreferences(Context context){
        preferences=context.getSharedPreferences("car_dashboard_3x2",Context.MODE_PRIVATE);
    }

    List<DashboardCatalog.Parameter> page(int page){
        List<DashboardCatalog.Parameter> result=new ArrayList<>(DashboardCatalog.CELLS);
        for(int cell=0;cell<DashboardCatalog.CELLS;cell++)
            result.add(DashboardCatalog.find(preferences.getString(key(page,cell),"")));
        return result;
    }

    void set(int page,int cell,DashboardCatalog.Parameter parameter){
        SharedPreferences.Editor edit=preferences.edit();
        if(parameter==null)edit.remove(key(page,cell));
        else edit.putString(key(page,cell),parameter.id);
        edit.apply();
    }

    private static String key(int page,int cell){return page+"/"+cell;}
}
