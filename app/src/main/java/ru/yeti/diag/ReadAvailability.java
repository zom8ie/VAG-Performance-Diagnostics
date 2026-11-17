package ru.yeti.diag;

import java.util.*;
import java.util.function.LongSupplier;

/** Session-local retry pacing. Silence never becomes a permanent unsupported verdict. */
final class ReadAvailability {
    private static final long[] RETRY_MS={1000,5000,30000};
    private static final class Failure {int count;long retryAt;}
    private final Map<String,Failure> failures=new HashMap<>();
    private final LongSupplier now;
    ReadAvailability(){this(()->System.nanoTime()/1_000_000);}
    ReadAvailability(LongSupplier now){this.now=now;}
    boolean ready(String key){Failure f=failures.get(key);return f==null||now.getAsLong()>=f.retryAt;}
    long failed(String key){
        Failure f=failures.computeIfAbsent(key,k->new Failure());
        long delay=RETRY_MS[Math.min(f.count++,RETRY_MS.length-1)];
        f.retryAt=now.getAsLong()+delay;return delay;
    }
    void success(String key){failures.remove(key);}
    void clear(){failures.clear();}
}
