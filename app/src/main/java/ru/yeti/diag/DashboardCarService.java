package ru.yeti.diag;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.validation.HostValidator;

/** Entry point for the Android Auto host. */
public final class DashboardCarService extends CarAppService {
    @Override public HostValidator createHostValidator(){
        // The Desktop Head Unit uses a local test certificate. This variant is
        // a different package and is never the phone/vehicle build.
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build();
    }

    @Override public Session onCreateSession(SessionInfo info){
        return new Session(){
            @Override public Screen onCreateScreen(Intent intent){
                return new DashboardCarScreen(getCarContext());
            }
        };
    }
}
