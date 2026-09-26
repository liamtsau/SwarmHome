package organic.swarm.home;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class SwarmView extends View {

    public interface Host {
        void eye();
        void memory();
        void explore(String q);
        void hive();
        void settings();
    }

    private static final int VOID   = Color.rgb(16, 10, 19);   // #100A13
    private static final int CHITIN = Color.rgb(36, 19, 41);   // #241329
    private static final int PURPLE = Color.rgb(89, 48, 107);  // #59306B
    private static final int AMBER  = Color.rgb(234, 163, 66); // #EAA342
    private static final int BONE   = Color.rgb(234, 218, 194);// #EADAC2

    private static final int ORGAN_EYE = 1;
    private static final int ORGAN_MEMORY = 2;
    private static final int ORGAN_EXPLORE = 3;
    private static final int ORGAN_HIVE = 4;
    private static final int ORGAN_BROOD = 5;
    private static final int ORGAN_NUCLEUS = 6;

    private final Host host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(8217);
    private final ArrayList<App> apps = new ArrayList<>();
    private final ArrayList<Spore> spores = new ArrayList<>();
    private final SharedPreferences memory;

    private final Cell eye = new Cell(ORGAN_EYE, "EYE", "camera");
    private final Cell mem = new Cell(ORGAN_MEMORY, "MEMORY", "files");
    private final Cell explore = new Cell(ORGAN_EXPLORE, "EXPLORE", "web");
    private final Cell hive = new Cell(ORGAN_HIVE, "HIVE", "people");
    private final Cell brood = new Cell(ORGAN_BROOD, "BROOD", "apps");
    private final Cell nucleus = new Cell(ORGAN_NUCLEUS, "NUCLEUS", "system");
    private final Cell[] organs = { eye, mem, explore, hive, brood };

    private float density;
    private float scaledDensity;
    private float downX, downY;
    private float lastX, lastY;
    private long downAt;
    private boolean moved;
    private boolean broodOpen;
    private float broodOffset;
    private float broodVelocity;
    private float broodDownOffset;
    private int pressedOrgan;
    private int pressedApp = -1;
    private float touchX = -9999f, touchY = -9999f;
    private float shockX, shockY, shockLife;
    private final float touchSlop;
    private final boolean motionEnabled;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public SwarmView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        motionEnabled = ValueAnimator.areAnimatorsEnabled();
        memory = context.getSharedPreferences("swarm_memory", Context.MODE_PRIVATE);

        setBackgroundColor(VOID);
        setFocusable(true);
        setClickable(true);
        refreshApps();
    }

    public void refreshApps() {
        PackageManager pm = getContext().getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);

        apps.clear();
        for (ResolveInfo r : pm.queryIntentActivities(query, 0)) {
            if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
            if (r.activityInfo.packageName.equals(getContext().getPackageName())) continue;

            App app = new App();
            app.name = String.valueOf(r.loadLabel(pm));
            app.pkg = r.activityInfo.packageName;
            app.cls = r.activityInfo.name;
            app.icon = r.loadIcon(pm);
            app.score = rememberedScore(app.pkg);
            apps.add(app);
        }

        Collections.sort(apps, new Comparator<App>() {
            @Override public int compare(App a, App b) {
                int score = Float.compare(b.score, a.score);
                if (score != 0) return score;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        clampBrood();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        long now = SystemClock.uptimeMillis();
        float t = now / 1000f;

        p.setStyle(Paint.Style.FILL);
        p.setColor(VOID);
        c.drawRect(0, 0, getWidth(), getHeight(), p);

        if (broodOpen) drawBrood(c, t);
        else drawHome(c, t);

        drawSpores(c);
        drawShock(c);

        stepPhysics();
        if (motionEnabled && getWindowVisibility() == VISIBLE) postInvalidateDelayed(33);
    }

    private void drawHome(Canvas c, float t) {
        float w = getWidth(), h = getHeight();
        float cx = w * .5f, cy = h * .42f;
        float breath = 1f + .035f * (float)Math.sin(t * 1.55f);

        place(nucleus, cx, cy, 86 * density * breath, 72 * density * breath);

        // Asymmetry is deliberate, but anchor points remain stable for muscle memory.
        place(eye,     w * .25f, h * .22f, 58*density, 46*density);
        place(mem,     w * .74f, h * .25f, 62*density, 48*density);
        place(explore, w * .20f, h * .65f, 66*density, 50*density);
        place(hive,    w * .77f, h * .62f, 62*density, 48*density);
        place(brood,   w * .50f, h * .79f, 70*density, 50*density);

        drawDeepTissue(c, cx, cy, t);
        for (Cell organ : organs) drawVessel(c, nucleus, organ, t);

        drawNucleus(c, nucleus, t);
        for (Cell organ : organs) drawOrgan(c, organ, t);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(BONE);
        text.setTextSize(11 * scaledDensity);
        text.setAlpha(170);
        c.drawText("swipe upward to enter brood", w*.5f, h*.93f, text);
        text.setAlpha(255);
    }

    private void drawDeepTissue(Canvas c, float cx, float cy, float t) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);

        for (int i=0; i<18; i++) {
            float a = (float)(i * Math.PI * 2 / 18.0);
            float wobble = (float)Math.sin(t*.45f + i*1.71f) * 18*density;
            float ex = cx + (float)Math.cos(a) * (getWidth()*.62f);
            float ey = cy + (float)Math.sin(a) * (getHeight()*.44f);

            Path vein = new Path();
            vein.moveTo(cx, cy);
            vein.cubicTo(
                    cx + (float)Math.cos(a+.55f)*100*density,
                    cy + (float)Math.sin(a+.55f)*100*density,
                    (cx+ex)*.5f + wobble,
                    (cy+ey)*.5f - wobble*.45f,
                    ex, ey);

            p.setStrokeWidth((i%3==0 ? 4f : 2f)*density);
            p.setColor(i%4==0 ? Color.argb(70,234,163,66) : Color.argb(65,89,48,107));
            c.drawPath(vein,p);
        }
    }

    private void drawVessel(Canvas c, Cell from, Cell to, float t) {
        float dx = to.cx-from.cx, dy = to.cy-from.cy;
        float len = (float)Math.sqrt(dx*dx+dy*dy);
        if (len < 1f) return;
        float nx = -dy/len, ny = dx/len;
        float bend = (18 + to.id*3) * density * ((to.id&1)==0 ? 1 : -1);

        Path path = new Path();
        path.moveTo(from.cx, from.cy);
        path.cubicTo(
                from.cx + dx*.30f + nx*bend,
                from.cy + dy*.30f + ny*bend,
                from.cx + dx*.70f - nx*bend*.5f,
                from.cy + dy*.70f - ny*bend*.5f,
                to.cx, to.cy);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(10*density);
        p.setColor(Color.argb(110,36,19,41));
        c.drawPath(path,p);

        p.setStrokeWidth(2.2f*density);
        p.setColor(Color.argb(150,89,48,107));
        c.drawPath(path,p);

        PathMeasure pm = new PathMeasure(path,false);
        float[] pos = new float[2];
        float flow = ((t*.10f + to.id*.137f)%1f) * pm.getLength();
        if (pm.getPosTan(flow,pos,null)) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(220,234,163,66));
            c.drawCircle(pos[0],pos[1],3.2f*density,p);
        }
    }

    private void drawNucleus(Canvas c, Cell cell, float t) {
        float press = cell.id==pressedOrgan ? .14f : 0f;
        Path outer = blob(cell.cx,cell.cy,cell.rx*(1f-press),cell.ry*(1f+press*.35f),14,t*.13f,cell.id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(CHITIN);
        c.drawPath(outer,p);

        Path sac = blob(cell.cx,cell.cy,cell.rx*.72f,cell.ry*.74f,13,-t*.10f,cell.id+4);
        p.setColor(Color.rgb(82,43,61));
        c.drawPath(sac,p);

        float pulse = .5f + .5f*(float)Math.sin(t*1.65f);
        Path core = blob(cell.cx,cell.cy,cell.rx*(.48f+.025f*pulse),cell.ry*(.52f+.03f*pulse),12,t*.18f,cell.id+9);
        p.setColor(mix(Color.rgb(108,56,45),AMBER,.42f+.26f*pulse));
        c.drawPath(core,p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((2.5f+1.5f*pulse)*density);
        p.setColor(Color.argb(210,234,163,66));
        c.drawPath(core,p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(BONE);
        text.setTextSize(13*scaledDensity);
        text.setAlpha(205);
        c.drawText(clock.format(new Date()),cell.cx,cell.cy-11*density,text);

        text.setTextSize(22*scaledDensity);
        text.setAlpha(255);
        c.drawText(batteryPercent()+"%",cell.cx,cell.cy+18*density,text);

        text.setTextSize(8.5f*scaledDensity);
        text.setLetterSpacing(.18f);
        text.setAlpha(170);
        c.drawText("NUCLEUS",cell.cx,cell.cy+39*density,text);
        text.setLetterSpacing(0);
        text.setAlpha(255);
    }

    private void drawOrgan(Canvas c, Cell cell, float t) {
        boolean pressed = cell.id==pressedOrgan;
        float squash = pressed ? .10f : 0f;
        float idle = .018f*(float)Math.sin(t*(1.15f+cell.id*.07f)+cell.id);

        float rx = cell.rx*(1f+idle-squash);
        float ry = cell.ry*(1f-idle+squash*.65f);

        Path membrane = blob(cell.cx,cell.cy,rx,ry,10,t*.10f+cell.id*.21f,cell.id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(pressed ? mix(CHITIN,PURPLE,.55f) : CHITIN);
        c.drawPath(membrane,p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((pressed?4.2f:2.4f)*density);
        p.setColor(pressed ? AMBER : PURPLE);
        c.drawPath(membrane,p);

        // Internal amber organ sac communicates "alive/ready".
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(pressed?210:145,234,163,66));
        c.drawCircle(cell.cx,cell.cy-cell.ry*.14f,(pressed?8:6)*density,p);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(BONE);
        text.setTextSize(12*scaledDensity);
        text.setAlpha(245);
        c.drawText(cell.label,cell.cx,cell.cy+4*density,text);
        text.setTextSize(8.5f*scaledDensity);
        text.setAlpha(135);
        c.drawText(cell.sub,cell.cx,cell.cy+20*density,text);
        text.setAlpha(255);
    }

    private void drawBrood(Canvas c, float t) {
        float w=getWidth(), h=getHeight();
        float top=70*density;
        float rowH=104*density;
        int columns = w/density >= 600 ? 5 : 3;
        float colW=w/columns;

        text.setColor(BONE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(10*scaledDensity);
        text.setLetterSpacing(.18f);
        text.setAlpha(180);
        c.drawText("BROOD",w*.5f,33*density,text);
        text.setLetterSpacing(0);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f*density);
        p.setColor(Color.argb(80,89,48,107));
        c.drawLine(w*.16f,48*density,w*.84f,48*density,p);

        for(int i=0;i<apps.size();i++) {
            int row=i/columns, col=i%columns;
            float drift = (float)Math.sin(i*1.73f)*7*density;
            float x=colW*(col+.5f)+drift;
            float y=top+row*rowH-broodOffset + (col%2)*12*density;
            if(y < -80*density || y > h+80*density) continue;

            App a=apps.get(i);
            float scoreGlow=Math.min(1f,a.score/7f);
            boolean pressed=i==pressedApp;
            float radius=(38+4*scoreGlow)*density;
            Path cell=blob(x,y,radius*(pressed?.90f:1f),radius*.78f*(pressed?1.10f:1f),9,t*.08f+i*.17f,i+20);

            p.setStyle(Paint.Style.FILL);
            p.setColor(pressed ? mix(CHITIN,PURPLE,.58f) : CHITIN);
            c.drawPath(cell,p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth((1.4f+2.0f*scoreGlow)*density);
            p.setColor(mix(PURPLE,AMBER,.15f+.55f*scoreGlow));
            c.drawPath(cell,p);

            Drawable icon=a.icon;
            if(icon!=null) {
                int s=(int)(36*density);
                icon.setBounds((int)x-s/2,(int)(y-11*density)-s/2,(int)x+s/2,(int)(y-11*density)+s/2);
                icon.draw(c);
            }

            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(BONE);
            text.setTextSize(9.5f*scaledDensity);
            text.setAlpha(225);
            c.drawText(shortName(a.name),x,y+31*density,text);
        }

        text.setAlpha(130);
        text.setTextSize(9*scaledDensity);
        c.drawText("drag tissue • tap to consume • hold for app anatomy",w*.5f,h-22*density,text);
        text.setAlpha(255);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x=e.getX(), y=e.getY();

        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=lastX=x; downY=lastY=y;
                touchX=x; touchY=y;
                downAt=SystemClock.uptimeMillis();
                moved=false;
                broodVelocity=0;
                broodDownOffset=broodOffset;

                if (broodOpen) pressedApp=findAppAt(x,y);
                else pressedOrgan=findOrganAt(x,y);

                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                touchX=x; touchY=y;
                float dx=x-downX, dy=y-downY;
                if (dx*dx+dy*dy > touchSlop*touchSlop) moved=true;

                if (broodOpen) {
                    float frameDy=y-lastY;
                    broodVelocity=-frameDy;
                    broodOffset=broodDownOffset+(downY-y);
                    clampBrood();
                    if(moved) pressedApp=-1;
                } else if(moved) {
                    pressedOrgan=0;
                }

                lastX=x; lastY=y;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                clearPress();
                return true;

            case MotionEvent.ACTION_UP:
                long held=SystemClock.uptimeMillis()-downAt;
                float totalDy=y-downY;

                if (!broodOpen && totalDy < -70*density) {
                    openBrood(x,y);
                    clearPress();
                    return true;
                }

                if (broodOpen && totalDy > 90*density && broodOffset < 12*density) {
                    broodOpen=false;
                    erupt(x,y,14);
                    clearPress();
                    invalidate();
                    return true;
                }

                if (broodOpen) {
                    if(!moved && pressedApp>=0) {
                        if(held>=520) openAppAnatomy(apps.get(pressedApp));
                        else consumeApp(pressedApp,x,y);
                    } else if(moved) {
                        broodOffset += broodVelocity*3.2f;
                        clampBrood();
                    }
                    clearPress();
                    return true;
                }

                if(!moved && pressedOrgan!=0) {
                    int target=pressedOrgan;
                    clearPress();
                    erupt(x,y,target==ORGAN_NUCLEUS?16:10);

                    if(target==ORGAN_NUCLEUS) {
                        if(held>=550) host.settings();
                        else openBrood(x,y);
                    } else if(target==ORGAN_EYE) {
                        host.eye();
                    } else if(target==ORGAN_MEMORY) {
                        host.memory();
                    } else if(target==ORGAN_EXPLORE) {
                        organicExplore();
                    } else if(target==ORGAN_HIVE) {
                        host.hive();
                    } else if(target==ORGAN_BROOD) {
                        openBrood(x,y);
                    }
                    return true;
                }

                clearPress();
                return true;
        }
        return true;
    }

    private void openBrood(float x,float y) {
        broodOpen=true;
        erupt(x,y,18);
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        invalidate();
    }

    private void organicExplore() {
        final EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setHint("url or thought");
        input.setTextColor(BONE);
        input.setHintTextColor(Color.argb(120,234,218,194));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding((int)(18*density),(int)(14*density),(int)(18*density),(int)(14*density));

        AlertDialog dialog=new AlertDialog.Builder(getContext())
                .setTitle("DEVOUR THE WEB")
                .setMessage("Feed the nervous system a place or question.")
                .setView(input)
                .setPositiveButton("CONSUME",(d,w)->host.explore(input.getText().toString()))
                .setNegativeButton("RETRACT",null)
                .create();

        dialog.setOnShowListener(v -> {
            if(dialog.getWindow()!=null) {
                dialog.getWindow().getDecorView().setBackgroundColor(CHITIN);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(AMBER);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(BONE);
        });
        dialog.show();
    }

    private int findOrganAt(float x,float y) {
        if(hit(nucleus,x,y)) return ORGAN_NUCLEUS;
        for(Cell cell:organs) if(hit(cell,x,y)) return cell.id;
        return 0;
    }

    private int findAppAt(float x,float y) {
        float w=getWidth();
        int columns = w/density >= 600 ? 5 : 3;
        float colW=w/columns;
        float top=70*density;
        float rowH=104*density;

        for(int i=0;i<apps.size();i++) {
            int row=i/columns,col=i%columns;
            float cx=colW*(col+.5f)+(float)Math.sin(i*1.73f)*7*density;
            float cy=top+row*rowH-broodOffset+(col%2)*12*density;
            float rx=48*density, ry=44*density;
            float dx=(x-cx)/rx,dy=(y-cy)/ry;
            if(dx*dx+dy*dy<=1f) return i;
        }
        return -1;
    }

    private void consumeApp(int index,float x,float y) {
        if(index<0 || index>=apps.size()) return;
        App a=apps.get(index);
        remember(a.pkg);
        erupt(x,y,20);
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);

        postDelayed(() -> {
            try {
                Intent intent=new Intent();
                intent.setClassName(a.pkg,a.cls);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch(Exception ignored) {}
        },55);
    }

    private void openAppAnatomy(App a) {
        erupt(touchX,touchY,9);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        try {
            Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:"+a.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch(Exception ignored) {}
    }

    private void remember(String pkg) {
        float old=memory.getFloat("score:"+pkg,0f);
        long now=System.currentTimeMillis();
        float next=Math.min(30f,old*.93f+1.35f);
        memory.edit()
                .putFloat("score:"+pkg,next)
                .putLong("last:"+pkg,now)
                .apply();
    }

    private float rememberedScore(String pkg) {
        float score=memory.getFloat("score:"+pkg,0f);
        long last=memory.getLong("last:"+pkg,0L);
        if(last==0L) return score;
        float days=(System.currentTimeMillis()-last)/86400000f;
        float recency=(float)Math.exp(-days/12f);
        return score + recency*.65f;
    }

    private void stepPhysics() {
        if(broodOpen && Math.abs(broodVelocity)>.15f && !isPressed()) {
            broodOffset+=broodVelocity;
            broodVelocity*=.90f;
            clampBrood();
        } else {
            broodVelocity*=.7f;
        }

        for(int i=spores.size()-1;i>=0;i--) {
            Spore s=spores.get(i);
            s.x+=s.vx;
            s.y+=s.vy;
            s.vx*=.965f;
            s.vy=s.vy*.965f+.035f*density;
            s.life-=.028f;
            if(s.life<=0) spores.remove(i);
        }
        shockLife=Math.max(0,shockLife-.045f);
    }

    private boolean isPressed() {
        return pressedOrgan!=0 || pressedApp>=0;
    }

    private void clampBrood() {
        int columns=getWidth()/density>=600?5:3;
        int rows=(apps.size()+columns-1)/columns;
        float max=Math.max(0,70*density+rows*104*density-getHeight()+70*density);
        if(broodOffset<0) {
            broodOffset=0;
            broodVelocity=0;
        }
        if(broodOffset>max) {
            broodOffset=max;
            broodVelocity=0;
        }
    }

    private void erupt(float x,float y,int count) {
        shockX=x; shockY=y; shockLife=1f;
        for(int i=0;i<count;i++) {
            double a=random.nextDouble()*Math.PI*2;
            float speed=(1.4f+random.nextFloat()*5.8f)*density;
            Spore s=new Spore();
            s.x=x; s.y=y;
            s.vx=(float)Math.cos(a)*speed;
            s.vy=(float)Math.sin(a)*speed;
            s.life=.55f+random.nextFloat()*.45f;
            s.r=(1.2f+random.nextFloat()*2.8f)*density;
            spores.add(s);
        }
        if(spores.size()>90) spores.subList(0,spores.size()-90).clear();
    }

    private void drawSpores(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for(Spore s:spores) {
            int alpha=(int)(185*Math.max(0,s.life));
            p.setColor(Color.argb(alpha,234,163,66));
            c.drawCircle(s.x,s.y,s.r*(.4f+.6f*s.life),p);
        }
    }

    private void drawShock(Canvas c) {
        if(shockLife<=0) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2*density*shockLife);
        p.setColor(Color.argb((int)(120*shockLife),234,163,66));
        c.drawCircle(shockX,shockY,(1f-shockLife)*54*density,p);
    }

    private Path blob(float cx,float cy,float rx,float ry,int points,float phase,int seed) {
        float[] xs=new float[points];
        float[] ys=new float[points];

        for(int i=0;i<points;i++) {
            float a=(float)(Math.PI*2*i/points);
            float organic=1f
                    +.055f*(float)Math.sin(a*3f+phase*3.1f+seed*.77f)
                    +.028f*(float)Math.sin(a*5f-phase*2.2f+seed*1.31f);
            xs[i]=cx+(float)Math.cos(a)*rx*organic;
            ys[i]=cy+(float)Math.sin(a)*ry*organic;
        }

        Path path=new Path();
        float sx=(xs[points-1]+xs[0])*.5f;
        float sy=(ys[points-1]+ys[0])*.5f;
        path.moveTo(sx,sy);

        for(int i=0;i<points;i++) {
            int n=(i+1)%points;
            float mx=(xs[i]+xs[n])*.5f;
            float my=(ys[i]+ys[n])*.5f;
            path.quadTo(xs[i],ys[i],mx,my);
        }
        path.close();
        return path;
    }

    private void place(Cell c,float x,float y,float rx,float ry) {
        c.cx=x; c.cy=y; c.rx=rx; c.ry=ry;
    }

    private boolean hit(Cell c,float x,float y) {
        if(c.rx<=0 || c.ry<=0) return false;
        float dx=(x-c.cx)/(c.rx*1.15f);
        float dy=(y-c.cy)/(c.ry*1.15f);
        return dx*dx+dy*dy<=1f;
    }

    private int batteryPercent() {
        BatteryManager bm=(BatteryManager)getContext().getSystemService(Context.BATTERY_SERVICE);
        if(bm==null) return 0;
        int n=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return Math.max(0,Math.min(100,n));
    }

    private int mix(int a,int b,float t) {
        t=Math.max(0,Math.min(1,t));
        int ar=Color.red(a),ag=Color.green(a),ab=Color.blue(a);
        int br=Color.red(b),bg=Color.green(b),bb=Color.blue(b);
        return Color.rgb(
                (int)(ar+(br-ar)*t),
                (int)(ag+(bg-ag)*t),
                (int)(ab+(bb-ab)*t));
    }

    private String shortName(String s) {
        if(s==null) return "";
        return s.length()>12 ? s.substring(0,11)+"…" : s;
    }

    private void clearPress() {
        pressedOrgan=0;
        pressedApp=-1;
        touchX=touchY=-9999f;
        invalidate();
    }

    private static class Cell {
        final int id;
        final String label;
        final String sub;
        float cx,cy,rx,ry;

        Cell(int id,String label,String sub) {
            this.id=id;
            this.label=label;
            this.sub=sub;
        }
    }

    private static class App {
        String name,pkg,cls;
        Drawable icon;
        float score;
    }

    private static class Spore {
        float x,y,vx,vy,life,r;
    }
}
