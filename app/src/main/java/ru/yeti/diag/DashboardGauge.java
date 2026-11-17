package ru.yeti.diag;

import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;
import androidx.car.app.model.CarIcon;
import androidx.core.graphics.drawable.IconCompat;
import java.util.List;

/** Draws the instruments painted on the Android Auto navigation surface. */
final class DashboardGauge {
    private static final int WIDTH=DashboardTouch.CELL_WIDTH;
    private static final int HEIGHT=DashboardTouch.CELL_HEIGHT;
    private static final int LABEL_WIDTH=118;
    private static final float DIAL_CENTER_X=WIDTH/2f;
    private static final float DIAL_CENTER_Y=55;
    private static final float DIAL_RADIUS=42;
    private static final float READING_WIDTH=76;
    private final LruCache<String,CarIcon> images=new LruCache<>(DashboardCatalog.CELLS*2);

    private static Bitmap bitmap(int width,int height){
        Bitmap bitmap=Bitmap.createBitmap(width*2,height*2,Bitmap.Config.ARGB_8888);
        // Explicit density avoids inheriting the phone's density on a different car display.
        bitmap.setDensity(160);
        return bitmap;
    }

    /** Scales the whole 3×2 panel into the given rectangle, keeping the dials circular. */
    void drawPanel(Canvas canvas,int width,int height,List<DashboardCatalog.Parameter> cells,
                   List<String> values,List<String> units,List<DashboardReadings.Sample> scales,List<Integer> positions){
        canvas.drawColor(0xFF12191F);
        RectF rect=panelRect(width,height);
        float scale=rect.width()/(WIDTH*DashboardCatalog.COLUMNS);
        canvas.save();
        canvas.translate(rect.left,rect.top);
        canvas.scale(scale,scale);
        for(int cell=0;cell<cells.size();cell++){
            DashboardCatalog.Parameter parameter=cells.get(cell);
            canvas.save();
            canvas.translate((cell%DashboardCatalog.COLUMNS)*WIDTH,(cell/DashboardCatalog.COLUMNS)*HEIGHT);
            draw(canvas,parameter==null?"Не выбрано":label(parameter),parameter==null?"—":values.get(cell),
                units.get(cell),scales.get(cell),positions.get(cell));
            canvas.restore();
        }
        canvas.restore();
    }

    /** Where the panel lands inside a rectangle; also used to map taps onto cells. */
    static RectF panelRect(int width,int height){
        float[] bounds=DashboardTouch.bounds(width,height);
        return new RectF(bounds[0],bounds[1],bounds[2],bounds[3]);
    }

    /** One instrument enlarged to the whole surface. */
    void drawSingle(Canvas canvas,int width,int height,DashboardCatalog.Parameter parameter,
                    String value,String unit,DashboardReadings.Sample scale,int position){
        canvas.drawColor(0xFF12191F);
        float factor=Math.min(width/(float)WIDTH,height/(float)HEIGHT);
        canvas.save();
        canvas.translate((width-WIDTH*factor)/2f,(height-HEIGHT*factor)/2f);
        canvas.scale(factor,factor);
        draw(canvas,parameter==null?"Не выбрано":label(parameter),parameter==null?"—":value,unit,scale,position);
        canvas.restore();
    }

    CarIcon control(String text){
        String key="control/"+text;
        CarIcon cached=images.get(key);
        if(cached!=null)return cached;
        // The host fixes the button size; only the glyph inside is ours, so keep it small and dim.
        Bitmap bitmap=bitmap(48,48);
        Canvas canvas=new Canvas(bitmap);
        canvas.scale(2,2);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF93A6B2);
        paint.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
        paint.setTextSize(13);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text,24,24-(paint.ascent()+paint.descent())/2,paint);
        CarIcon icon=new CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build();
        images.put(key,icon);
        return icon;
    }

    private static void draw(Canvas canvas,String label,String value,String unit,DashboardReadings.Sample scale,
                             int position){
        Paint dial=new Paint(Paint.ANTI_ALIAS_FLAG);
        dial.setShader(new LinearGradient(0,0,0,HEIGHT,0xFF27323B,0xFF12191F,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(1,1,WIDTH-1,HEIGHT-1,4,4,dial);
        dial.setShader(null);
        dial.setStyle(Paint.Style.STROKE);
        dial.setStrokeWidth(2);
        dial.setColor(0xFF7E919F);
        canvas.drawRoundRect(2,2,WIDTH-2,HEIGHT-2,4,4,dial);

        drawScale(canvas,dial,scale,position);
        if(scale!=null)drawScaleBounds(canvas,scale);

        Paint reading=new Paint(Paint.ANTI_ALIAS_FLAG);
        reading.setTextAlign(Paint.Align.CENTER);
        reading.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
        String[] lines=value.split("\n");
        String[] parts=DashboardTouch.reading(lines[0],unit);
        if(!parts[1].isEmpty()){
            reading.setColor(0xFFE0E8F0);
            reading.setTextSize(10);
            canvas.drawText(parts[1],DIAL_CENTER_X,26-(reading.ascent()+reading.descent())/2,reading);
        }
        reading.setColor(Color.WHITE);
        reading.setTextSize(26);
        float width=reading.measureText(parts[0]);
        if(width>READING_WIDTH)reading.setTextSize(26*READING_WIDTH/width);
        canvas.drawText(parts[0],DIAL_CENTER_X,45-(reading.ascent()+reading.descent())/2,reading);
        for(int index=1;index<lines.length;index++){
            reading.setTextSize(11);
            float line=reading.measureText(lines[index]);
            if(line>READING_WIDTH)reading.setTextSize(11*READING_WIDTH/line);
            float center=58+(index-1)*10;
            canvas.drawText(lines[index],DIAL_CENTER_X,center-(reading.ascent()+reading.descent())/2,reading);
        }

        TextPaint captionPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        captionPaint.setColor(0xFFE0E8F0);
        captionPaint.setTypeface(Typeface.create("sans-serif-condensed",Typeface.NORMAL));
        int captionHeight=23;
        StaticLayout caption=fitLabel(label,captionPaint,LABEL_WIDTH,captionHeight);
        canvas.save();
        canvas.translate((WIDTH-LABEL_WIDTH)/2f,68+(captionHeight-caption.getHeight())/2f);
        caption.draw(canvas);
        canvas.restore();
    }

    private static void drawScale(Canvas canvas,Paint paint,DashboardReadings.Sample scale,int position){
        RectF arc=new RectF(DIAL_CENTER_X-DIAL_RADIUS,DIAL_CENTER_Y-DIAL_RADIUS,
            DIAL_CENTER_X+DIAL_RADIUS,DIAL_CENTER_Y+DIAL_RADIUS);
        paint.setStrokeWidth(6);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(0xFF365A4E);
        canvas.drawArc(arc,180,180,false,paint);
        if(scale==null)return;

        float sweep=180f*position/100;
        paint.setColor(UiWidgets.ACCENT);
        canvas.drawArc(arc,180,sweep,false,paint);

        paint.setColor(0xFFD6E2DA);
        for(int tick=0;tick<=12;tick++){
            paint.setStrokeWidth(tick%3==0?1.4f:0.8f);
            drawMark(canvas,paint,180+15*tick,DIAL_RADIUS+4,DIAL_RADIUS+(tick%3==0?9:7));
        }
        paint.setColor(0xFF9DE1C5);
        paint.setStrokeWidth(2.5f);
        drawMark(canvas,paint,180+sweep,DIAL_RADIUS-4,DIAL_RADIUS+4);
    }

    private static void drawMark(Canvas canvas,Paint paint,float degrees,float inner,float outer){
        double angle=Math.toRadians(degrees);
        float x=(float)Math.cos(angle);
        float y=(float)Math.sin(angle);
        canvas.drawLine(DIAL_CENTER_X+inner*x,DIAL_CENTER_Y+inner*y,
            DIAL_CENTER_X+outer*x,DIAL_CENTER_Y+outer*y,paint);
    }

    private static void drawScaleBounds(Canvas canvas,DashboardReadings.Sample scale){
        Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFE0E8F0);
        text.setTypeface(Typeface.create("sans-serif-condensed",Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12);
        float width=Math.max(text.measureText(scale.minimum),text.measureText(scale.maximum));
        if(width>40)text.setTextSize(text.getTextSize()*40/width);
        canvas.drawText(scale.minimum,DIAL_CENTER_X-DIAL_RADIUS,66,text);
        canvas.drawText(scale.maximum,DIAL_CENTER_X+DIAL_RADIUS,66,text);
    }

    private static String label(DashboardCatalog.Parameter parameter){
        switch(parameter.id){
            case "ECM/g115f2":
            case "ECM/g117f3": return "Заданный наддув (абс.)";
            case "ECM/g115f3": return "Фактический наддув (абс.)";
            case "DSG/g1f0": return "Стоп-сигнал F";
            case "DSG/g1f1": return "Тормоз F47";
            case "DSG/g4f0": return "Селектор";
            case "DSG/g10f2": return "Момент ДВС";
            case "DSG/g11f3": return "Давление K1";
            case "DSG/g12f3": return "Давление K2";
            case "DSG/g20f1": return "Фактическая передача";
            case "HALDEX/h2BE6": return "Ток насоса";
            case "HALDEX/h2BE7": return "Управление насосом";
            case "HALDEX/h2BE9": return "Напряжение насоса";
            case "HALDEX/h2BF3": return "Замыкание муфты";
            case "OBD/maf_power": return "Расчётная мощность";
            default: return parameter.name;
        }
    }

    private static StaticLayout fitLabel(String label,TextPaint paint,int width,int height){
        int textSize=19;
        StaticLayout layout;
        do{
            paint.setTextSize(textSize);
            layout=StaticLayout.Builder.obtain(label,0,label.length(),paint,width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build();
            textSize--;
        }while(layout.getHeight()>height&&textSize>0);
        return layout;
    }

}
