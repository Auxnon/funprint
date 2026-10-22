package com.auxnon.funprint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Image -> 1-bit rows -> "cat printer" command stream. Mirrors the Python CLI. */
object Raster {
    const val WIDTH = 384

    // (energy, speed): higher energy / lower speed = darker
    const val ENERGY = 12000
    const val SPEED = 32

    fun packets(file: File): ByteArray = encode(dither(load(file)))

    private fun load(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PrintException("Not an image")
        // Decode at the smallest power-of-two size that still covers the printhead.
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WIDTH) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw PrintException("Couldn't decode the image")
        val matrix = exifMatrix(file) ?: return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun exifMatrix(file: File): Matrix? {
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                else -> return null
            }
        }
    }

    /** Scale to the printhead width on white, autocontrast, Floyd-Steinberg. Returns black pixels. */
    private fun dither(src: Bitmap): Array<BooleanArray> {
        val w = WIDTH
        val h = maxOf(1, (src.height.toLong() * w / src.width).toInt())
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(px.size) {
            val c = px[it]
            (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000f
        }
        autocontrast(g)

        val black = Array(h) { BooleanArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val old = g[i]
            val new = if (old < 128f) 0f else 255f
            black[y][x] = new == 0f
            val err = old - new
            if (x + 1 < w) g[i + 1] += err * 7 / 16
            if (y + 1 < h) {
                if (x > 0) g[i + w - 1] += err * 3 / 16
                g[i + w] += err * 5 / 16
                if (x + 1 < w) g[i + w + 1] += err * 1 / 16
            }
        }
        return black
    }

    /** Stretch levels so the darkest/lightest 1% clip, like PIL's autocontrast(cutoff=1). */
    private fun autocontrast(g: FloatArray) {
        val hist = IntArray(256)
        for (v in g) hist[v.toInt().coerceIn(0, 255)]++
        val cut = g.size / 100
        var lo = 0
        var acc = 0
        while (lo < 255) { acc += hist[lo]; if (acc > cut) break; lo++ }
        var hi = 255
        acc = 0
        while (hi > 0) { acc += hist[hi]; if (acc > cut) break; hi-- }
        if (hi <= lo) return
        val scale = 255f / (hi - lo)
        for (i in g.indices) g[i] = ((g[i] - lo) * scale).coerceIn(0f, 255f)
    }

    private fun encode(rows: Array<BooleanArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Protocol.cmd(0xA3, byteArrayOf(0)))                               // get device state
        out.write(Protocol.cmd(0xA4, byteArrayOf(0x33)))                            // quality
        out.write(Protocol.cmd(0xAF, byteArrayOf(ENERGY.toByte(), (ENERGY shr 8).toByte())))
        out.write(Protocol.cmd(0xBE, byteArrayOf(0)))                               // image mode
        out.write(Protocol.cmd(0xBD, byteArrayOf(SPEED.toByte())))
        out.write(Protocol.LATTICE_START)
        for (row in rows) {
            val bits = ByteArray(WIDTH / 8)
            for (x in row.indices) if (row[x]) bits[x shr 3] = (bits[x shr 3].toInt() or (1 shl (x and 7))).toByte()
            out.write(Protocol.cmd(0xA2, bits))
        }
        out.write(Protocol.feed(80))
        out.write(Protocol.cmd(0xBD, byteArrayOf(8)))
        out.write(Protocol.LATTICE_END)
        out.write(Protocol.cmd(0xA3, byteArrayOf(0)))
        return out.toByteArray()
    }
}

object Protocol {
    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    fun cmd(code: Int, data: ByteArray): ByteArray =
        byteArrayOf(0x51, 0x78, code.toByte(), 0, (data.size and 0xFF).toByte(), (data.size shr 8).toByte()) +
            data + byteArrayOf(crc8(data).toByte(), 0xFF.toByte())

    fun feed(lines: Int) = cmd(0xA1, byteArrayOf((lines and 0xFF).toByte(), (lines shr 8).toByte()))

    val LATTICE_START = cmd(0xA6, byteArrayOf(0xAA.toByte(), 0x55, 0x17, 0x38, 0x44, 0x5F, 0x5F, 0x5F, 0x44, 0x38, 0x2C))
    val LATTICE_END = cmd(0xA6, byteArrayOf(0xAA.toByte(), 0x55, 0x17, 0, 0, 0, 0, 0, 0, 0, 0x17))
}
