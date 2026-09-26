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
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;

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
    private float flashLife;
    private float tissueEnergy;
    private float nerveLife;
    private int consumingApp = -1;
    private long consumeAt;
    private long lastSoundAt;
    private boolean exploreOpen;
    private final SpannableStringBuilder searchText = new SpannableStringBuilder();
    private final RectF exploreMouth = new RectF();
    private final float touchSlop;
    private final boolean motionEnabled;

    private static final int SOUND_CLICK = 0;
    private static final int SOUND_CONSUME = 1;
    private static final int SOUND_OPEN = 2;
    private static final int SOUND_REJECT = 3;

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

        drawFleshTexture(c, t);
        drawCarapace(c, t);
        drawTouchNerve(c, t);

        if (broodOpen) drawBrood(c, t);
        else drawHome(c, t);

        if (exploreOpen) drawExploreMouth(c, t);
        drawSpores(c);
        drawShock(c);
        drawFlash(c);

        stepPhysics();
        if (motionEnabled && getWindowVisibility() == VISIBLE) postInvalidateDelayed(16);
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
        float length = pm.getLength();
        for (int packet=0; packet<3; packet++) {
            float phase = (packet/3f + t*(.075f+.008f*to.id) + to.id*.137f) % 1f;
            if (pm.getPosTan(phase*length,pos,null)) {
                float swell=.72f+.28f*(float)Math.sin(t*3.2f+packet+to.id);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(150+packet*28,234,163,66));
                c.drawCircle(pos[0],pos[1],(2.3f+packet*.65f)*density*swell,p);
            }
        }
    }

    private void drawNucleus(Canvas c, Cell cell, float t) {
        float local = reaction(cell.cx,cell.cy,170*density);
        float press = (cell.id==pressedOrgan ? .14f : 0f) + local*.08f;
        float dx = local*(touchX-cell.cx)*.035f;
        float dy = local*(touchY-cell.cy)*.035f;
        Path outer = blob(cell.cx+dx,cell.cy+dy,cell.rx*(1f-press),cell.ry*(1f+press*.35f),14,t*.13f,cell.id);

        p.setStyle(Paint.Style.FILL);
        p.setColor(CHITIN);
        c.drawPath(outer,p);

        Path sac = blob(cell.cx+dx*.55f,cell.cy+dy*.55f,cell.rx*.72f,cell.ry*.74f,13,-t*.10f,cell.id+4);
        p.setShader(new RadialGradient(cell.cx-dx*.2f,cell.cy-dy*.2f,cell.rx*.78f,
                new int[]{Color.rgb(120,55,67),Color.rgb(65,29,57),CHITIN},
                new float[]{0f,.52f,1f},Shader.TileMode.CLAMP));
        c.drawPath(sac,p);
        p.setShader(null);

        float pulse = .5f + .5f*(float)Math.sin(t*1.65f);
        Path core = blob(cell.cx+dx*.3f,cell.cy+dy*.3f,cell.rx*(.48f+.025f*pulse),cell.ry*(.52f+.03f*pulse),12,t*.18f,cell.id+9);
        p.setShader(new RadialGradient(cell.cx-cell.rx*.12f,cell.cy-cell.ry*.16f,cell.rx*.58f,
                new int[]{Color.rgb(255,225,139),AMBER,Color.rgb(114,52,30)},
                new float[]{0f,.42f,1f},Shader.TileMode.CLAMP));
        c.drawPath(core,p);
        p.setShader(null);

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
        float local = reaction(cell.cx,cell.cy,150*density);
        float squash = (pressed ? .10f : 0f) + local*.065f;
        float idle = .018f*(float)Math.sin(t*(1.15f+cell.id*.07f)+cell.id);
        float shiftX = local*(touchX-cell.cx)*.045f;
        float shiftY = local*(touchY-cell.cy)*.045f;

        float rx = cell.rx*(1f+idle-squash);
        float ry = cell.ry*(1f-idle+squash*.65f);

        Path membrane = blob(cell.cx+shiftX,cell.cy+shiftY,rx,ry,11,t*.10f+cell.id*.21f,cell.id);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cell.cx-rx*.22f,cell.cy-ry*.28f,Math.max(rx,ry)*1.1f,
                new int[]{pressed?mix(PURPLE,AMBER,.35f):mix(CHITIN,PURPLE,.35f),CHITIN,VOID},
                new float[]{0f,.65f,1f},Shader.TileMode.CLAMP));
        c.drawPath(membrane,p);
        p.setShader(null);

        drawMembraneRibs(c,cell.cx+shiftX,cell.cy+shiftY,rx,ry,cell.id,t,pressed);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((pressed?4.2f:2.4f)*density);
        p.setColor(pressed ? AMBER : PURPLE);
        c.drawPath(membrane,p);

        drawGlyph(c,cell,pressed,t,shiftX,shiftY);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(BONE);
        text.setTextSize(10.5f*scaledDensity);
        text.setAlpha(242);
        c.drawText(cell.label,cell.cx+shiftX,cell.cy+cell.ry*.63f+shiftY,text);
        text.setAlpha(255);
    }

    private void drawFleshTexture(Canvas c, float t) {
        float w=getWidth(), h=getHeight();
        p.setStyle(Paint.Style.FILL);
        for(int i=0;i<34;i++) {
            float hx=hash(i*17+3), hy=hash(i*31+7);
            float x=hx*w;
            float y=hy*h;
            float rr=(7f+hash(i*13+5)*24f)*density;
            float breathe=1f+.08f*(float)Math.sin(t*.42f+i*.83f);
            int alpha=10+(int)(18*hash(i*19+11));
            p.setColor((i%5==0)?Color.argb(alpha,234,163,66):Color.argb(alpha,89,48,107));
            c.drawOval(x-rr*breathe,y-rr*.55f,x+rr*breathe,y+rr*.55f,p);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(.7f*density);
        for(int i=0;i<14;i++) {
            float y=(i+.5f)*h/14f;
            Path fiber=new Path();
            fiber.moveTo(0,y);
            fiber.cubicTo(w*.25f,y+(float)Math.sin(t*.25f+i)*10*density,
                    w*.70f,y-(float)Math.cos(t*.21f+i*.7f)*13*density,w,y);
            p.setColor(Color.argb(18,234,218,194));
            c.drawPath(fiber,p);
        }

        // Capillary mesh and pores: stable texture, respiratory motion.
        p.setStrokeWidth(.55f*density);
        for(int i=0;i<22;i++) {
            float x=hash(i*47+5)*w;
            float y=hash(i*67+9)*h;
            float len=(24+hash(i*29+2)*60)*density;
            float a=hash(i*37+4)*(float)Math.PI*2f;
            Path cap=new Path();
            cap.moveTo(x,y);
            cap.quadTo(x+(float)Math.cos(a+.8f)*len*.55f,
                    y+(float)Math.sin(a+.8f)*len*.55f,
                    x+(float)Math.cos(a)*len,
                    y+(float)Math.sin(a)*len);
            p.setColor(i%4==0?Color.argb(25,234,163,66):Color.argb(24,126,69,122));
            c.drawPath(cap,p);
        }

        p.setStyle(Paint.Style.FILL);
        for(int i=0;i<46;i++) {
            float x=hash(i*73+19)*w;
            float y=hash(i*41+23)*h;
            float open=.55f+.45f*(float)Math.sin(t*.55f+i*.91f);
            float r=(.7f+hash(i*11+8)*1.7f)*density*(.72f+.28f*open);
            p.setColor(Color.argb(18+(int)(18*open),10,4,13));
            c.drawCircle(x,y,r,p);
        }
    }

    private void drawCarapace(Canvas c,float t) {
        float w=getWidth(), h=getHeight();
        float breathe=.5f+.5f*(float)Math.sin(t*.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);

        for(int side=0;side<2;side++) {
            float edge=side==0?14*density:w-14*density;
            float inward=side==0?1f:-1f;
            Path spine=new Path();
            spine.moveTo(edge,0);
            spine.cubicTo(edge+inward*42*density,h*.25f,
                    edge+inward*22*density,h*.70f,edge,h);
            p.setStrokeWidth(16*density);
            p.setColor(Color.argb(190,22,11,26));
            c.drawPath(spine,p);
            p.setStrokeWidth(3.2f*density);
            p.setColor(Color.argb(205,102,59,116));
            c.drawPath(spine,p);

            for(int i=0;i<10;i++) {
                float y=(i+.55f)*h/10f;
                float len=(22+10*(float)Math.sin(i*1.7f))*density;
                float flex=(float)Math.sin(t*.7f+i)*2.5f*density*breathe;
                Path rib=new Path();
                rib.moveTo(edge,y);
                rib.quadTo(edge+inward*(len*.55f),y-8*density,
                        edge+inward*(len+flex),y-18*density);
                p.setStrokeWidth(5*density);
                p.setColor(Color.argb(190,55,30,61));
                c.drawPath(rib,p);
                p.setStrokeWidth(1.2f*density);
                p.setColor(Color.argb(165,234,218,194));
                c.drawPath(rib,p);
            }
        }

        p.setStyle(Paint.Style.FILL);
        for(int i=0;i<5;i++) {
            float x=w*(i+.5f)/5f;
            float y=10*density+(float)Math.sin(i*1.4f+t*.35f)*2*density;
            Path tooth=new Path();
            tooth.moveTo(x-11*density,y);
            tooth.lineTo(x,y+20*density);
            tooth.lineTo(x+11*density,y);
            tooth.close();
            p.setColor(Color.argb(130,72,39,79));
            c.drawPath(tooth,p);
        }
    }

    private void drawMembraneRibs(Canvas c,float cx,float cy,float rx,float ry,int seed,float t,boolean pressed) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for(int i=0;i<5;i++) {
            float y=cy-ry*.52f+i*ry*.26f;
            float bow=(float)Math.sin(t*.55f+seed+i)*4*density;
            Path rib=new Path();
            rib.moveTo(cx-rx*.68f,y);
            rib.quadTo(cx+bow,y+ry*.16f,cx+rx*.68f,y);
            p.setStrokeWidth((i==2?2f:1.1f)*density);
            p.setColor(pressed?Color.argb(130,234,163,66):Color.argb(105,119,72,133));
            c.drawPath(rib,p);
        }
    }

    private void drawGlyph(Canvas c,Cell cell,boolean pressed,float t,float sx,float sy) {
        float cx=cell.cx+sx, cy=cell.cy+sy-cell.ry*.10f;
        float u=12*density;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((pressed?2.8f:2f)*density);
        p.setColor(pressed?AMBER:Color.argb(215,234,163,66));

        if(cell.id==ORGAN_EYE) {
            RectF eyeR=new RectF(cx-1.45f*u,cy-.72f*u,cx+1.45f*u,cy+.72f*u);
            c.drawOval(eyeR,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(AMBER);
            c.drawOval(cx-.18f*u,cy-.54f*u,cx+.18f*u,cy+.54f*u,p);
        } else if(cell.id==ORGAN_MEMORY) {
            for(int i=0;i<3;i++) {
                float yy=cy+(i-1)*.58f*u;
                Path plate=new Path();
                plate.moveTo(cx-1.1f*u,yy-.25f*u);
                plate.quadTo(cx,yy-.65f*u,cx+1.1f*u,yy-.25f*u);
                plate.quadTo(cx,yy+.45f*u,cx-1.1f*u,yy-.25f*u);
                c.drawPath(plate,p);
            }
        } else if(cell.id==ORGAN_EXPLORE) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(AMBER);
            c.drawCircle(cx,cy,.55f*u,p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.argb(210,234,163,66));
            for(int i=0;i<5;i++) {
                float a=(float)(i*Math.PI*2/5+t*.08f);
                Path tendril=new Path();
                tendril.moveTo(cx+(float)Math.cos(a)*.6f*u,cy+(float)Math.sin(a)*.6f*u);
                tendril.quadTo(cx+(float)Math.cos(a+.25f)*1.1f*u,cy+(float)Math.sin(a+.25f)*1.1f*u,
                        cx+(float)Math.cos(a)*1.55f*u,cy+(float)Math.sin(a)*1.55f*u);
                c.drawPath(tendril,p);
            }
        } else if(cell.id==ORGAN_HIVE) {
            for(int i=0;i<6;i++) {
                float a=(float)(i*Math.PI*2/6+t*.03f);
                float x=cx+(float)Math.cos(a)*.9f*u;
                float y=cy+(float)Math.sin(a)*.7f*u;
                p.setStyle(Paint.Style.FILL);
                p.setColor(i==0?AMBER:PURPLE);
                c.drawCircle(x,y,.30f*u,p);
            }
            p.setColor(AMBER);
            c.drawCircle(cx,cy,.42f*u,p);
        } else if(cell.id==ORGAN_BROOD) {
            Path egg=blob(cx,cy,1.0f*u,1.25f*u,9,t*.08f,51);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(AMBER);
            c.drawPath(egg,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(170,234,163,66));
            c.drawCircle(cx,cy-.15f*u,.34f*u,p);
        }
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
            float consume=0f;
            if(i==consumingApp) {
                consume=Math.min(1f,(SystemClock.uptimeMillis()-consumeAt)/235f);
                float pull=consume*consume;
                float targetX=w*.5f, targetY=h*.43f;
                x=x+(targetX-x)*pull;
                y=y+(targetY-y)*pull;
                radius*=Math.max(.08f,1f-consume*.92f);
            }
            Path cell=blob(x,y,radius*(pressed?.90f:1f),radius*.78f*(pressed?1.10f:1f),9,t*.08f+i*.17f,i+20);

            p.setStyle(Paint.Style.FILL);
            p.setAlpha((int)(255*(1f-consume*.72f)));
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
            if(consume<.65f) c.drawText(shortName(a.name),x,y+31*density,text);
            p.setAlpha(255);
            text.setAlpha(255);
        }

        text.setAlpha(130);
        text.setTextSize(9*scaledDensity);
        c.drawText("drag tissue • tap to consume • hold for app anatomy",w*.5f,h-22*density,text);
        text.setAlpha(255);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if(exploreOpen) return handleExploreTouch(e);
        float x=e.getX(), y=e.getY();

        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=lastX=x; downY=lastY=y;
                touchX=x; touchY=y;
                tissueEnergy=1f;
                nerveLife=1f;
                shockX=x; shockY=y;
                flashLife=.18f;
                downAt=SystemClock.uptimeMillis();
                moved=false;
                broodVelocity=0;
                broodDownOffset=broodOffset;

                if (broodOpen) pressedApp=findAppAt(x,y);
                else pressedOrgan=findOrganAt(x,y);

                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                playBioSound(SOUND_CLICK);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                touchX=x; touchY=y;
                tissueEnergy=Math.max(tissueEnergy,.72f);
                nerveLife=Math.max(nerveLife,.55f);
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
                    playBioSound(SOUND_CLICK);
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
        playBioSound(SOUND_OPEN);
        tissueEnergy=1f;
        flashLife=.65f;
        erupt(x,y,18);
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        invalidate();
    }

    private void organicExplore() {
        exploreOpen=true;
        broodOpen=false;
        searchText.clear();
        tissueEnergy=1f;
        flashLife=1f;
        playBioSound(SOUND_OPEN);
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        requestFocus();
        post(() -> {
            InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);
        });
        invalidate();
    }

    private void drawExploreMouth(Canvas c,float t) {
        float w=getWidth(),h=getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(175,8,4,10));
        c.drawRect(0,0,w,h,p);

        float cx=w*.5f,cy=h*.50f;
        float rx=Math.min(w*.42f,190*density);
        float ry=58*density;
        float pulse=.5f+.5f*(float)Math.sin(t*2.1f);
        exploreMouth.set(cx-rx,cy-ry,cx+rx,cy+ry);

        for(int i=0;i<8;i++) {
            float a=(float)(i*Math.PI*2/8.0);
            float ex=cx+(float)Math.cos(a)*w*.52f;
            float ey=cy+(float)Math.sin(a)*h*.38f;
            Path nerve=new Path();
            nerve.moveTo(ex,ey);
            nerve.quadTo((ex+cx)*.5f+(float)Math.sin(t*.7f+i)*8*density,
                    (ey+cy)*.5f,cx,cy);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth((i%2==0?2.2f:1.2f)*density);
            p.setColor(Color.argb(75,89,48,107));
            c.drawPath(nerve,p);
        }

        Path outer=blob(cx,cy,rx,ry,14,t*.11f,91);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx-rx*.28f,cy-ry*.20f,rx*1.15f,
                new int[]{Color.rgb(92,44,82),CHITIN,VOID},new float[]{0f,.62f,1f},Shader.TileMode.CLAMP));
        c.drawPath(outer,p);
        p.setShader(null);

        Path lumen=blob(cx,cy,rx*.84f,ry*.56f,13,-t*.12f,92);
        p.setColor(Color.rgb(9,5,11));
        c.drawPath(lumen,p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth((2f+1.5f*pulse)*density);
        p.setColor(Color.argb(180,234,163,66));
        c.drawPath(lumen,p);

        String q=searchText.toString();
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(16*scaledDensity);
        text.setColor(q.length()==0?Color.argb(120,234,218,194):BONE);
        text.setAlpha(255);
        String shown=q.length()==0?"feed a thought or URL":ellipsize(q,28);
        c.drawText(shown,cx-rx*.68f,cy+5*density,text);

        float orbX=cx+rx*.68f;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(orbX-3*density,cy-3*density,22*density,
                new int[]{Color.rgb(255,226,139),AMBER,Color.rgb(104,48,29)},null,Shader.TileMode.CLAMP));
        c.drawCircle(orbX,cy,(14+2*pulse)*density,p);
        p.setShader(null);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(9*scaledDensity);
        text.setColor(BONE);
        text.setAlpha(155);
        c.drawText("tap amber core to consume",cx,cy+ry+28*density,text);
        text.setAlpha(255);
    }

    private boolean handleExploreTouch(MotionEvent e) {
        float x=e.getX(),y=e.getY();
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            touchX=x;touchY=y;tissueEnergy=1f;
            playBioSound(SOUND_CLICK);
            return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_UP) {
            float submitX=exploreMouth.centerX()+exploreMouth.width()*.28f;
            if(exploreMouth.contains(x,y)) {
                if(x>=submitX) submitExplore();
                else {
                    requestFocus();
                    InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if(imm!=null) imm.showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);
                    erupt(x,y,5);
                }
            } else {
                closeExplore();
            }
            return true;
        }
        return true;
    }

    private void submitExplore() {
        String q=searchText.toString().trim();
        if(q.length()==0) {
            playBioSound(SOUND_REJECT);
            performHapticFeedback(HapticFeedbackConstants.REJECT);
            flashLife=.5f;
            invalidate();
            return;
        }
        erupt(exploreMouth.right-34*density,exploreMouth.centerY(),24);
        playBioSound(SOUND_CONSUME);
        closeExploreKeyboardOnly();
        exploreOpen=false;
        host.explore(q);
        invalidate();
    }

    private void closeExplore() {
        playBioSound(SOUND_CLICK);
        closeExploreKeyboardOnly();
        exploreOpen=false;
        searchText.clear();
        invalidate();
    }

    private void closeExploreKeyboardOnly() {
        InputMethodManager imm=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(imm!=null) imm.hideSoftInputFromWindow(getWindowToken(),0);
    }

    @Override public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI;
        outAttrs.imeOptions=EditorInfo.IME_ACTION_GO|EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this,true) {
            @Override public Editable getEditable() { return searchText; }

            @Override public boolean commitText(CharSequence value,int newCursorPosition) {
                boolean ok=super.commitText(value,newCursorPosition);
                trimSearch(); invalidate(); return ok;
            }

            @Override public boolean setComposingText(CharSequence value,int newCursorPosition) {
                boolean ok=super.setComposingText(value,newCursorPosition);
                trimSearch(); invalidate(); return ok;
            }

            @Override public boolean deleteSurroundingText(int beforeLength,int afterLength) {
                boolean ok=super.deleteSurroundingText(beforeLength,afterLength);
                invalidate(); return ok;
            }

            @Override public boolean sendKeyEvent(KeyEvent event) {
                if(event.getAction()==KeyEvent.ACTION_DOWN && event.getKeyCode()==KeyEvent.KEYCODE_ENTER) {
                    submitExplore(); return true;
                }
                boolean ok=super.sendKeyEvent(event); invalidate(); return ok;
            }

            @Override public boolean performEditorAction(int actionCode) {
                if(actionCode==EditorInfo.IME_ACTION_GO || actionCode==EditorInfo.IME_ACTION_DONE || actionCode==EditorInfo.IME_ACTION_SEARCH) {
                    submitExplore(); return true;
                }
                return super.performEditorAction(actionCode);
            }
        };
    }

    private void trimSearch() {
        if(searchText.length()>160) searchText.delete(160,searchText.length());
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
        if(index<0 || index>=apps.size() || consumingApp>=0) return;
        App a=apps.get(index);
        remember(a.pkg);
        consumingApp=index;
        consumeAt=SystemClock.uptimeMillis();
        tissueEnergy=1f;
        nerveLife=1f;
        flashLife=.92f;
        erupt(x,y,24);
        playBioSound(SOUND_CONSUME);
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);

        postDelayed(() -> {
            try {
                Intent intent=new Intent();
                intent.setClassName(a.pkg,a.cls);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch(Exception ignored) {
                playBioSound(SOUND_REJECT);
                flashLife=.8f;
            } finally {
                consumingApp=-1;
                invalidate();
            }
        },235);
    }

    private void openAppAnatomy(App a) {
        erupt(touchX,touchY,9);
        playBioSound(SOUND_OPEN);
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
        if(broodOpen && Math.abs(broodVelocity)>.15f && !hasActivePress()) {
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
        flashLife=Math.max(0,flashLife-.055f);
        nerveLife=Math.max(0,nerveLife-.045f);
        tissueEnergy=Math.max(0,tissueEnergy*.90f-.01f);
    }

    private boolean hasActivePress() {
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
        nerveLife=Math.max(nerveLife,.75f);
        tissueEnergy=Math.max(tissueEnergy,.85f);
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

    private void drawTouchNerve(Canvas c,float t) {
        if(nerveLife<=0 || touchX<-1000) return;
        float cx=getWidth()*.5f, cy=getHeight()*.42f;
        float dx=touchX-cx, dy=touchY-cy;
        float len=(float)Math.sqrt(dx*dx+dy*dy);
        if(len<4*density) return;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for(int strand=0;strand<3;strand++) {
            float side=(strand-1)*9*density;
            float nx=-dy/len, ny=dx/len;
            Path nerve=new Path();
            nerve.moveTo(cx,cy);
            float wave=(float)Math.sin(t*8f+strand*2.1f)*10*density*nerveLife;
            nerve.cubicTo(cx+dx*.30f+nx*(side+wave),cy+dy*.30f+ny*(side+wave),
                    cx+dx*.72f-nx*(side*.5f+wave),cy+dy*.72f-ny*(side*.5f+wave),touchX,touchY);
            p.setStrokeWidth((strand==1?2.4f:1.1f)*density*nerveLife);
            p.setColor(strand==1?Color.argb((int)(180*nerveLife),234,163,66):
                    Color.argb((int)(100*nerveLife),126,69,122));
            c.drawPath(nerve,p);
        }
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

    private void drawFlash(Canvas c) {
        if(flashLife<=0) return;
        float radius=(45+150*(1f-flashLife))*density;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(shockX,shockY,radius,
                new int[]{Color.argb((int)(100*flashLife),255,229,166),
                        Color.argb((int)(55*flashLife),234,163,66),Color.TRANSPARENT},
                new float[]{0f,.34f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(shockX,shockY,radius,p);
        p.setShader(null);
    }

    private float reaction(float x,float y,float radius) {
        if(touchX < -1000 || tissueEnergy<=0) return 0;
        float dx=touchX-x,dy=touchY-y;
        float d=(float)Math.sqrt(dx*dx+dy*dy);
        return Math.max(0f,1f-d/radius)*tissueEnergy;
    }

    private float hash(int n) {
        double v=Math.sin(n*12.9898+78.233)*43758.5453;
        return (float)(v-Math.floor(v));
    }

    private String ellipsize(String s,int max) {
        if(s==null) return "";
        return s.length()<=max?s:s.substring(0,Math.max(1,max-1))+"…";
    }

    private void playBioSound(final int kind) {
        long now=SystemClock.uptimeMillis();
        if(kind==SOUND_CLICK && now-lastSoundAt<55) return;
        lastSoundAt=now;
        Thread audio=new Thread(() -> {
            try {
                final int rate=22050;
                final int ms=kind==SOUND_CLICK?64:(kind==SOUND_CONSUME?245:(kind==SOUND_OPEN?175:125));
                final int count=Math.max(256,rate*ms/1000);
                short[] pcm=new short[count];
                Random rng=new Random(SystemClock.uptimeMillis()+kind*991L);
                double phase=0;

                for(int i=0;i<count;i++) {
                    double u=i/(double)count;
                    double env=Math.exp(-u*(kind==SOUND_CLICK?9.5:5.2));
                    double f;
                    if(kind==SOUND_CLICK) f=145+120*Math.exp(-u*12);
                    else if(kind==SOUND_CONSUME) f=185-105*u+32*Math.sin(u*Math.PI*2);
                    else if(kind==SOUND_OPEN) f=92+150*u;
                    else f=115-55*u;
                    phase+=Math.PI*2*f/rate;

                    double tone=Math.sin(phase)+.38*Math.sin(phase*.51)+.18*Math.sin(phase*1.97);
                    double noise=(rng.nextDouble()*2-1)*(kind==SOUND_CLICK?.72:.18)*(1-u);
                    double throb=(kind==SOUND_CONSUME?.28*Math.sin(u*Math.PI*7):0);
                    double wet=(kind==SOUND_CONSUME?.16*Math.sin(phase*.13)*(1-u):0);
                    double value=(tone*.46+noise+throb+wet)*env;
                    if(kind==SOUND_REJECT) value*=Math.sin(u*Math.PI*7)>0?1:-.55;
                    value=Math.max(-1,Math.min(1,value));
                    pcm[i]=(short)(value*7200);
                }

                AudioTrack track=new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length*2)
                        .build();
                track.write(pcm,0,pcm.length,AudioTrack.WRITE_BLOCKING);
                track.play();
                SystemClock.sleep(ms+35);
                track.stop();
                track.release();
            } catch(Throwable ignored) {}
        },"swarm-audio");
        audio.start();
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
