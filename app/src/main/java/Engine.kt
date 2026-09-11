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

object ShaderUtil {

    fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Échec du link du programme GL : $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Échec de compilation du shader : $log")
        }
        return shader
    }
}

/**
 * Nuage de points dédupliqué sur une grille de voxels.
 *
 * Chaque point du monde est quantifié sur une grille régulière ; un seul point est
 * conservé par cellule. Sans ça, un scan de deux minutes produirait plusieurs millions
 * de mesures redondantes sur les mêmes surfaces.
 *
 * Le jeu de clés est une table de hachage à adressage ouvert sur des `long` primitifs :
 * un HashMap<Long, ...> de Kotlin boxerait chaque clé et saturerait le GC bien avant
 * le million de points.
 *
 * Cette classe n'est pas thread-safe. Tout est écrit depuis le thread GL.
 */
class VoxelCloud(var voxelSize: Float = 0.03f) {

    companion object {
        private const val EMPTY = Long.MIN_VALUE
        /** 21 bits signés par axe, soit +/- 1 048 575 voxels : largement suffisant. */
        private const val MASK = 0x1FFFFFL
    }

    private var keys = LongArray(1 shl 16) { EMPTY }
    private var mask = keys.size - 1
    private var occupied = 0
    private var resizeAt = (keys.size * 0.6f).toInt()

    /** Coordonnées monde, x/y/z entrelacés. */
    var xyz = FloatArray(1 shl 18)
        private set

    var size = 0
        private set

    var minY = Float.MAX_VALUE
        private set
    var maxY = -Float.MAX_VALUE
        private set

    /** @return true si le point occupait une cellule encore vide. */
    fun add(x: Float, y: Float, z: Float): Boolean {
        val inv = 1f / voxelSize
        val qx = Math.floor((x * inv).toDouble()).toLong()
        val qy = Math.floor((y * inv).toDouble()).toLong()
        val qz = Math.floor((z * inv).toDouble()).toLong()
        val key = ((qx and MASK) shl 42) or ((qy and MASK) shl 21) or (qz and MASK)

        var i = (hash(key) and mask.toLong()).toInt()
        while (true) {
            val k = keys[i]
            if (k == EMPTY) break
            if (k == key) return false
            i = (i + 1) and mask
        }

        keys[i] = key
        occupied++

        if (size * 3 + 3 > xyz.size) xyz = xyz.copyOf(xyz.size * 2)
        val o = size * 3
        xyz[o] = x; xyz[o + 1] = y; xyz[o + 2] = z
        size++

        if (y < minY) minY = y
        if (y > maxY) maxY = y

        if (occupied >= resizeAt) grow()
        return true
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2) { EMPTY }
        mask = keys.size - 1
        resizeAt = (keys.size * 0.6f).toInt()
        for (k in old) {
            if (k == EMPTY) continue
            var i = (hash(k) and mask.toLong()).toInt()
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k
        }
    }

    private fun hash(k: Long): Long {
        var h = k * -7046029254386353131L
        h = h xor (h ushr 32)
        return h and Long.MAX_VALUE
    }

    fun clear() {
        java.util.Arrays.fill(keys, EMPTY)
        occupied = 0
        size = 0
        minY = Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
    }

    /**
     * Hauteur du sol estimée par percentile bas des altitudes, plutôt que par
     * détection de plan : le percentile résiste aux quelques points aberrants
     * sous le sol que produit inévitablement la profondeur par parallaxe.
     */
    fun estimateFloorY(percentile: Float = 0.03f): Float {
        if (size == 0) return 0f
        val step = if (size > 20000) size / 20000 else 1
        val sample = ArrayList<Float>(size / step + 1)
        var i = 0
        while (i < size) {
            sample.add(xyz[i * 3 + 1])
            i += step
        }
        sample.sort()
        val idx = (sample.size * percentile).toInt().coerceIn(0, sample.size - 1)
        return sample[idx]
    }
}

/**
 * Convertit la carte de profondeur brute d'ARCore en points dans le repère monde.
 *
 * On utilise la profondeur *brute* (raw) plutôt que la version lissée : la lissée est
 * interpolée et remplie par inpainting, ce qui est joli à l'écran mais inventerait de
 * la géométrie dans un scan. La brute ne renvoie que ce qui a réellement été mesuré,
 * accompagnée d'une image de confiance.
 */
object DepthProcessor {

    /** Confiance minimale acceptée, sur 255. Plus haut = moins de points, moins de bruit. */
    const val MIN_CONFIDENCE = 140

    /**
     * @return nombre de nouveaux voxels ajoutés au nuage.
     */
    fun process(frame: Frame, maxDepthM: Float, cloud: VoxelCloud): Int {
        var tmpDepth: Image? = null
        var tmpConf: Image? = null
        try {
            tmpDepth = frame.acquireRawDepthImage16Bits()
            tmpConf = frame.acquireRawDepthConfidenceImage()
        } catch (e: NotYetAvailableException) {
            tmpDepth?.close()
            tmpConf?.close()
            return 0
        }

        val depth: Image = tmpDepth ?: run { tmpConf?.close(); return 0 }
        val conf: Image = tmpConf ?: run { depth.close(); return 0 }

        try {
            val w = depth.width
            val h = depth.height

            val dPlane = depth.planes[0]
            val dBuf = dPlane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val dRowStrideShorts = dPlane.rowStride / 2

            val cPlane = conf.planes[0]
            val cBuf = cPlane.buffer
            val cRowStride = cPlane.rowStride
            val cPixStride = cPlane.pixelStride

            // Les intrinsèques décrivent l'image caméra pleine résolution ; la carte de
            // profondeur est bien plus petite, d'où la mise à l'échelle.
            val ci = frame.camera.imageIntrinsics
            val sx = w.toFloat() / ci.imageDimensions[0]
            val sy = h.toFloat() / ci.imageDimensions[1]
            val fx = ci.focalLength[0] * sx
            val fy = ci.focalLength[1] * sy
            val cx = ci.principalPoint[0] * sx
            val cy = ci.principalPoint[1] * sy

            // Matrice caméra -> monde, appliquée à la main pour éviter une allocation
            // par point (Pose.transformPoint en créerait des dizaines de milliers).
            val m = FloatArray(16)
            frame.camera.pose.toMatrix(m, 0)

            val maxMm = (maxDepthM * 1000f).toInt()
            var added = 0

            for (v in 0 until h) {
                val dRow = v * dRowStrideShorts
                val cRow = v * cRowStride
                for (u in 0 until w) {
                    val confidence = cBuf.get(cRow + u * cPixStride).toInt() and 0xFF
                    if (confidence < MIN_CONFIDENCE) continue

                    val mm = dBuf.get(dRow + u).toInt() and 0xFFFF
                    if (mm <= 0 || mm > maxMm) continue

                    val z = mm / 1000f
                    // Repère caméra OpenGL : X droite, Y haut, -Z vers l'avant.
                    val px = z * (u - cx) / fx
                    val py = z * (cy - v) / fy
                    val pz = -z

                    val wx = m[0] * px + m[4] * py + m[8] * pz + m[12]
                    val wy = m[1] * px + m[5] * py + m[9] * pz + m[13]
                    val wz = m[2] * px + m[6] * py + m[10] * pz + m[14]

                    if (cloud.add(wx, wy, wz)) added++
                }
            }
            return added
        } finally {
            depth.close()
            conf.close()
        }
    }
}
