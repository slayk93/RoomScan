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

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var voxelCm = 3
    private var maxDepth = 5.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.voxelSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                voxelCm = progress + 1
                b.voxelValue.text = "$voxelCm cm"
            }
        })

        b.depthSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                maxDepth = 2.0f + progress * 0.5f
                b.depthValue.text = String.format("%.1f m", maxDepth)
            }
        })

        b.startBtn.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_VOXEL, voxelCm / 100f)
                putExtra(ScanActivity.EXTRA_DEPTH, maxDepth)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        checkSupport()
    }

    private fun checkSupport() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            b.root.postDelayed({ checkSupport() }, 200)
            return
        }
        if (!availability.isSupported) {
            b.statusText.text = "Cet appareil n'est pas compatible ARCore. Le scan ne fonctionnera pas."
            b.startBtn.isEnabled = false
        } else {
            b.statusText.text = "Conseils de prise de vue : avance à vitesse de marche lente, " +
                "balaie latéralement plutôt que de pointer droit devant, et évite les murs nus, " +
                "les vitres et les sols brillants — la profondeur est déduite du mouvement, " +
                "elle a besoin de texture pour exister."
        }
    }

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}

class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VOXEL = "voxel"
        const val EXTRA_DEPTH = "depth"
        private const val CAMERA_REQUEST = 101
    }

    private lateinit var b: ActivityScanBinding
    private lateinit var cloud: VoxelCloud
    private lateinit var renderer: ScanRenderer

    private var session: Session? = null
    private var installRequested = false
    private var lastHudUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScanBinding.inflate(layoutInflater)
        setContentView(b.root)

        cloud = VoxelCloud(intent.getFloatExtra(EXTRA_VOXEL, 0.03f))
        renderer = ScanRenderer(cloud) { stats -> postHud(stats) }
        renderer.maxDepthM = intent.getFloatExtra(EXTRA_DEPTH, 5.0f)

        b.surface.preserveEGLContextOnPause = true
        b.surface.setEGLContextClientVersion(2)
        b.surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        b.surface.setRenderer(renderer)
        b.surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        b.recordBtn.setOnClickListener {
            renderer.recording = !renderer.recording
            b.recordBtn.text = if (renderer.recording) "Mettre en pause" else "Enregistrer"
        }

        b.resetBtn.setOnClickListener {
            renderer.pendingReset = true
            Toast.makeText(this, "Nuage effacé", Toast.LENGTH_SHORT).show()
        }

        b.export3dBtn.setOnClickListener { export(threeD = true) }
        b.export2dBtn.setOnClickListener { export(threeD = false) }
    }

    private fun postHud(stats: ScanRenderer.Stats) {
        val now = System.currentTimeMillis()
        if (now - lastHudUpdate < 200) return
        lastHudUpdate = now
        runOnUiThread {
            val state = if (stats.recording) "ENREGISTREMENT" else "en pause"
            b.hud.text = "$state\n" +
                "${stats.points} points  (+${stats.lastAdded})\n" +
                stats.tracking
        }
    }

    private fun export(threeD: Boolean) {
        if (cloud.size == 0) {
            Toast.makeText(this, "Rien à exporter — lance d'abord un enregistrement", Toast.LENGTH_SHORT).show()
            return
        }
        val wasRecording = renderer.recording
        renderer.recording = false
        renderer.frozen = true
        b.recordBtn.text = "Enregistrer"
        Toast.makeText(this, "Export en cours…", Toast.LENGTH_SHORT).show()

        val traj = renderer.trajectorySnapshot()
        thread {
            val message = try {
                if (threeD) {
                    "Écrit : " + Exporter.exportPly(this, cloud)
                } else {
                    val plan = FloorPlanBuilder.build(cloud)
                    if (plan == null) {
                        "Pas assez de points dans la bande de hauteur pour un plan"
                    } else {
                        val png = Exporter.exportPng(this, FloorPlanBuilder.render(plan, traj))
                        val svg = Exporter.exportSvg(this, FloorPlanBuilder.toSvg(plan))
                        "Écrit : $png et $svg"
                    }
                }
            } catch (t: Throwable) {
                "Échec de l'export : ${t.message}"
            }
            runOnUiThread {
                renderer.frozen = false
                if (wasRecording) {
                    renderer.recording = true
                    b.recordBtn.text = "Mettre en pause"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST
            )
            return
        }
        if (session == null && !createSession()) return

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            fail("Caméra indisponible. Ferme les autres applications qui l'utilisent.")
            session = null
            return
        }
        b.surface.onResume()
    }

    override fun onPause() {
        super.onPause()
        b.surface.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {}
            }

            val s = Session(this)
            val config = s.config

            if (!s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                fail("Cet appareil ne fournit pas de profondeur brute via ARCore.")
                s.close()
                return false
            }
            config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
            config.focusMode = Config.FocusMode.AUTO
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            s.configure(config)

            session = s
            renderer.session = s
            return true
        } catch (e: UnavailableException) {
            fail("ARCore indisponible : ${e.message}")
            return false
        } catch (e: Exception) {
            fail("Impossible de créer la session : ${e.message}")
            return false
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST && !hasCameraPermission()) {
            fail("L'accès caméra est nécessaire pour scanner.")
            finish()
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
