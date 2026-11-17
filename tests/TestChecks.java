package ru.yeti.diag;

final class TestChecks {
    static int checks=0;
    static void check(boolean v,String msg){checks++;if(!v)throw new AssertionError(msg);}
    static void near(double a,double b,String label){check(Math.abs(a-b)<.00001,label+": "+a+" != "+b);}
    static Protocol.Pid pid(int id){for(Protocol.Pid p:Protocol.PIDS)if(p.id==id)return p;throw new AssertionError(id);}
    static final Elm.Events EVENTS=(k,c,d,t)->{};
}
