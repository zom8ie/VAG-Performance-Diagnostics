package ru.yeti.diag;

/** Visual interpolation only. Numeric readings and measured bounds remain unmodified. */
final class DashboardNeedle {
    static final long DURATION_MS=600;
    private float from=DashboardReadings.NO_SCALE;
    private int target=DashboardReadings.NO_SCALE;
    private long started;

    void moveTo(int position,long now){
        if(position==target)return;
        float current=positionAt(now);
        target=position;
        from=current<0||position<0?position:current;
        started=now;
    }

    float positionAt(long now){
        if(from==target)return target;
        float progress=Math.max(0,Math.min(1,(now-started)/(float)DURATION_MS));
        float eased=progress*progress*(3-2*progress);
        return from+(target-from)*eased;
    }

    boolean running(long now){return from!=target&&now-started<DURATION_MS;}
}
