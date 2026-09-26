package organic.swarm.home;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.*;
import android.view.HapticFeedbackConstants;
import android.widget.EditText;
import java.util.*;

public class SwarmView extends View {

    public interface Host {
        void eye();
        void memory();
        void explore(String q);
        void hive();
        void settings();
    }

    private final Host host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<App> apps = new ArrayList<>();

    private final RectF nucleus = new RectF();
    private final RectF eye = new RectF();
    private final RectF memory = new RectF();
    private final RectF explore = new RectF();
    private final RectF hive = new RectF();
    private final RectF brood = new RectF();

    private float pulse;
    private float downX, downY;
    private long downTime;
    private boolean broodOpen;

    private static final int VOID = Color.rgb(5,3,6);
    private static final int FLESH = Color.rgb(48,17,48);
    private static final int GLOW = Color.rgb(182,92,255);
    private static final int BONE = Color.rgb(242,200,255);

    public SwarmView(Context c, Host h) {
        super(c);
        host = h;
        setBackgroundColor(VOID);

        ValueAnimator a = ValueAnimator.ofFloat(0f,1f);
        a.setDuration(4000);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(v -> {
            pulse = (float)v.getAnimatedValue();
            invalidate();
        });
        a.start();

        refreshApps();
    }

    public void refreshApps() {
        apps.clear();

        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm = getContext().getPackageManager();

        for (ResolveInfo r : pm.queryIntentActivities(i,0)) {
            if (r.activityInfo.packageName.equals(getContext().getPackageName()))
                continue;

            App a = new App();
            a.name = r.loadLabel(pm).toString();
            a.pkg = r.activityInfo.packageName;
            a.cls = r.activityInfo.name;
            a.icon = r.loadIcon(pm);
            apps.add(a);
        }

        Collections.sort(apps,
            Comparator.comparing(x -> x.name.toLowerCase()));

        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        if (broodOpen) {
            drawBrood(c);
            return;
        }

        float w=getWidth(), h=getHeight();
        float cx=w/2f, cy=h*.46f;
        float breath=1f+pulse*.05f;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(Color.rgb(69,25,73));

        for(int k=0;k<12;k++) {
            double a=k*Math.PI*2/12;
            c.drawLine(cx,cy,
                cx+(float)Math.cos(a)*w*.55f,
                cy+(float)Math.sin(a)*h*.42f,p);
        }

        float nr=72*getResources().getDisplayMetrics().density*breath;
        nucleus.set(cx-nr,cy-nr,cx+nr,cy+nr);

        p.setStyle(Paint.Style.FILL);
        p.setColor(FLESH);
        c.drawOval(nucleus,p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8f+8f*pulse);
        p.setColor(GLOW);
        c.drawOval(nucleus,p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(BONE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);
        c.drawText("SWARM AWAKE",cx,cy+6,p);

        float ox=w*.24f, oy=h*.19f;
        organ(c,eye,cx-ox,cy-oy,"EYE");
        organ(c,memory,cx+ox,cy-oy,"MEMORY");
        organ(c,explore,cx-ox,cy+oy,"EXPLORE");
        organ(c,hive,cx+ox,cy+oy,"HIVE");
        organ(c,brood,cx,cy+h*.34f,"BROOD");
    }

    private void organ(Canvas c, RectF r, float x,float y,String name) {
        float d=getResources().getDisplayMetrics().density;
        float rx=62*d, ry=39*d;

        r.set(x-rx,y-ry,x+rx,y+ry);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(36,13,39));
        c.drawOval(r,p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3*d);
        p.setColor(Color.rgb(126,54,151));
        c.drawOval(r,p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(BONE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);
        c.drawText(name,x,y+5*d,p);
    }

    private void drawBrood(Canvas c) {
        float d=getResources().getDisplayMetrics().density;
        int cols=getWidth()/d>600?6:4;
        float cellW=getWidth()/(float)cols;
        float cellH=105*d;

        p.setTextAlign(Paint.Align.CENTER);

        for(int i=0;i<apps.size();i++) {
            int row=i/cols, col=i%cols;
            float x=col*cellW+cellW/2;
            float y=row*cellH+70*d;

            p.setColor(Color.rgb(35,12,38));
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(
                x-cellW*.43f,y-48*d,
                x+cellW*.43f,y+45*d,
                25*d,25*d,p);

            Drawable icon=apps.get(i).icon;
            if(icon!=null) {
                int s=(int)(42*d);
                icon.setBounds(
                    (int)x-s/2,(int)y-s/2-12*(int)d,
                    (int)x+s/2,(int)y+s/2-12*(int)d);
                icon.draw(c);
            }

            p.setColor(BONE);
            p.setTextSize(11*getResources().getDisplayMetrics().scaledDensity);
            c.drawText(shortName(apps.get(i).name),x,y+35*d,p);
        }
    }

    private String shortName(String s) {
        return s.length()>13?s.substring(0,12)+"…":s;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        float x=e.getX(), y=e.getY();

        if(e.getAction()==MotionEvent.ACTION_DOWN) {
            downX=x; downY=y;
            downTime=SystemClock.uptimeMillis();
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            return true;
        }

        if(e.getAction()==MotionEvent.ACTION_UP) {
            float dy=y-downY;

            if(dy < -80*getResources().getDisplayMetrics().density) {
                broodOpen=true;
                invalidate();
                return true;
            }

            if(broodOpen &&
               dy > 80*getResources().getDisplayMetrics().density) {
                broodOpen=false;
                invalidate();
                return true;
            }

            if(broodOpen) {
                launchApp(x,y);
                return true;
            }

            long held=SystemClock.uptimeMillis()-downTime;

            if(nucleus.contains(x,y) && held>550) {
                host.settings();
                return true;
            }

            if(eye.contains(x,y)) host.eye();
            else if(memory.contains(x,y)) host.memory();
            else if(explore.contains(x,y)) explore();
            else if(hive.contains(x,y)) host.hive();
            else if(brood.contains(x,y)) {
                broodOpen=true;
                invalidate();
            }

            return true;
        }

        return true;
    }

    private void explore() {
        final EditText e=new EditText(getContext());
        e.setSingleLine(true);
        e.setHint("Where does the swarm seek?");

        new AlertDialog.Builder(getContext())
            .setTitle("EXPLORE")
            .setView(e)
            .setPositiveButton("SEEK",
                (d,w)->host.explore(e.getText().toString()))
            .setNegativeButton("CLOSE",null)
            .show();
    }

    private void launchApp(float x,float y) {
        float d=getResources().getDisplayMetrics().density;
        int cols=getWidth()/d>600?6:4;
        float cellW=getWidth()/(float)cols;
        float cellH=105*d;

        int col=(int)(x/cellW);
        int row=(int)(y/cellH);
        int index=row*cols+col;

        if(index>=0 && index<apps.size()) {
            App a=apps.get(index);

            try {
                Intent i=new Intent();
                i.setClassName(a.pkg,a.cls);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            } catch(Exception ignored) {}
        }
    }

    private static class App {
        String name,pkg,cls;
        Drawable icon;
    }
}
