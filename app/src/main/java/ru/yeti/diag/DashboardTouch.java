package ru.yeti.diag;

/** Tap and scale gestures of the full-screen panel; the host reports double taps as scaling. */
final class DashboardTouch {
    static final int CELL_WIDTH=128;
    static final int CELL_HEIGHT=94;

    /** Where the 3×2 design lands inside the surface, centred and keeping dials circular. */
    static float[] bounds(int width,int height){
        float designWidth=CELL_WIDTH*DashboardCatalog.COLUMNS;
        float designHeight=CELL_HEIGHT*DashboardCatalog.ROWS;
        float scale=Math.min(width/designWidth,height/designHeight);
        float panelWidth=designWidth*scale;
        float panelHeight=designHeight*scale;
        float left=(width-panelWidth)/2f;
        float top=(height-panelHeight)/2f;
        return new float[]{left,top,left+panelWidth,top+panelHeight};
    }

    static int cellAt(float x,float y,int width,int height){
        float[] bounds=bounds(width,height);
        if(x<bounds[0]||y<bounds[1]||x>=bounds[2]||y>=bounds[3])return -1;
        int column=(int)((x-bounds[0])/((bounds[2]-bounds[0])/DashboardCatalog.COLUMNS));
        int row=(int)((y-bounds[1])/((bounds[3]-bounds[1])/DashboardCatalog.ROWS));
        return row*DashboardCatalog.COLUMNS+column;
    }

    static int pageAction(int cell){
        int column=cell%DashboardCatalog.COLUMNS;
        if(column==0)return -1;
        if(column==DashboardCatalog.COLUMNS-1)return 1;
        return 0;
    }

    static int shifted(int page,int delta){
        return (page+delta+DashboardCatalog.PAGES)%DashboardCatalog.PAGES;
    }

    /** Splits a displayed reading into the number and the small unit line above it. */
    static String[] reading(String text,String unit){
        if(!unit.isEmpty()&&text.endsWith(" "+unit))
            return new String[]{text.substring(0,text.length()-unit.length()-1),unit};
        return new String[]{text,""};
    }
}