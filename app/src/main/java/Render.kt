package com.tony.roomscan

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.tony.roomscan.databinding.ActivityMainBinding
import com.tony.roomscan.databinding.ActivityScanBinding
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread

/** Dessine le flux caméra ARCore dans un quad plein écran. */
class BackgroundRenderer {

    var textureId = -1
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0

    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer

    private val ndcQuad = floatArrayOf(
        -1f, -1f,
        +1f, -1f,
        -1f, +1f,
        +1f, +1f
    )

    fun createOnGlThread() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        quadCoords = alloc(ndcQuad.size).apply { put(ndcQuad); position(0) }
        quadTexCoords = alloc(ndcQuad.size)

        program = ShaderUtil.buildProgram(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    private fun alloc(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun draw(frame: Frame) {
        // Les coordonnées de texture changent quand la géométrie d'affichage change
        // (rotation, redimensionnement). ARCore nous dit quand recalculer.
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            quadTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords
            )
        }
        if (frame.timestamp == 0L) return

        quadCoords.position(0)
        quadTexCoords.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }
}

/**
 * Affiche le nuage accumulé, coloré par altitude.
 *
 * Le VBO est rempli de façon incrémentale : seuls les points ajoutés depuis la frame
 * précédente sont téléversés. Renvoyer un million de points à chaque frame ferait
 * tomber le rendu bien en dessous du temps réel.
 */
class PointCloudRenderer {

    companion object {
        /** Au-delà, on continue d'accumuler pour l'export mais on cesse d'afficher. */
        const val MAX_RENDERED = 1_500_000

        private const val VERTEX = """
            uniform mat4 u_MVP;
            uniform float u_PointSize;
            uniform float u_MinY;
            uniform float u_MaxY;
            attribute vec4 a_Position;
            varying float v_T;
            void main() {
                gl_Position = u_MVP * a_Position;
                gl_PointSize = u_PointSize;
                v_T = clamp((a_Position.y - u_MinY) / max(0.001, u_MaxY - u_MinY), 0.0, 1.0);
            }
        """

        private const val FRAGMENT = """
            precision mediump float;
            varying float v_T;
            void main() {
                vec3 low  = vec3(0.15, 0.45, 0.95);
                vec3 mid  = vec3(0.20, 0.85, 0.55);
                vec3 high = vec3(0.98, 0.72, 0.20);
                vec3 c = v_T < 0.5
                    ? mix(low, mid, v_T * 2.0)
                    : mix(mid, high, (v_T - 0.5) * 2.0);
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }

    private var program = 0
    private var vbo = 0
    private var uMvp = 0
    private var uPointSize = 0
    private var uMinY = 0
    private var uMaxY = 0
    private var aPosition = 0

    private var uploaded = 0
    private var staging: FloatBuffer =
        ByteBuffer.allocateDirect(60000 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun createOnGlThread() {
        program = ShaderUtil.buildProgram(VERTEX, FRAGMENT)
        uMvp = GLES20.glGetUniformLocation(program, "u_MVP")
        uPointSize = GLES20.glGetUniformLocation(program, "u_PointSize")
        uMinY = GLES20.glGetUniformLocation(program, "u_MinY")
        uMaxY = GLES20.glGetUniformLocation(program, "u_MaxY")
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, MAX_RENDERED * 3 * 4, null, GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        uploaded = 0
    }

    fun reset() {
        uploaded = 0
    }

    fun sync(cloud: VoxelCloud) {
        val target = minOf(cloud.size, MAX_RENDERED)
        if (target <= uploaded) return

        val newPoints = target - uploaded
        val floats = newPoints * 3
        if (staging.capacity() < floats) {
            staging = ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        staging.position(0)
        staging.put(cloud.xyz, uploaded * 3, floats)
        staging.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER, uploaded * 3 * 4, floats * 4, staging
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        uploaded = target
    }

    fun draw(mvp: FloatArray, minY: Float, maxY: Float, pointSize: Float = 4f) {
        if (uploaded == 0) return

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 12, 0)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(uPointSize, pointSize)
        GLES20.glUniform1f(uMinY, minY)
        GLES20.glUniform1f(uMaxY, maxY)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, uploaded)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

class ScanRenderer(
    private val cloud: VoxelCloud,
    private val onStats: (Stats) -> Unit
) : GLSurfaceView.Renderer {

    data class Stats(
        val points: Int,
        val tracking: String,
        val recording: Boolean,
        val lastAdded: Int
    )

    private val background = BackgroundRenderer()
    private val pointCloud = PointCloudRenderer()

    @Volatile var session: Session? = null
    @Volatile var recording = false
    @Volatile var maxDepthM = 5.0f
    /** Mis à true pendant un export pour geler le nuage. */
    @Volatile var frozen = false

    /** Positions successives de la caméra pendant l'enregistrement, x/y/z entrelacés. */
    val trajectory = ArrayList<Float>()

    private var lastPose: Pose? = null
    private var lastAdded = 0
    private var surfaceW = 1
    private var surfaceH = 1

    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val mvpM = FloatArray(16)

    var pendingReset = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        pointCloud.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceW = width
        surfaceH = height
        // L'activité est verrouillée en portrait, la rotation d'affichage ne varie donc pas.
        session?.setDisplayGeometry(android.view.Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = this.session ?: return

        if (pendingReset) {
            cloud.clear()
            pointCloud.reset()
            synchronized(trajectory) { trajectory.clear() }
            lastPose = null
            lastAdded = 0
            pendingReset = false
        }

        try {
            session.setCameraTextureName(background.textureId)
            val frame = session.update()
            val camera = frame.camera

            background.draw(frame)

            if (camera.trackingState == TrackingState.TRACKING) {
                if (recording && !frozen && hasMovedEnough(camera.pose)) {
                    lastAdded = DepthProcessor.process(frame, maxDepthM, cloud)
                    lastPose = camera.pose
                    val t = camera.pose.translation
                    synchronized(trajectory) {
                        trajectory.add(t[0]); trajectory.add(t[1]); trajectory.add(t[2])
                    }
                }

                camera.getViewMatrix(viewM, 0)
                camera.getProjectionMatrix(projM, 0, 0.05f, 100f)
                Matrix.multiplyMM(mvpM, 0, projM, 0, viewM, 0)

                pointCloud.sync(cloud)
                pointCloud.draw(mvpM, cloud.minY, cloud.maxY)
            }

            onStats(
                Stats(
                    points = cloud.size,
                    tracking = describe(camera.trackingState.name),
                    recording = recording,
                    lastAdded = lastAdded
                )
            )
        } catch (t: Throwable) {
            // Une exception non rattrapée ici tuerait le thread GL et figerait l'écran.
            android.util.Log.e("ScanRenderer", "Erreur de frame", t)
        }
    }

    /**
     * On ne traite une frame que si la caméra a bougé : rester immobile n'apporte
     * aucune mesure nouvelle et ferait chauffer le téléphone pour rien.
     */
    private fun hasMovedEnough(pose: Pose): Boolean {
        val prev = lastPose ?: return true
        val a = pose.translation
        val b = prev.translation
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        if (dx * dx + dy * dy + dz * dz > 0.0009f) return true // 3 cm

        // Rotation : produit scalaire des quaternions, ~3 degrés.
        val qa = pose.rotationQuaternion
        val qb = prev.rotationQuaternion
        var dot = qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]
        if (dot < 0f) dot = -dot
        return dot < 0.9997f
    }

    private fun describe(state: String) = when (state) {
        "TRACKING" -> "suivi actif"
        "PAUSED" -> "suivi perdu — bouge lentement, cherche de la texture"
        else -> "suivi arrêté"
    }

    fun trajectorySnapshot(): FloatArray = synchronized(trajectory) {
        FloatArray(trajectory.size) { trajectory[it] }
    }
}
