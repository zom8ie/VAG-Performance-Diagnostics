package ru.yeti.diag;

import android.content.Context;
import android.content.SharedPreferences;

/** Android-backed selection store. Missing entries intentionally mean enabled. */
final class PreferenceParameterSelection implements ParameterSelection {
    private final SharedPreferences preferences;
    PreferenceParameterSelection(Context context){preferences=context.getSharedPreferences("parameter_selection",Context.MODE_PRIVATE);}
    public boolean isEnabled(String mode,String key){return preferences.getBoolean(mode+"/"+key,true);}
    public void setEnabled(String mode,String key,boolean enabled){preferences.edit().putBoolean(mode+"/"+key,enabled).apply();}
}
