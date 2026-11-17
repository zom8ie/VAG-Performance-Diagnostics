package ru.yeti.diag;

/** Physical diagnostic routes. OBD and extended engine readings share one block. */
enum DiagnosticBlock {
    ENGINE("7E0","7E8",1), DSG("7E1","7E9",2), ABS("713","77D",3),
    EPS("712","77C",9), GATEWAY("710","77A",0x1F), HALDEX("70F","779",0);

    final String tx, rx;
    final int tpAddress;
    DiagnosticBlock(String tx,String rx,int tpAddress){
        this.tx=tx;
        this.rx=rx;
        this.tpAddress=tpAddress;
    }
    static DiagnosticBlock forMode(String mode){
        if(mode.equals("ECM")||mode.equals("OBD")||mode.equals(Engine.COMBINED_ENGINE))return ENGINE;
        return valueOf(mode);
    }
}
