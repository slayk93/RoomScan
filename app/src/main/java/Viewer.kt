package com.tony.roomscan

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lecteur PLY.
 *
 * Il accepte plus que ce que RoomScan produit : binaire petit ou gros boutiste, ASCII,
 * et des propriétés supplémentaires (couleurs, normales) qu'il saute proprement grâce
 * aux décalages calculés depuis l'en-tête. Ça permet de relire un nuage retouché dans
 * CloudCompare ou MeshLab, pas seulement les exports d'origine.
 */
object PlyLoader {

    private fun typeSize(t: String): Int = when (t) {
        "char", "uchar", "int8", "uint8" -> 1
        "short", "ushort", "int16", "uint16" -> 2
        "int", "uint", "int32", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> throw IllegalArgumentException("Type PLY inconnu : $t")
    }

    fun load(ctx: Context, uri: Uri, onProgress: (Int, Int) -> Unit): VoxelCloud {
        val stream = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Fichier illisible")

        BufferedInputStream(stream, 1 shl 16).use { input ->
            val header = readHeader(input)

            var binary = false
            var little = true
            var count = -1
            var inVertex = false
            val propNames = ArrayList<String>()
            val propTypes = ArrayList<String>()

            for (l in header) {
                val t = l.split(Regex("\\s+"))
                when (t[0]) {
                    "format" -> {
                        binary = t[1].startsWith("binary")
                        little = t[1].contains("little")
                    }
                    "element" -> {
                        inVertex = t.size > 2 && t[1] == "vertex"
                        if (inVertex) count = t[2].toInt()
                    }
                    // Les propriétés "list" n'apparaissent que sur les faces, qu'on ignore.
                    "property" -> if (inVertex && t[1] != "list") {
                        propTypes.add(t[1])
                        propNames.add(t[2])
                    }
                }
            }

            if (count <= 0) throw IllegalStateException("Aucun sommet déclaré dans l'en-tête")
            val ix = propNames.indexOf("x")
            val iy = propNames.indexOf("y")
            val iz = propNames.indexOf("z")
            if (ix < 0 || iy < 0 || iz < 0) {
                throw IllegalStateException("Coordonnées x/y/z absentes du fichier")
            }

            // Voxel de 2 mm : assez fin pour ne rien perdre d'un nuage déjà échantillonné
            // à 1 cm ou plus, tout en réutilisant la déduplication existante.
            val cloud = VoxelCloud(0.002f)

            if (binary) {
                readBinary(input, cloud, count, propTypes, ix, iy, iz, little, onProgress)
            } else {
                readAscii(input, cloud, count, ix, iy, iz, onProgress)
            }
            onProgress(count, count)
            return cloud
        }
    }

    private fun readHeader(input: InputStream): List<String> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("En-tête PLY incomplet")
            when {
                b == '\n'.code -> {
                    val l = sb.toString().trim()
                    sb.setLength(0)
                    if (l.isNotEmpty()) {
                        if (lines.isEmpty() && l != "ply") {
                            throw IllegalStateException("Ce fichier n'est pas un PLY")
                        }
                        lines.add(l)
                        if (l == "end_header") return lines
                    }
                }
                b != '\r'.code -> sb.append(b.toChar())
            }
        }
    }

    private fun readBinary(
        input: InputStream, cloud: VoxelCloud, count: Int,
        types: List<String>, ix: Int, iy: Int, iz: Int,
        little: Boolean, onProgress: (Int, Int) -> Unit
    ) {
        val offsets = IntArray(types.size)
        var stride = 0
        for (i in types.indices) {
            offsets[i] = stride
            stride += typeSize(types[i])
        }

        val row = ByteArray(stride)
        val bb = ByteBuffer.wrap(row)
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        for (i in 0 until count) {
            if (!readFully(input, row)) {
                throw IllegalStateException("Fichier tronqué après $i points sur $count")
            }
            cloud.add(
                coord(bb, offsets[ix], types[ix]),
                coord(bb, offsets[iy], types[iy]),
                coord(bb, offsets[iz], types[iz])
            )
            if (i % 20000 == 0) onProgress(i, count)
        }
    }

    private fun coord(bb: ByteBuffer, off: Int, type: String): Float = when (type) {
        "double", "float64" -> bb.getDouble(off).toFloat()
        "float", "float32" -> bb.getFloat(off)
        else -> throw IllegalStateException("Coordonnée de type $type non gérée")
    }

    private fun readAscii(
        input: InputStream, cloud: VoxelCloud, count: Int,
        ix: Int, iy: Int, iz: Int, onProgress: (Int, Int) -> Unit
    ) {
        val reader = input.bufferedReader()
        var read = 0
        while (read < count) {
            val line = reader.readLine() ?: break
            val t = line.trim()
            if (t.isEmpty()) continue
            val cols = t.split(Regex("\\s+"))
            if (cols.size <= maxOf(ix, iy, iz)) continue
            cloud.add(cols[ix].toFloat(), cols[iy].toFloat(), cols[iz].toFloat())
            read++
            if (read % 20000 == 0) onProgress(read, count)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }
}

/**
 * Rendu du nuage chargé, vu par une caméra en orbite.
 *
 * Le rendu des points lui-même est délégué à PointCloudRenderer, exactement celui du
 * scanner : même VBO incrémental, même coloration par altitude. Seule la matrice de vue
 * change — elle vient d'une orbite pilotée au doigt plutôt que de la pose ARCore.
 */
class ViewerRenderer : GLSurfaceView.Renderer {

    private val points = PointCloudRenderer()
    private var lastCloud: VoxelCloud? = null

    @Volatile var cloud: VoxelCloud? = null
    @Volatile var yaw = 0.7f
    @Volatile var pitch = 0.5f
    @Volatile var distance = 6f
    @Volatile var pointSize = 4f

    val target = floatArrayOf(0f, 0f, 0f)

    private var aspect = 1f
    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val mvpM = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.063f, 0.071f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        points.createOnGlThread()
        lastCloud = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val c = cloud
        if (c !== lastCloud) {
            points.reset()
            lastCloud = c
        }
        if (c == null || c.size == 0) return

        points.sync(c)

        val cp = cos(pitch)
        val ex = target[0] + distance * cp * sin(yaw)
        val ey = target[1] + distance * sin(pitch)
        val ez = target[2] + distance * cp * cos(yaw)

        Matrix.setLookAtM(
            viewM, 0, ex, ey, ez,
            target[0], target[1], target[2], 0f, 1f, 0f
        )
        Matrix.perspectiveM(
            projM, 0, 50f, aspect,
            max(0.02f, distance * 0.01f), distance * 8f + 100f
        )
        Matrix.multiplyMM(mvpM, 0, projM, 0, viewM, 0)

        points.draw(mvpM, c.minY, c.maxY, pointSize)
    }

    /** Cadre la caméra sur le nuage : centre de gravité et rayon englobant. */
    fun frame(c: VoxelCloud) {
        if (c.size == 0) return
        val step = if (c.size > 50000) c.size / 50000 else 1
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        var i = 0
        while (i < c.size) {
            sx += c.xyz[i * 3]; sy += c.xyz[i * 3 + 1]; sz += c.xyz[i * 3 + 2]
            n++; i += step
        }
        target[0] = (sx / n).toFloat()
        target[1] = (sy / n).toFloat()
        target[2] = (sz / n).toFloat()

        var maxR = 0f
        i = 0
        while (i < c.size) {
            val dx = c.xyz[i * 3] - target[0]
            val dy = c.xyz[i * 3 + 1] - target[1]
            val dz = c.xyz[i * 3 + 2] - target[2]
            val r = sqrt(dx * dx + dy * dy + dz * dz)
            if (r > maxR) maxR = r
            i += step
        }
        distance = max(1f, maxR * 2.2f)
        yaw = 0.7f
        pitch = 0.5f
    }
}

/**
 * Visionneuse autonome : ouvre un .ply depuis le stockage et l'affiche.
 *
 * L'interface est construite en code plutôt qu'en XML pour n'ajouter qu'un seul fichier
 * au projet — pas de layout ni de binding supplémentaires.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private val renderer = ViewerRenderer()

    private var mode = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var sizeStep = 1

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) loadCloud(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }

        status = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "Ouvre un fichier .ply depuis Téléchargements/RoomScan"
        }

        val openBtn = Button(this).apply {
            text = "Ouvrir un .ply"
            setOnClickListener { openFile.launch(arrayOf("*/*")) }
        }
        val frameBtn = Button(this).apply {
            text = "Recadrer"
            setOnClickListener {
                renderer.cloud?.let { renderer.frame(it) }
            }
        }
        val sizeBtn = Button(this).apply {
            text = "Points"
            setOnClickListener {
                sizeStep = (sizeStep + 1) % 3
                renderer.pointSize = when (sizeStep) {
                    0 -> 2f
                    1 -> 4f
                    else -> 7f
                }
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(openBtn, lp)
            addView(frameBtn, LinearLayout.LayoutParams(lp))
            addView(sizeBtn, LinearLayout.LayoutParams(lp))
        }

        val root = FrameLayout(this)
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        )
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )
        setContentView(root)

        glView.setOnTouchListener { v, e -> handleTouch(v, e) }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Un doigt fait tourner, deux doigts zooment et déplacent le centre. */
    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    mode = 2
                    lastSpan = span(e)
                    lastX = (e.getX(0) + e.getX(1)) * 0.5f
                    lastY = (e.getY(0) + e.getY(1)) * 0.5f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    renderer.yaw -= dx * 0.008f
                    renderer.pitch = (renderer.pitch + dy * 0.008f).coerceIn(-1.5f, 1.5f)
                } else if (mode == 2 && e.pointerCount >= 2) {
                    val s = span(e)
                    if (lastSpan > 1f && s > 1f) {
                        renderer.distance = (renderer.distance * lastSpan / s)
                            .coerceIn(0.05f, 500f)
                    }
                    lastSpan = s

                    val mx = (e.getX(0) + e.getX(1)) * 0.5f
                    val my = (e.getY(0) + e.getY(1)) * 0.5f
                    pan(mx - lastX, my - lastY)
                    lastX = mx
                    lastY = my
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount <= 2) {
                    mode = 1
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(remaining)
                    lastY = e.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                v.performClick()
            }
        }
        return true
    }

    private fun span(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    /**
     * Déplace le point visé dans le plan de l'écran. L'échelle dépend de la distance,
     * sinon le déplacement paraîtrait trop lent de loin et incontrôlable de près.
     */
    private fun pan(dx: Float, dy: Float) {
        val yaw = renderer.yaw
        val pitch = renderer.pitch
        val cp = cos(pitch)

        // Direction de visée, de l'oeil vers la cible.
        val fx = -cp * sin(yaw)
        val fy = -sin(pitch)
        val fz = -cp * cos(yaw)

        // Droite de l'écran : perpendiculaire à la visée, dans le plan horizontal.
        val rx = cos(yaw)
        val rz = -sin(yaw)

        // Haut de l'écran : produit vectoriel droite x visée.
        val ux = -rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy

        val k = renderer.distance * 0.0022f
        renderer.target[0] += (-dx * rx + dy * ux) * k
        renderer.target[1] += (dy * uy) * k
        renderer.target[2] += (-dx * rz + dy * uz) * k
    }

    private fun loadCloud(uri: Uri) {
        status.text = "Lecture en cours…"
        thread {
            try {
                val cloud = PlyLoader.load(this, uri) { done, total ->
                    if (total > 0) {
                        val pct = done * 100 / total
                        runOnUiThread { status.text = "Lecture… $pct %" }
                    }
                }
                renderer.frame(cloud)
                renderer.cloud = cloud
                runOnUiThread {
                    val span = cloud.maxY - cloud.minY
                    status.text = "${cloud.size} points · ${"%.1f".format(span)} m de hauteur\n" +
                        "Un doigt : rotation · Deux doigts : zoom et déplacement"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "Échec de la lecture : ${t.message}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }
}
