package ru.yeti.diag;

import java.io.*;
import java.util.*;
import static ru.yeti.diag.TestChecks.*;

final class CanProtocolTest {
    static void run() throws Exception {
        check(Protocol.frames("SEARCHING...\r7E8 04 41 0C 1A F8\r>").size()==1,"frame plus searching");
        check(Protocol.frames("7E8 5 04 41 0C 1A F8\r>").size()==1,"clone DLC");
        check(Protocol.frames("7E8 8 04 41 0C 1A F8\r>").isEmpty(),"invalid DLC rejected");
        check(Protocol.frames("010C\rNO DATA\rCAN ERROR\r>").isEmpty(),"text not hex data");
        String two="7E8 06 41 00 80 00 00 01\r7E9 06 41 00 40 00 00 00\r>";
        Set<Integer> supported=Protocol.supported(0,Protocol.positive(two,"7E8",0x41,0));
        check(supported.equals(new LinkedHashSet<>(Arrays.asList(1,32))),"ECU masks isolated and bit order correct");
        check(Protocol.positive(two,"7E8",0x41,12)==null,"PID mismatch rejected");
        check(Protocol.supported(0,new byte[]{1,2}).isEmpty(),"short mask");
        String multi="7E8 10 0A 43 04 03 04 01 71\r7E9 03 41 05 40\r7E8 21 01 72 01 73 00 00 00\r>";
        check(Protocol.hex(Protocol.messages(multi).get("7E8")).equals("43040304017101720173"),"multi frame exact length");
        check(Protocol.dtcs(Protocol.messages(multi).get("7E8")).equals(Arrays.asList("P0304","P0171","P0172","P0173")),"CAN DTC count");
        check(Protocol.messages(multi.replace("7E8 21","7E8 22")).get("7E8")==null,"wrong sequence rejected");
        check(Protocol.messages("7E8 10 0A 43 04 03 04 01 71\r>").isEmpty(),"partial response rejected");
        check(Protocol.dtcs(Protocol.bytes("4303040171")).equals(Arrays.asList("P0304","P0171")),"legacy pairs");
        check(Protocol.dtcs(Protocol.bytes("4300")).isEmpty(),"zero DTCs");
        check(Protocol.udsDtcs(Protocol.bytes("5902FF40311A08")).equals(Collections.singletonList("C00311A · 08")),"UDS DTC keeps symptom and status");
        check(Protocol.udsDtcs(Protocol.bytes("5902FF")).isEmpty()&&Protocol.udsDtcs(Protocol.bytes("7F1931"))==null,"negative not empty DTC list");
        check(Protocol.kwpDtcs(Protocol.bytes("5801011D08")).equals(Collections.singletonList("00285 · 08")),"KWP DTC number and status");
        check(Protocol.kwpDtcs(Protocol.bytes("5802"))==null,"KWP wrong count");
        check(Protocol.gatewayModules(Protocol.bytes("5A9F09010100010303000101FF")).equals(Arrays.asList(1,3)),"gateway installed modules");
        check(Protocol.gatewayModules(Protocol.bytes("5A9F0901010001"))==null,"truncated gateway rejected");
    }
}
