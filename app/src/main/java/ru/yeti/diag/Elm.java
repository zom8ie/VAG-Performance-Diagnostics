package ru.yeti.diag;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One owner thread: a new command is never sent before the previous prompt. */
public final class Elm {
    public interface Wire { String exchange(String command,int timeoutMs) throws IOException; }
    public interface Events { void record(String kind,String command,String detail,long elapsed); }
    static final class ResponseTimeout extends IOException {
        ResponseTimeout(){
            super("ELM не завершил ответ. Подключись повторно; частичный ответ сохранён.");
        }
    }
    public static final class StreamWire implements Wire {
        private final InputStream in; private final OutputStream out; private final Events events;
        public volatile boolean closed;
        public StreamWire(InputStream in,OutputStream out,Events events){this.in=in;this.out=out;this.events=events;}
        @Override public String exchange(String cmd,int timeoutMs) throws IOException {
            if(closed)throw new IOException("Соединение закрыто");
            try{return exchangeUntilPrompt(cmd,timeoutMs);}
            catch(IOException e){closed=true;throw e;}
        }
        private String exchangeUntilPrompt(String cmd,int timeoutMs)throws IOException{
            long start=System.nanoTime(); events.record("tx",cmd,"",0);
            out.write((cmd+"\r").getBytes(StandardCharsets.US_ASCII));out.flush();
            StringBuilder b=new StringBuilder();int received=0;
            while((System.nanoTime()-start)/1_000_000<timeoutMs) {
                if(closed)throw new IOException("Соединение закрыто");
                if(in.available()>0) {
                    int x=in.read(); if(x<0)throw new EOFException("Bluetooth закрыт");
                    b.append((char)x);
                    if(++received>65536)throw new IOException("Слишком длинный ответ ELM");
                    if(x=='>') {
                        String s=b.toString();
                        // Reset can first interrupt the previous operation with an empty prompt.
                        // Its startup banner must finish before ATE0 is sent, within the same deadline.
                        String content=s.replace(">","").trim();
                        if(cmd.equals("ATZ")&&(content.isEmpty()||content.equalsIgnoreCase("ATZ"))){
                            events.record("reset_pending",cmd,s,(System.nanoTime()-start)/1_000_000);
                            b.setLength(0);continue;
                        }
                        events.record("rx",cmd,s,(System.nanoTime()-start)/1_000_000);return s;
                    }
                } else try {Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Остановлено",e);}
            }
            // Do not send another request after a timeout: a late response could be misattributed.
            events.record("timeout",cmd,b.toString(),timeoutMs);
            throw new ResponseTimeout();
        }
    }
    public final Wire wire; public final Events events;
    public Elm(Wire wire,Events events){this.wire=wire;this.events=events;}
    public String at(String command) throws IOException {
        if(!allowedAt(command))throw new IOException("Запрещённая команда адаптера");
        return wire.exchange(command,command.equals("ATZ")?5000:3000);
    }
    public void ok(String c) throws IOException {
        String r=at(c);
        if(!r.contains("OK"))throw new IOException(c+": "+r.replaceAll("[\r\n>]"," ").trim());
    }
    public static boolean allowedAt(String c) {
        return Arrays.asList("ATZ","ATI","AT@1","ATRV","ATDP","ATDPN","ATE0","ATL0","ATS1","ATH1","ATD0","ATAT1","ATAT0","ATST64","ATST0A","ATST10","ATST18","ATST32","ATSP0","ATSP6","ATSPB","ATPBC001","ATCAF0","ATCAF1","ATCFC0","ATCFC1","ATFCSD300000","ATFCSM1","ATFCSM0","ATV1","ATV0","ATAL","ATPC","ATAR").contains(c)
            || c.matches("ATFCSH[0-9A-F]{3}") || c.matches("ATSH(?:[0-9A-F]{3}|[0-9A-F]{8})") || c.matches("ATCRA(?:[0-9A-F]{3}|[0-9A-F]{8})");
    }
    public static boolean allowedObd(String c) {
        if(Arrays.asList("03","07","0A","0100","0120","0140","0160","0180","01A0","01C0","01E0","0101","0103","0113","011C","0141").contains(c))return true;
        for(Protocol.Pid p:Protocol.PIDS)if(c.equals(String.format(Locale.ROOT,"01%02X",p.id)))return true;
        return false;
    }
    public String obd(String c) throws IOException {
        if(!allowedObd(c))throw new IOException("Разрешено только чтение известных параметров");
        return wire.exchange(c,16000);
    }
    /** A physical mode-01 reply with at most five data bytes fits one CAN frame. */
    String singleFramePid(Protocol.Pid pid)throws IOException{
        if(!Protocol.PIDS.contains(pid)||pid.size>5)throw new IOException("Параметр не помещается в один кадр");
        return wire.exchange(String.format(Locale.ROOT,"01%02X1",pid.id),16000);
    }
    /** Explicitly named state-changing command; it must only follow user confirmation. */
    public String clearObdFaults() throws IOException {return wire.exchange("04",16000);}
    public void init() throws IOException {
        at("ATZ");ok("ATE0");ok("ATL0");ok("ATS1");ok("ATH1");at("ATD0");
        at("ATI");at("AT@1");ok("ATAT1");ok("ATST64");ok("ATSP0");ok("ATCAF1");ok("ATCFC1");
    }
    public void canPhysical(String tx,String rx) throws IOException {ok("ATSH"+tx);ok("ATCRA"+rx);}
    public void isoPhysical(String tx,String rx) throws IOException {canPhysical(tx,rx);ok("ATFCSH"+tx);ok("ATFCSD300000");ok("ATFCSM1");}
    public String udsRead(String did) throws IOException {
        if(!allowedUdsDid(did))throw new IOException("Неизвестный идентификатор");
        return wire.exchange("22"+did,4000);
    }
    static boolean allowedUdsDid(String did){
        boolean allowed=Arrays.asList(VehicleData.ID_FIELDS).contains(did)
            ||Arrays.asList("0600","F1A0","F1A1").contains(did);
        for(Haldex.Sensor sensor:Haldex.SENSORS)allowed |= sensor.did.equals(did);
        return allowed;
    }
    public String udsFaults() throws IOException {return wire.exchange("1902FF",4000);}
    /** Clears one UDS block only after a confirmed action in the interface. */
    public String clearUdsFaults() throws IOException {return wire.exchange("14FFFFFF",4000);}
}
