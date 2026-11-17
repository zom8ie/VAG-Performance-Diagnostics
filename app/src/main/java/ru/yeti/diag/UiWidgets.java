package ru.yeti.diag;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** Shared native view styling and menu components. */
final class UiWidgets {
    final Activity activity;
    UiWidgets(Activity activity){this.activity=activity;}

    static final int BG=0xFFE5EDE8, CARD=0xFFF6F9F7, TONAL=0xFFDCE7E0;
    static final int TEXT=0xFF192C24, MUTED=0xFF4D6358;
    static final int ACCENT=0xFF008A67, GREEN=0xFF006B50, RED=0xFFA63531, AMBER=0xFF896100;
    int dp(float v){return (int)(v*activity.getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    GradientDrawable panel(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    RippleDrawable ripple(int ink,GradientDrawable background,int radius){
        return new RippleDrawable(ColorStateList.valueOf(ink),background,panel(Color.WHITE,radius));
    }
    Button labelButton(String label,int size,int color){
        Button button=new Button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(size);
        button.setTextColor(color);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    /** Compact action used in the equally sized action row of a block card. */
    Button blockActionButton(String label){
        Button button=labelButton(label,13,TEXT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2),dp(8),dp(2),dp(8));
        button.setBackground(ripple(0x22006B50,panel(TONAL,16),16));
        return button;
    }

    ImageButton iconButton(int icon,String description,int color,boolean filled){
        ImageButton button=new ImageButton(activity);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(color));
        button.setContentDescription(description);
        button.setBackground(ripple(0x22006B50,filled?panel(TONAL,24):null,24));
        button.setPadding(dp(12),dp(12),dp(12),dp(12));
        return button;
    }
    void menuCard(LinearLayout body,int icon,String label,String detail,Runnable action){
        LinearLayout row=cardRow(body,icon,label,detail);
        ImageView arrow=cardIcon(R.drawable.ic_next,MUTED);
        row.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
        row.setBackground(ripple(0x22006B50,panel(CARD,20),20));
        row.setOnClickListener(v->action.run());
        row.setFocusable(true);
        row.setContentDescription(label+", "+detail);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(view,info);
                info.setClassName(Button.class.getName());
            }
        });
    }
    LinearLayout messageCard(LinearLayout body,int icon,String label,String detail){
        return cardRow(body,icon,label,detail);
    }
    LinearLayout cardRow(LinearLayout body,int icon,String label,String detail){
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(76));
        row.setPadding(dp(14),dp(12),dp(14),dp(12));
        row.setBackground(panel(CARD,20));
        ImageView symbol=cardIcon(icon,GREEN);
        symbol.setBackground(panel(TONAL,14));
        symbol.setPadding(dp(10),dp(10),dp(10),dp(10));
        row.addView(symbol,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout copy=new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(label,17,TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        name.setPadding(0,0,0,0);
        TextView description=text(detail,14,MUTED);
        description.setPadding(0,dp(2),0,0);
        copy.addView(name);
        copy.addView(description);
        LinearLayout.LayoutParams copyParams=new LinearLayout.LayoutParams(0,-2,1);
        copyParams.setMarginStart(dp(14));
        copyParams.setMarginEnd(dp(8));
        row.addView(copy,copyParams);

        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);
        rowParams.setMargins(0,dp(6),0,dp(4));
        body.addView(row,rowParams);
        return row;
    }
    ImageView cardIcon(int icon,int color){
        ImageView view=new ImageView(activity);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(color));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
}
