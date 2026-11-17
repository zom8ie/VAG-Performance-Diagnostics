package ru.yeti.diag;

import java.io.*;
import java.util.*;

/** Experimental TP2.0 transport; limited to short read-only KWP requests.
 * Protocol reference: Jared Wiltshire, https://jazdw.net/tp20 .
 */
public final class Tp20 {
    interface RetryPause { void waitMillis(int millis) throws IOException; }
    static final class ChannelBusy extends IOException {
        ChannelBusy(){super("Канал TP2.0 не открыт: ответ D7 повторился после паузы");}
    }
    // The engine sometimes still refused after the short retry in the logs.
    // Give only this address extra time; the normal and first retry are unchanged.
    private static final int ENGINE_D7_RETRY_DELAY_MS=6000;
    private static final int D7_RETRY_DELAY_MS=3500;
    private final Elm elm;private final RetryPause retryPause;
    private String rx="300";private int txSequence,rxSequence;
    private String tx="";private boolean open;private long lastSend; private int separationMs=10, destination;
    public Tp20(Elm elm){this(elm,Tp20::pause);}
    Tp20(Elm elm,RetryPause retryPause){this.elm=elm;this.retryPause=retryPause;}
    private static void pause(int millis)throws IOException {
        try{Thread.sleep(millis);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException("Остановлено",ex);}
    }
    private List<byte[]> send(String s) throws IOException {
        long wait=separationMs-(System.nanoTime()/1_000_000-lastSend);
        if(wait>0)try{Thread.sleep(wait);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IOException(ex);}
        String response=elm.wire.exchange(s,2500);lastSend=System.nanoTime()/1_000_000;
        List<byte[]> out=new ArrayList<>();
        for(Protocol.Frame f:Protocol.frames(response))if(f.id.equals(rx))out.add(f.data);
        return out;
    }
    public void open(int address) throws IOException {open(address,false);}
    public void open(int address,boolean firstSetupResponse) throws IOException {
        if(!knownAddress(address))throw new IOException("Блок вне профиля Yeti");
        destination=address;
        elm.ok("ATPC");elm.ok("ATPBC001");elm.ok("ATSPB");elm.ok("ATCAF0");
        elm.ok("ATCFC0");elm.ok("ATV1");elm.ok("ATH1");elm.ok("ATS1");elm.at("ATD0");
        elm.ok("ATAT0");elm.ok("ATST0A");
        rx=String.format(Locale.ROOT,"%03X",0x200+address);elm.canPhysical("200",rx);
        String setup=String.format(Locale.ROOT,"%02XC00010000301",address)+(firstSetupResponse?"1":"");
        byte[] p=setupReply(setup);
        if(Protocol.u(p[1])!=0xD0||(Protocol.u(p[3])&0xF0)!=0||(Protocol.u(p[5])&0xF0)!=0)
            throw new IOException("Блок отказал в открытии канала TP2.0");
        int recv=Protocol.u(p[2])|((Protocol.u(p[3])&15)<<8);
        int assignedTx=Protocol.u(p[4])|((Protocol.u(p[5])&15)<<8);
        if(recv!=0x300||assignedTx<0x300||assignedTx>0x7FF)throw new IOException("Неверные адреса TP2.0");
        rx=String.format(Locale.ROOT,"%03X",recv);tx=String.format(Locale.ROOT,"%03X",assignedTx);elm.canPhysical(tx,rx);
        List<byte[]> param=send("A00F8AFF32FF");
        if(param.size()!=1||param.get(0).length!=6||Protocol.u(param.get(0)[0])!=0xA1)
            throw new IOException("Не согласованы параметры TP2.0");
        int t=Protocol.u(param.get(0)[4]);double[] scales={.1,1,10,100};
        separationMs=(int)Math.ceil((t&63)*scales[t>>6]);
        if(separationMs>200)throw new IOException("Слишком медленный канал TP2.0");
        txSequence=0;rxSequence=0;open=true;
        if(address!=1&&address!=2){
            elm.ok("ATST18");
            return;
        }
        // The short session-open reply must return before the ECU repeats its
        // final TP2.0 frame. Longer measurement groups use the normal timeout
        // again after the handshake is complete.
        elm.ok("ATST0A");
        byte[] session=request("1089");
        if(session.length<2||Protocol.u(session[0])!=0x50||Protocol.u(session[1])!=0x89)
            throw new IOException("Блок не открыл сеанс измерений");
        elm.ok("ATST18");
    }
    private byte[] setupReply(String setup)throws IOException {
        int finalDelay=destination==1?ENGINE_D7_RETRY_DELAY_MS:D7_RETRY_DELAY_MS;
        for(int delay:new int[]{0,180,finalDelay}){
            if(delay==finalDelay)
                elm.events.record("tp20_retry",setup,"D7; delay_ms="+delay,0);
            if(delay>0)retryPause.waitMillis(delay);
            List<byte[]> reply=send(setup);
            if(reply.size()!=1||reply.get(0).length!=7)throw new IOException("Блок не ответил по TP2.0");
            byte[] frame=reply.get(0);
            if(Protocol.u(frame[1])!=0xD7)return frame;
        }
        throw new ChannelBusy();
    }
    public static boolean allowedRequest(String s) {
        for(int address:new int[]{1,2,3,9,0x1F})if(allowedRequest(address,s))return true;
        return false;
    }
    static boolean allowedRequest(int address,String s) {
        if(!knownAddress(address))return false;
        if(s.equals("1089"))return address==1||address==2;
        if(s.equals("1A9B"))return true;
        if(s.equals("1800FF00"))return true;
        if(s.equals("1A9F"))return address==0x1F;
        if(!s.matches("21[0-9A-F]{2}"))return false;
        int g=Integer.parseInt(s.substring(2),16);
        String mode=address==1?"ECM":address==2?"DSG":address==3?"ABS":address==9?"EPS":"GATEWAY";
        return VehicleData.groups(mode).contains(g);
    }
    private static boolean knownAddress(int address){
        return address==1||address==2||address==3||address==9||address==0x1F;
    }
    public byte[] request(String request) throws IOException {
        if(!open||!allowedRequest(destination,request))throw new IOException("Недопустимый запрос TP2.0");
        byte[] data=Protocol.bytes(request);
        String packet=String.format(Locale.ROOT,"%02X%04X",0x10|txSequence,data.length)+request;
        txSequence=(txSequence+1)&15;
        Deque<byte[]> queue=new ArrayDeque<>(send(packet));boolean ack=false,ackRequired=false,first=true,done=false;
        byte[] finalFrame=null;
        int length=-1;ByteArrayOutputStream body=new ByteArrayOutputStream();
        int frames=0,controlExchanges=0,queuedChannelTests=0,pendingControlReplies=0;
        if(queue.isEmpty())throw new IOException("Блок не ответил на запрос TP2.0 "+request);
        while(!queue.isEmpty()||queuedChannelTests>0) {
                if(queue.isEmpty()){
                    queuedChannelTests--;
                    pendingControlReplies++;
                    queue.addAll(send("A3"));
                    continue;
                }
                if(++frames>1024)throw new IOException("Слишком много кадров TP2.0");
                byte[] f=queue.removeFirst();
                if(f.length==0)throw new IOException("Пустой кадр TP2.0");
                // ACK any data already received before waiting for the channel test.
                if(Protocol.u(f[0])==0xA3){
                    if(f.length!=1||++controlExchanges>16)throw new IOException("Некорректный обмен поддержания TP2.0");
                    queuedChannelTests++;continue;
                }
                if(Protocol.u(f[0])==0xA1&&f.length==6&&pendingControlReplies>0){pendingControlReplies--;continue;}
                int op=Protocol.u(f[0])>>4,seq=Protocol.u(f[0])&15;
                if(op==0xB) {if(f.length!=1||seq!=txSequence)throw new IOException("Неверное подтверждение TP2.0");ack=true;continue;}
                if(Protocol.u(f[0])==0xA8||Protocol.u(f[0])==0xA4) {open=false;throw new IOException("Блок закрыл канал TP2.0");}
                // Some VAG ECUs retransmit the completed single-frame reply
                // when the ELM clone returns the whole CAN burst as one text
                // response. It is the same data, not a second reply.
                if(done&&finalFrame!=null&&Arrays.equals(f,finalFrame))continue;
                if(done)throw new IOException("Неожиданный кадр TP2.0 после завершения ответа");
                if(op>3)throw new IOException("Неожиданный кадр TP2.0");
                if(seq!=rxSequence)throw new IOException("Потеря последовательности TP2.0");
                rxSequence=(rxSequence+1)&15;int offset=1;
                if(first) {
                    if(f.length<3)throw new IOException("Короткий заголовок TP2.0");
                    length=((Protocol.u(f[1])&127)<<8)|Protocol.u(f[2]);offset=3;first=false;
                    if(length<1||length>4096)throw new IOException("Неверная длина TP2.0");
                }
                body.write(f,offset,f.length-offset);
                if(body.size()>length)throw new IOException("TP2.0: ответ длиннее заголовка");
                done=(op&1)!=0;
                if(done)finalFrame=Arrays.copyOf(f,f.length);
                if((op&2)==0){ackRequired=true;queue.addAll(send(String.format(Locale.ROOT,"%02X",0xB0|rxSequence)));}
        }
        if(!done||(ackRequired&&!ack)||body.size()!=length)throw new IOException("TP2.0: длина/подтверждение не совпали");
        byte[] result=body.toByteArray();elm.events.record("kwp",request,Protocol.hex(result),0);return result;
    }
    public void close() {if(open){try{send("A8");}catch(IOException ignored){}open=false;}}
}
