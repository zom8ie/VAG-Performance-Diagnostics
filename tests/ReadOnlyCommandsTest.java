package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class ReadOnlyCommandsTest {
    static void run() throws Exception {
        for(String forbidden:new String[]{"04","14FFFFFF","2EF19000","3101","2701","1101","1002","010C\r04","ATSH000"})check(!Elm.allowedObd(forbidden),"write blocked "+forbidden);
        check(!Elm.allowedAt("ATSH7E0\r04"),"AT injection blocked");
        check(!Elm.allowedAt("ATMA"),"monitor is not permitted");
        check(Tp20.allowedRequest("210F"),"misfire group allowed");
        check(!Tp20.allowedRequest("3101")&&!Tp20.allowedRequest("2701")&&!Tp20.allowedRequest("1101")&&!Tp20.allowedRequest("103B"),"KWP mutations excluded");
        check(Tp20.allowedRequest("2108")&&Tp20.allowedRequest("2110"),"DQ250 diagnostic groups allowed");
        check(Tp20.allowedRequest(2,"218C")&&Tp20.allowedRequest(2,"218D")&&!Tp20.allowedRequest(3,"218C"),"gearbox history limited to gearbox");
        check(Tp20.allowedRequest(1,"2173")&&Tp20.allowedRequest(1,"21E6"),"boost and fuel rail groups");
        check(Tp20.allowedRequest(3,"2105")&&Tp20.allowedRequest(3,"1800FF00")&&!Tp20.allowedRequest(3,"3105"),"ABS read-only");
        check(Tp20.allowedRequest(0x1F,"1A9F")&&!Tp20.allowedRequest(3,"1A9F"),"gateway read only at gateway");
        check(!Tp20.allowedRequest(3,"1089"),"ABS does not switch diagnostic session");
        check(Tp20.allowedRequest(9,"2101")&&Tp20.allowedRequest(9,"2107")&&Tp20.allowedRequest(9,"1800FF00")
            &&!Tp20.allowedRequest(9,"1089")&&!Tp20.allowedRequest(9,"3101"),"EPS read-only whitelist");
        check(Elm.allowedUdsDid("0600")&&Elm.allowedUdsDid("F1A0")&&Elm.allowedUdsDid("F1A1")
            &&!Elm.allowedUdsDid("F1A0\r2E0600"),"coding and dataset use exact read identifiers");
        List<String> writes=new ArrayList<>();
        Elm elm=new Elm((command,timeout)->{writes.add(command);return "7E8 44\r>";},(kind,command,detail,time)->{});
        elm.clearObdFaults();elm.clearUdsFaults();
        check(writes.equals(Arrays.asList("04","14FFFFFF")),"only named fault-clear commands can write");
        check(FaultCatalog.hasCodes("P0304, C1234")&&!FaultCatalog.hasCodes("Не обнаружены"),"fault dialog detects actual codes");
    }
}
