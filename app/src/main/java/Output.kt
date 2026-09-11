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

/**
 * Aplatit le nuage en plan d'étage.
 *
 * Le principe : ne garder que les points situés dans une bande de hauteur au-dessus du
 * sol — typiquement 0,4 m à 2,0 m. En dessous on ne verrait que le sol, au-dessus que le
 * plafond ; entre les deux se trouvent murs, meubles et rayonnages, c'est-à-dire tout ce
 * qu'un plan doit montrer. Chaque cellule de la grille compte ses points ; au-delà d'un
 * seuil elle est déclarée occupée, ce qui élimine les mesures isolées et bruitées.
 */
object FloorPlanBuilder {

    data class Params(
        val cellSize: Float = 0.05f,
        val bandLow: Float = 0.40f,
        val bandHigh: Float = 2.00f,
        val minHits: Int = 3
    )

    class Result(
        val grid: BooleanArray,
        val cols: Int,
        val rows: Int,
        val originX: Float,
        val originZ: Float,
        val cellSize: Float,
        val occupiedCells: Int
    )

    fun build(cloud: VoxelCloud, p: Params = Params()): Result? {
        if (cloud.size == 0) return null
        val floorY = cloud.estimateFloorY()
        val loY = floorY + p.bandLow
        val hiY = floorY + p.bandHigh

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var kept = 0
        for (i in 0 until cloud.size) {
            val y = cloud.xyz[i * 3 + 1]
            if (y < loY || y > hiY) continue
            val x = cloud.xyz[i * 3]
            val z = cloud.xyz[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            kept++
        }
        if (kept == 0) return null

        val cols = (((maxX - minX) / p.cellSize).toInt() + 2).coerceAtMost(4000)
        val rows = (((maxZ - minZ) / p.cellSize).toInt() + 2).coerceAtMost(4000)
        val counts = IntArray(cols * rows)

        for (i in 0 until cloud.size) {
            val y = cloud.xyz[i * 3 + 1]
            if (y < loY || y > hiY) continue
            val cx = ((cloud.xyz[i * 3] - minX) / p.cellSize).toInt()
            val cz = ((cloud.xyz[i * 3 + 2] - minZ) / p.cellSize).toInt()
            if (cx in 0 until cols && cz in 0 until rows) counts[cz * cols + cx]++
        }

        var occupied = 0
        val grid = BooleanArray(cols * rows)
        for (i in counts.indices) {
            if (counts[i] >= p.minHits) { grid[i] = true; occupied++ }
        }

        return Result(grid, cols, rows, minX, minZ, p.cellSize, occupied)
    }

    /** Rend le plan en bitmap, avec grille métrique et trajectoire du scan. */
    fun render(r: Result, trajectory: FloatArray, pxPerCell: Int = 3): Bitmap {
        val margin = 40
        val w = r.cols * pxPerCell + margin * 2
        val h = r.rows * pxPerCell + margin * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Grille du mètre.
        paint.color = Color.parseColor("#E4E7EA")
        paint.strokeWidth = 1f
        val cellsPerMeter = (1f / r.cellSize)
        var g = 0f
        while (g < r.cols) {
            val x = margin + g * pxPerCell
            c.drawLine(x, margin.toFloat(), x, (h - margin).toFloat(), paint)
            g += cellsPerMeter
        }
        g = 0f
        while (g < r.rows) {
            val y = margin + g * pxPerCell
            c.drawLine(margin.toFloat(), y, (w - margin).toFloat(), y, paint)
            g += cellsPerMeter
        }

        // Cellules occupées, fusionnées en segments horizontaux.
        paint.color = Color.parseColor("#20262B")
        paint.style = Paint.Style.FILL
        for (row in 0 until r.rows) {
            var col = 0
            while (col < r.cols) {
                if (!r.grid[row * r.cols + col]) { col++; continue }
                var end = col
                while (end + 1 < r.cols && r.grid[row * r.cols + end + 1]) end++
                c.drawRect(
                    (margin + col * pxPerCell).toFloat(),
                    (margin + row * pxPerCell).toFloat(),
                    (margin + (end + 1) * pxPerCell).toFloat(),
                    (margin + (row + 1) * pxPerCell).toFloat(),
                    paint
                )
                col = end + 1
            }
        }

        // Trajectoire parcourue.
        if (trajectory.size >= 6) {
            paint.color = Color.parseColor("#D8543B")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            val path = android.graphics.Path()
            for (i in 0 until trajectory.size / 3) {
                val px = margin + (trajectory[i * 3] - r.originX) / r.cellSize * pxPerCell
                val py = margin + (trajectory[i * 3 + 2] - r.originZ) / r.cellSize * pxPerCell
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }

        // Échelle.
        paint.color = Color.parseColor("#20262B")
        paint.strokeWidth = 3f
        val barPx = cellsPerMeter * pxPerCell
        val by = (h - margin / 2).toFloat()
        c.drawLine(margin.toFloat(), by, margin + barPx, by, paint)
        paint.textSize = 20f
        c.drawText("1 m", margin.toFloat(), by - 8f, paint)

        return bmp
    }

    /** Variante vectorielle, éditable dans Inkscape ou Illustrator. */
    fun toSvg(r: Result): String {
        val s = StringBuilder(1 shl 16)
        val wM = r.cols * r.cellSize
        val hM = r.rows * r.cellSize
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
            .append("width=\"").append(wM * 100).append("mm\" ")
            .append("height=\"").append(hM * 100).append("mm\" ")
            .append("viewBox=\"0 0 ").append(r.cols).append(" ").append(r.rows).append("\">\n")
        s.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
        s.append("<g fill=\"#20262b\">\n")
        for (row in 0 until r.rows) {
            var col = 0
            while (col < r.cols) {
                if (!r.grid[row * r.cols + col]) { col++; continue }
                var end = col
                while (end + 1 < r.cols && r.grid[row * r.cols + end + 1]) end++
                s.append("<rect x=\"").append(col).append("\" y=\"").append(row)
                    .append("\" width=\"").append(end - col + 1).append("\" height=\"1\"/>\n")
                col = end + 1
            }
        }
        s.append("</g>\n</svg>\n")
        return s.toString()
    }
}

/** Écrit les résultats dans Téléchargements/RoomScan via MediaStore. */
object Exporter {

    private const val DIR = "Download/RoomScan"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun open(ctx: Context, name: String, mime: String): Pair<OutputStream, String>? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = resolver.openOutputStream(uri) ?: return null
        return out to uri.toString()
    }

    private fun finish(ctx: Context, uriString: String) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(android.net.Uri.parse(uriString), values, null, null)
    }

    /**
     * PLY binaire little-endian. En ASCII, un million de points pèserait ~40 Mo et
     * mettrait une éternité à s'écrire ; en binaire c'est 12 octets par point.
     * Lisible par CloudCompare, MeshLab et Blender.
     */
    fun exportPly(ctx: Context, cloud: VoxelCloud): String {
        val name = "scan-${stamp()}.ply"
        val (out, uri) = open(ctx, name, "application/octet-stream")
            ?: throw IllegalStateException("Impossible de créer le fichier")

        BufferedOutputStream(out, 1 shl 16).use { bos ->
            val header = buildString {
                append("ply\n")
                append("format binary_little_endian 1.0\n")
                append("comment généré par RoomScan, voxel ")
                append(cloud.voxelSize).append(" m\n")
                append("element vertex ").append(cloud.size).append("\n")
                append("property float x\nproperty float y\nproperty float z\n")
                append("end_header\n")
            }
            bos.write(header.toByteArray(Charsets.US_ASCII))

            val chunkPoints = 8192
            val buf = ByteBuffer.allocate(chunkPoints * 12).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < cloud.size) {
                val n = minOf(chunkPoints, cloud.size - i)
                buf.clear()
                for (k in 0 until n) {
                    val o = (i + k) * 3
                    buf.putFloat(cloud.xyz[o])
                    buf.putFloat(cloud.xyz[o + 1])
                    buf.putFloat(cloud.xyz[o + 2])
                }
                bos.write(buf.array(), 0, n * 12)
                i += n
            }
        }
        finish(ctx, uri)
        return "$DIR/$name"
    }

    fun exportPng(ctx: Context, bmp: Bitmap): String {
        val name = "plan-${stamp()}.png"
        val (out, uri) = open(ctx, name, "image/png")
            ?: throw IllegalStateException("Impossible de créer le fichier")
        out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        finish(ctx, uri)
        return "$DIR/$name"
    }

    fun exportSvg(ctx: Context, svg: String): String {
        val name = "plan-${stamp()}.svg"
        val (out, uri) = open(ctx, name, "image/svg+xml")
            ?: throw IllegalStateException("Impossible de créer le fichier")
        out.use { it.write(svg.toByteArray(Charsets.UTF_8)) }
        finish(ctx, uri)
        return "$DIR/$name"
    }
}
