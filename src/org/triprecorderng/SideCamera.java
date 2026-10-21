package org.triprecorderng;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One side camera of the car's 360-degree feed, drawn in a window over the other apps. The feed is one 7680 x 1300
 * picture made of four 1920 x 1300 fisheye pictures side by side (rear, left mirror, right mirror, front); the car's
 * camera class opens it with the CAMERA permission only. Two ways to draw a mirror camera:
 * <ul>
 * <li>{@link #VIEW_SIDE}: the car's own side view rebuilt from its camera calibration, the ground beside and ahead of
 * the car with its side down one edge, 1104 x 990 in shape;</li>
 * <li>{@link #VIEW_FISHEYE}: the camera's picture as it comes, 1920 x 1300 in shape.</li>
 * </ul>
 * The numbers are the nominal ones from the head unit's own camera files (the car's refined calibration is not
 * readable), see docs/byd-internals/blind-spot-cameras.md. All calls but the drawing thread's own are for the main
 * thread.
 */
final class SideCamera {
    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int VIEW_SIDE = 0;
    static final int VIEW_FISHEYE = 1;

    /** The shape of the two views (width over height). */
    static final double SIDE_ASPECT = 1104.0 / 990.0;
    static final double FISHEYE_ASPECT = 1920.0 / 1300.0;

    /** How far, in pixels of the side view's 990 high canvas, a wide window's crop sits above the middle. */
    private static final double WIDE_UP = 60.0;

    /** The car multiplies the colours of its surround-view pictures by this. */
    private static final float GAIN = 1.23f;

    /**
     * Per mirror camera (right, left): fx, fy, cu, cv, the four distortion terms, the Rodrigues rotation from sensor
     * to body, then the side view's virtual camera: yaw, pitch, roll (degrees), focal length, xi, principal point.
     */
    private static final double[][] CAMERA = {
        {446.008331, 445.7565, 960.551636, 650.56427, 0.158470005, -0.0794069991, 0.0249170009, -0.00309400004,
            0.00586933689, 2.90007901, -1.20188904, -5.48, 34.89, 0.84, 900.2, 0.522, 180.1, 383.4},
        {445.333893, 445.775513, 956.290894, 653.459229, 0.155710995, -0.0756009966, 0.0230160002, -0.00277299993,
            -2.35627246, -0.00572358118, 0.00275337114, 5.48, 34.89, -0.84, 900.2, 0.522, 923.9, 383.4}};

    private static final String VERTEX = "attribute vec4 aPos; varying vec2 vPos;\n"
            + "void main(){ gl_Position = aPos; vPos = aPos.xy; }";

    /** Rounded corners: 1 inside the window's rounded rectangle (width, height, radius in uRnd), 0 outside. */
    private static final String ROUND = "uniform vec3 uRnd;\n"
            + "float mask(){ vec2 h = uRnd.xy * 0.5; vec2 p = abs(gl_FragCoord.xy - h) - (h - vec2(uRnd.z));\n"
            + "  float d = length(max(p, 0.0)) + min(max(p.x, p.y), 0.0) - uRnd.z; return clamp(0.5 - d, 0.0, 1.0); }\n";

    /** The side view: a pixel of the virtual camera becomes a ray, then a point of the fisheye picture. */
    private static final String FRAGMENT_SIDE = "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float; varying vec2 vPos;\n" + ROUND
            + "uniform samplerExternalOES uS; uniform mat4 uTex; uniform vec4 uCrop; uniform vec4 uInt; uniform vec4 uK;\n"
            + "uniform vec4 uV; uniform vec2 uCanvas; uniform vec2 uWin; uniform vec2 uWinY; uniform float uGain;\n"
            + "uniform vec3 uRow0; uniform vec3 uRow1; uniform vec3 uRow2; uniform vec3 uAx; uniform vec3 uAy; uniform vec3 uAz;\n"
            + "void main(){\n"
            + "  vec2 pix = vec2(uWin.x + (vPos.x * 0.5 + 0.5) * uWin.y, uWinY.x + (0.5 - vPos.y * 0.5) * uWinY.y);\n"
            + "  float mx = (pix.x - uV.z) / uV.x, my = (pix.y - uV.w) / uV.x; float r2 = mx * mx + my * my;\n"
            + "  float lam = (uV.y + sqrt(1.0 + (1.0 - uV.y * uV.y) * r2)) / (1.0 + r2);\n"
            + "  vec3 ray = vec3(lam * mx, lam * my, lam - uV.y);\n"
            + "  vec3 d = uAx * ray.x + uAy * ray.y + uAz * ray.z;\n"
            + "  vec3 s = vec3(dot(uRow0, d), dot(uRow1, d), dot(uRow2, d));\n"
            + "  float theta = acos(clamp(s.z / length(s), -1.0, 1.0));\n"
            + "  float phi = atan(s.y, s.x);\n"
            + "  float t2 = theta * theta;\n"
            + "  float td = theta * (1.0 + t2 * (uK.x + t2 * (uK.y + t2 * (uK.z + t2 * uK.w))));\n"
            + "  float px = uInt.z + uInt.x * td * cos(phi); float py = uInt.w + uInt.y * td * sin(phi);\n"
            + "  if (px < 0.0 || px > 1920.0 || py < 0.0 || py > 1300.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0) * mask(); return; }\n"
            + "  vec2 t = (uTex * vec4(px / 1920.0, 1.0 - py / 1300.0, 0.0, 1.0)).xy;\n"
            + "  vec4 c = texture2D(uS, vec2(uCrop.x + t.x * uCrop.z, uCrop.y + t.y * uCrop.w));\n"
            + "  gl_FragColor = vec4(clamp(c.rgb * uGain, 0.0, 1.0), 1.0) * mask();\n"
            + "}";

    /** The camera's own picture: one quarter of the feed, zoomed to fill the window (uFit is the size of the picture in window units). */
    private static final String FRAGMENT_FISHEYE = "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float; varying vec2 vPos;\n" + ROUND
            + "uniform samplerExternalOES uS; uniform mat4 uTex; uniform vec4 uCrop; uniform float uGain; uniform vec2 uFit;\n"
            + "void main(){\n"
            + "  vec2 q = vPos / uFit;\n"
            + "  if (abs(q.x) > 1.0 || abs(q.y) > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0) * mask(); return; }\n"
            + "  vec2 t = (uTex * vec4(q.x * 0.5 + 0.5, q.y * 0.5 + 0.5, 0.0, 1.0)).xy;\n"
            + "  vec4 c = texture2D(uS, vec2(uCrop.x + t.x * uCrop.z, uCrop.y + t.y * uCrop.w));\n"
            + "  gl_FragColor = vec4(clamp(c.rgb * uGain, 0.0, 1.0), 1.0) * mask();\n"
            + "}";

    /** The window's title, drawn over the picture from a bitmap. */
    private static final String TITLE_VERTEX = "attribute vec4 aPos; attribute vec2 aT; varying vec2 vT;\n"
            + "void main(){ gl_Position = aPos; vT = aT; }";
    private static final String TITLE_FRAGMENT = "precision mediump float; varying vec2 vT; uniform sampler2D uT;\n"
            + "void main(){ gl_FragColor = texture2D(uT, vT); }";

    /**
     * What the car's own turn-signal window does: a 20 sp white title, one line high, at the very top edge (the window
     * resets the title's layout to full width, wrap-content height, no margin), corners about 10 dp.
     */
    private static final float TITLE_SP = 20f, CORNER_DP = 10f;

    interface Listener {
        /** The window was tapped; the caller decides whether to hide it. */
        void onTap();

        /** The window was dragged and let go at (x, y) in pixels of its display. */
        void onMoved(int x, int y);
    }

    private WindowManager windows;
    private View window;
    private AtomicBoolean alive;
    private Thread render;
    /** The drawing thread that is still closing the camera after its window went; the next one waits for it. */
    private Thread closing;
    private String title = "";
    private float density = 2f, scaledDensity = 2f;
    private int side;
    private int view;
    private double aspect = SIDE_ASPECT;
    private Runnable onFirstFrame;

    boolean showing() {
        return window != null;
    }

    /**
     * Shows the picture of one side in a window at (x, y), w by h pixels, on the display of {@code windowContext}
     * (the application context for the centre screen, a window context for another display). With
     * {@code movable} the window can be dragged by hand, and a tap that does not move it calls {@code tap}; the
     * cluster cannot be touched, and its window is made the way the car's own cluster window is.
     */
    void show(Context windowContext, int side, int view, int x, int y, int w, int h, boolean movable, final Listener tap,
              Runnable firstFrame) {
        if (window != null) hide();
        this.side = side;
        this.view = view;
        this.onFirstFrame = firstFrame;
        this.aspect = (double) w / h;
        // the car's own windows are in English only, so is this one; the fisheye is the whole camera, not the front part
        this.title = view == VIEW_FISHEYE ? (side == LEFT ? "Left" : "Right") : (side == LEFT ? "Left Front" : "Right Front");
        android.util.DisplayMetrics dm = windowContext.getResources().getDisplayMetrics();
        this.density = dm.density;
        this.scaledDensity = dm.scaledDensity;
        try {
            windows = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
            SurfaceView sv = new SurfaceView(windowContext);
            // the corners are cut by the drawing itself, so the surface has to carry an alpha
            sv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
            // on the centre screen the picture has to cover what is under it completely
            if (movable) sv.setZOrderOnTop(true);
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                }

                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    startRender(holder.getSurface());
                }

                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    stopRender();
                }
            });
            // a touchable window: the system caps the opacity of one that cannot be touched, and the picture
            // underneath shows through; the cluster's window is the car's own kind (not focusable, format 1)
            WindowManager.LayoutParams lp = movable
                    ? new WindowManager.LayoutParams(w, h, 2038, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT)
                    : new WindowManager.LayoutParams(w, h, 2038, 40, 1);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = x;
            lp.y = y;
            if (movable) sv.setOnTouchListener(new Mover(lp, w, h, windowContext, tap));
            windows.addView(sv, lp);
            window = sv;
        } catch (Throwable t) {
            Diag.log("side camera window failed", t);
            window = null;
        }
    }

    /** Drags the window with a finger; a touch that hardly moves is a tap. */
    private final class Mover implements View.OnTouchListener {
        private static final int SLOP_PX = 12;
        private final WindowManager.LayoutParams lp;
        private final int w, h;
        private final int screenW, screenH;
        private final Listener listener;
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        Mover(WindowManager.LayoutParams lp, int w, int h, Context ctx, Listener listener) {
            this.lp = lp;
            this.w = w;
            this.h = h;
            this.listener = listener;
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }

        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.abs(dx) < SLOP_PX && Math.abs(dy) < SLOP_PX) return true;
                    moved = true;
                    lp.x = Math.max(0, Math.min(screenW - w, startX + Math.round(dx)));
                    lp.y = Math.max(0, Math.min(screenH - h, startY + Math.round(dy)));
                    try {
                        windows.updateViewLayout(window, lp);
                    } catch (Throwable t) {
                        Diag.log("side camera move failed", t);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (moved) listener.onMoved(lp.x, lp.y);
                    else listener.onTap();
                    return true;
                default:
                    return true;
            }
        }
    }

    void hide() {
        // the window goes first, as the car's own does; the camera is closed afterwards by its drawing thread
        View w = window;
        window = null;
        if (w != null && windows != null) {
            try {
                windows.removeViewImmediate(w);
            } catch (Throwable t) {
                Diag.log("side camera window removal failed", t);
            }
        }
        stopRender();
    }

    private synchronized void startRender(final Surface surface) {
        if (alive != null && alive.get()) return;
        final AtomicBoolean mine = new AtomicBoolean(true);
        alive = mine;
        final int s = side;
        final int v = view;
        final double a = aspect;
        final Thread previous = closing;
        render = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (previous != null) previous.join(3000);
                    if (mine.get()) draw(surface, s, v, a, mine);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    Diag.log("side camera drawing failed", t);
                }
            }
        }, "side-camera");
        render.setDaemon(true);
        render.start();
    }

    /** Tells the drawing thread to stop; it closes the camera on its own time, without holding anything up. */
    private synchronized void stopRender() {
        if (alive != null) alive.set(false);
        alive = null;
        if (render != null) closing = render;
        render = null;
    }

    // ---- the drawing thread --------------------------------------------------------------------
    private static int shader(int type, String source) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, source);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(sh));
        return sh;
    }

    private static double[][] rodrigues(double[] r) {
        double th = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        double[] k = {r[0] / th, r[1] / th, r[2] / th};
        double c = Math.cos(th), s = Math.sin(th), v = 1 - c;
        return new double[][]{
            {c + k[0] * k[0] * v, k[0] * k[1] * v - k[2] * s, k[0] * k[2] * v + k[1] * s},
            {k[1] * k[0] * v + k[2] * s, c + k[1] * k[1] * v, k[1] * k[2] * v - k[0] * s},
            {k[2] * k[0] * v - k[1] * s, k[2] * k[1] * v + k[0] * s, c + k[2] * k[2] * v}};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] mul(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    private static double[] rotateZ(double[] v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
    }

    private static void uniform3(int prog, String name, double[] v) {
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, name), (float) v[0], (float) v[1], (float) v[2]);
    }

    /** Sets the uniforms of the side view for one mirror camera. */
    private static void sideUniforms(int prog, int side, double aspect) {
        double[] c = CAMERA[side == LEFT ? 1 : 0];
        double[][] r = rodrigues(new double[]{c[8], c[9], c[10]});
        double yaw = Math.toRadians(c[11]), pitch = Math.toRadians(c[12]), roll = Math.toRadians(c[13]);
        // the virtual camera's axes in the car's frame (x front, y left, z up): looking forward, x to the right,
        // y down; tilted down by the pitch, rolled, then turned by the yaw
        double[] cx = {0, -1, 0}, cy = {0, 0, -1}, cz = {1, 0, 0};
        double[] cz1 = add(mul(cz, Math.cos(pitch)), mul(cy, Math.sin(pitch)));
        double[] cy1 = add(mul(cy, Math.cos(pitch)), mul(cz, -Math.sin(pitch)));
        double[] cx2 = add(mul(cx, Math.cos(roll)), mul(cy1, Math.sin(roll)));
        double[] cy2 = add(mul(cy1, Math.cos(roll)), mul(cx, -Math.sin(roll)));
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uInt"), (float) c[0], (float) c[1], (float) c[2], (float) c[3]);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uK"), (float) c[4], (float) c[5], (float) c[6], (float) c[7]);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uV"), (float) c[14], (float) c[15], (float) c[16], (float) c[17]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uCanvas"), 1104f, 990f);
        // a window narrower than the car's picture shows the part of it next to the car's side: the left edge of the
        // right camera's picture, the right edge of the left camera's; a wider one shows the middle of its height,
        // zoomed rather than stretched
        double wide = Math.min(1104.0, 990.0 * aspect);
        double high = Math.min(990.0, 1104.0 / aspect);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uWin"), (float) (side == LEFT ? 1104.0 - wide : 0.0), (float) wide);
        // the middle of the height, moved up a little to keep more of the sky
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uWinY"), (float) Math.max(0.0, (990.0 - high) / 2 - WIDE_UP), (float) high);
        uniform3(prog, "uRow0", new double[]{r[0][0], r[1][0], r[2][0]});
        uniform3(prog, "uRow1", new double[]{r[0][1], r[1][1], r[2][1]});
        uniform3(prog, "uRow2", new double[]{r[0][2], r[1][2], r[2][2]});
        uniform3(prog, "uAx", rotateZ(cx2, yaw));
        uniform3(prog, "uAy", rotateZ(cy2, yaw));
        uniform3(prog, "uAz", rotateZ(cz1, yaw));
    }

    private static Object openCamera() throws Exception {
        Class<?> cam = Class.forName("android.hardware.AVMCamera");
        return cam.getMethod("open", int.class, int.class).invoke(null, 2, 1);
    }

    private static void closeCamera(Object camera) {
        if (camera == null) return;
        try {
            Class<?> cam = Class.forName("android.hardware.AVMCamera");
            cam.getMethod("stopPreview").invoke(camera);
            cam.getMethod("close").invoke(camera);
        } catch (Throwable t) {
            Diag.log("side camera close failed", t);
        }
    }

    private void draw(Surface out, int side, int view, double windowAspect, AtomicBoolean alive) throws Exception {
        HiddenApi.exempt();
        EGLDisplay dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(dpy, ver, 0, ver, 1);
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE}, 0, cfg, 0, 1, n, 0);
        EGLContext ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface win = EGL14.eglCreateWindowSurface(dpy, cfg[0], out, new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, win, win, ctx);
        Object camera = null;
        try {
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, view == VIEW_SIDE ? FRAGMENT_SIDE : FRAGMENT_FISHEYE));
            GLES20.glLinkProgram(prog);
            GLES20.glUseProgram(prog);
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            SurfaceTexture st = new SurfaceTexture(tex[0]);
            st.setDefaultBufferSize(7680, 1300);
            final Object lock = new Object();
            final boolean[] fresh = new boolean[1];
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override public void onFrameAvailable(SurfaceTexture t) {
                    synchronized (lock) {
                        fresh[0] = true;
                        lock.notifyAll();
                    }
                }
            });

            // the quarter of the feed that is this mirror's picture: 2 = left, 3 = right
            float quarter = (side == LEFT ? 1 : 2) / 4f;
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uCrop"), quarter, 0f, 0.25f, 1f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGain"), GAIN);
            if (view == VIEW_SIDE) sideUniforms(prog, side, windowAspect);
            int[] sz = new int[1];
            EGL14.eglQuerySurface(dpy, win, EGL14.EGL_WIDTH, sz, 0);
            final int surfW = Math.max(1, sz[0]);
            EGL14.eglQuerySurface(dpy, win, EGL14.EGL_HEIGHT, sz, 0);
            final int surfH = Math.max(1, sz[0]);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uRnd"), surfW, surfH, CORNER_DP * density);
            if (view == VIEW_FISHEYE) {
                // the picture zoomed to fill the window, centred, never stretched: what does not fit is cut off
                GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uFit"),
                        (float) Math.max(1.0, FISHEYE_ASPECT / windowAspect), (float) Math.max(1.0, windowAspect / FISHEYE_ASPECT));
            }
            // the title, a small bitmap drawn over the top of the picture
            int tprog = 0, ttex = 0;
            FloatBuffer tPos = null, tCoord = null;
            if (title != null && title.length() > 0) {
                Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
                pt.setColor(0xFFFFFFFF);
                pt.setTextSize(TITLE_SP * scaledDensity);
                pt.setTextAlign(Paint.Align.CENTER);
                pt.setShadowLayer(2f * density, 0f, density, 0xB0000000);
                // one line as a text view makes it (with the font's own padding), the baseline below the line's top
                Paint.FontMetrics fm = pt.getFontMetrics();
                int bh = (int) Math.ceil(fm.bottom - fm.top);
                int bw = Math.min(surfW, (int) Math.ceil(pt.measureText(title) + 12 * density));
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                new Canvas(bmp).drawText(title, bw / 2f, -fm.top, pt);
                tprog = GLES20.glCreateProgram();
                GLES20.glAttachShader(tprog, shader(GLES20.GL_VERTEX_SHADER, TITLE_VERTEX));
                GLES20.glAttachShader(tprog, shader(GLES20.GL_FRAGMENT_SHADER, TITLE_FRAGMENT));
                GLES20.glLinkProgram(tprog);
                int[] tt = new int[1];
                GLES20.glGenTextures(1, tt, 0);
                ttex = tt[0];
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ttex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
                bmp.recycle();
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                float x1 = (float) bw / surfW;
                float yTop = 1f;
                float yBot = yTop - 2f * bh / surfH;
                tPos = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                tPos.put(new float[]{-x1, yBot, 0, 1, x1, yBot, 0, 1, -x1, yTop, 0, 1, x1, yTop, 0, 1});
                tPos.position(0);
                tCoord = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                tCoord.put(new float[]{0, 1, 1, 1, 0, 0, 1, 0});
                tCoord.position(0);
            }
            int uTex = GLES20.glGetUniformLocation(prog, "uTex");
            int aPos = GLES20.glGetAttribLocation(prog, "aPos");
            FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1});
            quad.position(0);

            camera = openCamera();
            if (camera == null) {
                Diag.log("side camera: the camera did not open");
                return;
            }
            Class<?> cam = Class.forName("android.hardware.AVMCamera");
            cam.getMethod("setTexture", SurfaceTexture.class, int.class).invoke(camera, st, 0);
            cam.getMethod("startPreview").invoke(camera);
            Diag.log("side camera on: " + (side == LEFT ? "left " : "right ") + (view == VIEW_SIDE ? "side view" : "fisheye"));

            float[] m = new float[16];
            int stalls = 0;
            long frames = 0, since = System.currentTimeMillis();
            while (alive.get()) {
                boolean got;
                synchronized (lock) {
                    if (!fresh[0]) lock.wait(500);
                    got = fresh[0];
                    fresh[0] = false;
                }
                if (!got) {
                    // no picture for a while (another app took the camera): open it again
                    if (++stalls >= 3) {
                        stalls = 0;
                        closeCamera(camera);
                        camera = openCamera();
                        if (camera != null) {
                            cam.getMethod("setTexture", SurfaceTexture.class, int.class).invoke(camera, st, 0);
                            cam.getMethod("startPreview").invoke(camera);
                        }
                    }
                    continue;
                }
                stalls = 0;
                st.updateTexImage();
                st.getTransformMatrix(m);
                GLES20.glUniformMatrix4fv(uTex, 1, false, m, 0);
                GLES20.glEnableVertexAttribArray(aPos);
                GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 16, quad);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                if (ttex != 0) {
                    GLES20.glUseProgram(tprog);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ttex);
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(tprog, "uT"), 1);
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    int tp = GLES20.glGetAttribLocation(tprog, "aPos"), tc = GLES20.glGetAttribLocation(tprog, "aT");
                    GLES20.glEnableVertexAttribArray(tp);
                    GLES20.glVertexAttribPointer(tp, 4, GLES20.GL_FLOAT, false, 16, tPos);
                    GLES20.glEnableVertexAttribArray(tc);
                    GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 8, tCoord);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    GLES20.glDisable(GLES20.GL_BLEND);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glUseProgram(prog);
                }
                EGL14.eglSwapBuffers(dpy, win);
                frames++;
                if (frames == 1) {
                    Diag.log("side camera: first picture drawn");
                    Runnable first = onFirstFrame;
                    if (first != null) first.run();
                }
                long now = System.currentTimeMillis();
                if (now - since >= 5000) {
                    Diag.log("side camera: " + frames + " pictures drawn so far");
                    since = now;
                }
            }
        } finally {
            closeCamera(camera);
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, win);
            EGL14.eglDestroyContext(dpy, ctx);
            Diag.log("side camera off");
        }
    }
}
