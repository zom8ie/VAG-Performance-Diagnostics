package ru.yeti.diag;

/** Stores whether a live parameter is included in the diagnostic polling plan. */
interface ParameterSelection {
    ParameterSelection ALL_ENABLED=new ParameterSelection(){
        public boolean isEnabled(String mode,String key){return true;}
        public void setEnabled(String mode,String key,boolean enabled){}
    };
    boolean isEnabled(String mode,String key);
    void setEnabled(String mode,String key,boolean enabled);
}
