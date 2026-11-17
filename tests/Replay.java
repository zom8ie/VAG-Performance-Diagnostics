package ru.yeti.diag;

import java.io.IOException;
import java.util.*;

/** Scripted wire shared by transport and diagnostic integration tests. */
final class Replay implements Elm.Wire {
    final Queue<String[]> steps=new ArrayDeque<>();
    Replay add(String tx,String rx){steps.add(new String[]{tx,rx});return this;}
    public String exchange(String c,int timeout)throws IOException {
        if(c.startsWith("AT"))return "OK\r>";
        String[] step=steps.poll();if(step==null||!step[0].equals(c))throw new AssertionError("Unexpected command "+c+" expected "+Arrays.toString(step));
        return step[1];
    }
}
