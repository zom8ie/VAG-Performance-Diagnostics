package ru.yeti.diag;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

/** Draws the panel straight onto the host's map surface, outside the template grid. */
final class DashboardSurface implements SurfaceCallback {
    interface Painter {void draw(Canvas canvas,int width,int height);}
    interface Clicks {void click(float x,float y);}
    interface Scales {void scale(float focusX,float focusY,float factor);}

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable retry=this::redraw;
    private SurfaceContainer container;
    private Painter painter;
    private Clicks clicks;
    private Scales scales;
    private int width,height;
    private boolean drawing;
    private int retries;

    void setPainter(Painter painter){this.painter=painter;}

    void setClicks(Clicks clicks){this.clicks=clicks;}

    void setScales(Scales scales){this.scales=scales;}

    int viewWidth(){return width;}

    int viewHeight(){return height;}

    @Override public void onSurfaceAvailable(SurfaceContainer surface){
        container=surface;
        retries=0;
        redraw();
    }

    @Override public void onSurfaceDestroyed(SurfaceContainer surface){
        container=null;
        handler.removeCallbacks(retry);
    }

    @Override public void onVisibleAreaChanged(Rect area){
        redraw();
    }

    @Override public void onStableAreaChanged(Rect area){
        redraw();
    }

    @Override public void onClick(float x,float y){
        if(clicks!=null)clicks.click(x,y);
    }

    // The host consumes double taps itself and reports them as a scale gesture.
    @Override public void onScale(float focusX,float focusY,float scaleFactor){
        if(scales!=null)scales.scale(focusX,focusY,scaleFactor);
    }

    void redraw(){
        if(container==null||painter==null||drawing)return;
        Surface surface=container.getSurface();
        if(surface==null||!surface.isValid())return;
        Canvas canvas;
        drawing=true;
        try{
            canvas=surface.lockCanvas(null);
        }catch(IllegalArgumentException notReady){
            // The host can announce the surface before its display is ready; a crash here
            // would loop forever because every restart announces it again.
            drawing=false;
            if(retries<3){
                retries++;
                handler.postDelayed(retry,150);
            }
            return;
        }
        if(canvas==null){
            drawing=false;
            return;
        }
        retries=0;
        try{
            // The host composites the whole buffer: paint edge to edge, not just the visible rect.
            width=canvas.getWidth();
            height=canvas.getHeight();
            painter.draw(canvas,width,height);
        }finally{
            drawing=false;
            try{
                surface.unlockCanvasAndPost(canvas);
            }catch(IllegalArgumentException gone){
                // The host can destroy the surface mid-frame; the next announcement repaints.
            }
        }
    }
}