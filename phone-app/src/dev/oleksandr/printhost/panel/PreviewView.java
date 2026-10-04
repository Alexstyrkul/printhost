package dev.oleksandr.printhost.panel;

import dev.oleksandr.printhost.R;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 3D preview of the print: the model's extrusion lines on the bed. One finger turns it, two fingers zoom.
 * Lines up to the "cut" height are drawn as printed (green), the rest as still to come (grey).
 */
final class PreviewView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final float BED = 220f;
    private static final String VS =
            "uniform mat4 uMvp; uniform float uCutZ; uniform float uFlat; uniform vec3 uColor;"
                    + "attribute vec3 aPos; attribute float aShade; varying vec3 vCol; varying float vTodo;"
                    + "void main() {"
                    + "  gl_Position = uMvp * vec4(aPos, 1.0);"
                    + "  vec3 done = vec3(0.13, 0.80, 0.42) * abs(aShade);"
                    + "  vec3 todo = vec3(0.72, 0.75, 0.80);"
                    + "  vTodo = (uFlat < 0.5 && aPos.z > uCutZ + 0.001) ? 1.0 : 0.0;"
                    // of the part still to print only the outer shell is drawn: infill showing through looked like noise
                    + "  if (vTodo > 0.5 && aShade < 0.0) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);"
                    + "  vCol = uFlat > 0.5 ? uColor : (vTodo > 0.5 ? todo : done);"
                    + "}";
    // uPass 0 draws only the solid parts (bed, printed lines); uPass 1 only the lines still to come, see-through.
    private static final String FS = "precision mediump float; varying vec3 vCol; varying float vTodo; uniform float uPass;"
            + "void main() {"
            + "  if (uPass < 0.5 && vTodo > 0.5) discard;"
            + "  if (uPass > 0.5 && vTodo < 0.5) discard;"
            + "  gl_FragColor = vec4(vCol, vTodo > 0.5 ? 0.10 : 1.0);"
            + "}";

    private volatile GcodeModel model;
    private volatile boolean modelDirty = false;
    private volatile float cutZ = 0;
    private float yaw = -55, pitch = 28, zoom = 1f;  // touched on the UI thread, read by the GL thread
    private float lastX, lastY;
    private boolean scaling = false;
    private static final float MIN_ZOOM = 0.08f, MAX_ZOOM = 6f;
    private final GestureDetector doubleTap;
    private float pinchStart = 1, zoomStart = 1;
    // Two fingers also drag the view: how far the look-at point is shifted, in mm, along the screen's right and up.
    private volatile float panRight = 0, panUp = 0;
    private float midX, midY;
    private volatile float mmPerPixel = 0.5f;  // at the look-at point, set by the last drawn frame

    private int program, aPos, aShade, uMvp, uCutZ, uFlat, uColor, uPass;
    private final int[] vbo = new int[2];
    private int bedTris, bedLines, modelVerts;
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];
    private float aspect = 1;
    private int viewHeight = 1;

    PreviewView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        doubleTap = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {  // back to the starting view
                yaw = -55;
                pitch = 28;
                zoom = 1f;
                panRight = panUp = 0;
                requestRender();
                return true;
            }
        });
    }

    void setModel(GcodeModel m) {
        model = m;
        modelDirty = true;
        requestRender();
    }

    void setCutZ(float z) {
        if (z == cutZ) return;
        cutZ = z;
        requestRender();
    }

    private static float spread(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * One finger turns the model; two fingers zoom by the ratio of their distance to the distance they started at
     * (our own pinch: the system's ScaleGestureDetector ignores fingers that start close together, so on this small
     * view zooming back out often did nothing). A double tap goes back to the starting view.
     */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);  // turning the model must not scroll the screen
        doubleTap.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                scaling = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    scaling = true;
                    pinchStart = Math.max(1f, spread(e));
                    zoomStart = zoom;
                    midX = (e.getX(0) + e.getX(1)) / 2;
                    midY = (e.getY(0) + e.getY(1)) / 2;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // one of the two fingers lifted: the next two-finger touch starts afresh (no jump)
                scaling = true;
                pinchStart = -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (scaling && e.getPointerCount() >= 2 && pinchStart < 0) {
                    pinchStart = Math.max(1f, spread(e));
                    zoomStart = zoom;
                    midX = (e.getX(0) + e.getX(1)) / 2;
                    midY = (e.getY(0) + e.getY(1)) / 2;
                } else if (scaling && e.getPointerCount() >= 2) {
                    zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomStart * spread(e) / pinchStart));
                    // the point between the fingers drags the bed with it
                    float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                    panRight -= (mx - midX) * mmPerPixel;
                    panUp += (my - midY) * mmPerPixel;
                    midX = mx;
                    midY = my;
                    requestRender();
                } else if (!scaling && e.getPointerCount() == 1) {
                    float k = 180f / Math.max(1, getWidth());
                    yaw -= (e.getX() - lastX) * k;
                    pitch = Math.max(4, Math.min(88, pitch + (e.getY() - lastY) * k));
                    lastX = e.getX();
                    lastY = e.getY();
                    requestRender();
                }
                break;
            default:
                break;
        }
        return true;
    }

    // ---- GL thread ----

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aShade = GLES20.glGetAttribLocation(program, "aShade");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uCutZ = GLES20.glGetUniformLocation(program, "uCutZ");
        uFlat = GLES20.glGetUniformLocation(program, "uFlat");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uPass = GLES20.glGetUniformLocation(program, "uPass");
        GLES20.glGenBuffers(2, vbo, 0);
        uploadBed();
        modelVerts = 0;
        modelDirty = model != null;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0x1E / 255f, 0x20 / 255f, 0x24 / 255f, 1f);  // the card's colour
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static FloatBuffer buffer(float[] data, int count) {
        FloatBuffer b = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data, 0, count).position(0);
        return b;
    }

    /** The bed: a plate (two triangles) and a 20 mm grid on it. */
    private void uploadBed() {
        float z = -0.05f;
        float[] v = new float[(6 + 2 * 2 * 12) * 4];
        int n = 0;
        float[][] quad = {{0, 0}, {BED, 0}, {BED, BED}, {0, 0}, {BED, BED}, {0, BED}};
        for (float[] q : quad) {
            v[n++] = q[0];
            v[n++] = q[1];
            v[n++] = z - 0.1f;
            v[n++] = 1;
        }
        bedTris = 6;
        for (int i = 0; i <= 11; i++) {
            float t = i * 20;
            float[][] seg = {{t, 0}, {t, BED}, {0, t}, {BED, t}};
            for (float[] q : seg) {
                v[n++] = q[0];
                v[n++] = q[1];
                v[n++] = z;
                v[n++] = 1;
            }
        }
        bedLines = 12 * 4;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 4, buffer(v, n), GLES20.GL_STATIC_DRAW);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        aspect = (float) w / Math.max(1, h);
        viewHeight = h;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GcodeModel m = model;
        if (modelDirty && m != null) {
            modelDirty = false;
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[1]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.floats * 4, buffer(m.verts, m.floats), GLES20.GL_STATIC_DRAW);
            modelVerts = m.vertexCount();
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Look at the middle of the model (or of the bed), from a distance that fits it.
        float cx = BED / 2, cy = BED / 2, cz = 0, size = BED * 0.8f;
        if (m != null && m.vertexCount() > 0) {
            cx = (m.minX + m.maxX) / 2;
            cy = (m.minY + m.maxY) / 2;
            cz = m.maxZ / 2;
            float dx = m.maxX - m.minX, dy = m.maxY - m.minY;
            size = (float) Math.sqrt(dx * dx + dy * dy + m.maxZ * m.maxZ);
        }
        float dist = size * 1.9f / zoom;
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        // shift the look-at point along the screen's right and up directions (two-finger drag)
        float fx = (float) (-Math.cos(pr) * Math.cos(yr)), fy = (float) (-Math.cos(pr) * Math.sin(yr)), fz = (float) -Math.sin(pr);
        float rl = (float) Math.sqrt(fx * fx + fy * fy);
        float rx = fy / rl, ry = -fx / rl;                    // right = forward x world-up
        float ux = ry * fz, uy = -rx * fz, uz = rx * fy - ry * fx;  // up = right x forward
        cx += rx * panRight + ux * panUp;
        cy += ry * panRight + uy * panUp;
        cz += uz * panUp;
        mmPerPixel = (float) (2 * dist * Math.tan(Math.toRadians(17)) / Math.max(1, viewHeight));
        float ex = cx + (float) (dist * Math.cos(pr) * Math.cos(yr));
        float ey = cy + (float) (dist * Math.cos(pr) * Math.sin(yr));
        float ez = cz + (float) (dist * Math.sin(pr));
        Matrix.setLookAtM(view, 0, ex, ey, ez, cx, cy, cz, 0, 0, 1);
        Matrix.perspectiveM(proj, 0, 34, aspect, Math.max(1f, dist / 60), dist * 3 + 800);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniform1f(uCutZ, cutZ);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aShade);

        bind(vbo[0]);
        GLES20.glUniform1f(uPass, 0);
        GLES20.glUniform1f(uFlat, 1);
        GLES20.glUniform3f(uColor, 0.085f, 0.09f, 0.10f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, bedTris);
        GLES20.glUniform3f(uColor, 0.20f, 0.21f, 0.24f);
        GLES20.glLineWidth(1f);
        GLES20.glDrawArrays(GLES20.GL_LINES, bedTris, bedLines);

        if (modelVerts > 0) {
            bind(vbo[1]);
            GLES20.glUniform1f(uFlat, 0);
            GLES20.glLineWidth(2f);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, modelVerts);
            // the part still to print: see-through, so what is inside stays visible
            GLES20.glUniform1f(uPass, 1);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDepthMask(false);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, modelVerts);
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void bind(int buffer) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 16, 0);
        GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, 16, 12);
    }
}
