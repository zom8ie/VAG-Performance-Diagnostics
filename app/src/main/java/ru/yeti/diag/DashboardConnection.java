package ru.yeti.diag;

/** Paces connection requests while the car screen is visible. */
final class DashboardConnection {
    static final long RETRY_MS=15000;
    private long nextAttempt;

    boolean shouldStart(long now,boolean active,boolean wanted,boolean selected,boolean permitted){
        if(active||!wanted||!selected||!permitted||now<nextAttempt)return false;
        nextAttempt=now+RETRY_MS;
        return true;
    }
}
