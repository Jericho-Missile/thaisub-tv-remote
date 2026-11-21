package app.vitranslator.phone.ps

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.vitranslator.phone.read.Frame
import app.vitranslator.phone.read.Snapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The decoder's picture through the GPU: the decoder draws into a texture (`surface`), and a frame
 * is read by drawing that texture into a plain RGBA picture of `width` x `height` and copying it.
 *
 * The decoder's own buffers can't be read on every phone: Qualcomm's decoders (Snapdragon 8 Elite,
 * Android 16) ignore a request for CPU-readable buffers and write their compressed format, and
 * reading such a buffer's planes aborts the app (a null pointer inside Android). The GPU reads any
 * format the decoder writes.
 *
 * Every frame is taken from the decoder at once (it never waits); `onFrame` gets each one on the GL
 * thread, and its pixels are copied only if it's asked for them then (`Frame.snapshot`).
 */
class GlFrames(private val width: Int, private val height: Int, private val onFrame: (Frame) -> Unit) {
    private val thread = HandlerThread("ps-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var texture2: SurfaceTexture? = null
    private val matrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    /** Where the decoder draws (null when the GPU couldn't be set up). */
    var surface: Surface? = null
        private set

    /** Set up on the GL thread; false when the GPU can't be used (the reason is logged). */
    fun start(): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = try {
                setUp()
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "GPU setup failed", e)
                false
            }
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return ok
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no RGBA config" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        // (the picture is drawn into this off-screen surface, the size of the stream)
        eglSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = program(VERTEX, FRAGMENT)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ frame() }, handler)
        texture2 = st
        surface = Surface(st)
    }

    /** A new frame from the decoder (GL thread): taken at once, drawn and copied only if asked. */
    private fun frame() {
        val st = texture2 ?: return
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "frame", e)
            return
        }
        st.getTransformMatrix(matrix)
        onFrame(object : Frame {
            override fun snapshot(): Snapshot = copy()
        })
    }

    /** The current frame as RGBA, top row first (on the GL thread, from `onFrame`). */
    private fun copy(): Snapshot {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "tex"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "picture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        pixels.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("glReadPixels: $err")
        val out = ByteArray(width * height * 4)
        pixels.rewind()
        pixels.get(out)
        val w = width
        val h = height
        return object : Snapshot {
            override val width = w
            override val height = h
            override fun rgba() = out
        }
    }

    fun stop() {
        handler.post {
            surface?.release()
            surface = null
            texture2?.release()
            texture2 = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val TAG = "GlFrames"

        // (drawn upside down: glReadPixels gives the bottom row first, so the copy comes out top row first)
        private const val VERTEX = """
            attribute vec2 pos;
            uniform mat4 tex;
            varying vec2 uv;
            void main() {
                gl_Position = vec4(pos.x, -pos.y, 0.0, 1.0);
                uv = (tex * vec4((pos + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """

        // The GPU turns the decoder's YUV into RGB (limited range to full). Then the studio levels are
        // stretched once more, as YuvSnapshot does: the PS5's Remote Play picture comes out with blacks
        // at ~32 and whites at ~228 where its HDMI picture has ~15 and ~248, and the game pages' colour
        // checks are tuned on the HDMI picture (GTA VI's pause menu isn't recognised without this).
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES picture;
            varying vec2 uv;
            void main() {
                vec3 c = texture2D(picture, uv).rgb;
                gl_FragColor = vec4(clamp((c - 16.0 / 255.0) * (255.0 / 219.0), 0.0, 1.0), 1.0);
            }
        """
    }
}
