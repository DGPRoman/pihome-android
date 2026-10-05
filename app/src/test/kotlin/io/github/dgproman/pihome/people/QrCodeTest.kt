package io.github.dgproman.pihome.people

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QrCodeTest {
    private val link = "http://192.168.1.20:5002/join#made-up-token-${"x".repeat(32)}"

    @Test
    fun `a camera reads the link back out of the code, at error correction M`() {
        val code = QrCode.encode(link)

        val read = QRCodeReader().decode(code.picture(), mapOf(DecodeHintType.PURE_BARCODE to true))

        assertEquals(link, read.text)
        assertEquals("M", read.resultMetadata[com.google.zxing.ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun `four modules of light all round`() {
        val code = QrCode.encode(link)
        val edge = 0 until QrCode.QUIET_ZONE
        val far = (code.size - QrCode.QUIET_ZONE) until code.size

        val dark =
            (0 until code.size).flatMap { a -> (edge + far).flatMap { b -> listOf(code.isDark(a, b), code.isDark(b, a)) } }

        assertFalse(dark.any { it })
        // The corner of the first finder pattern sits just inside it.
        assertEquals(true, code.isDark(QrCode.QUIET_ZONE, QrCode.QUIET_ZONE))
    }

    @Test
    fun `it never prints what it holds`() {
        assertFalse("made-up-token" in QrCode.encode(link).toString())
    }

    /** The code drawn as a camera would see it, four pixels a module. */
    private fun QrCode.picture(): BinaryBitmap {
        val scale = 4
        val width = size * scale
        val pixels = IntArray(width * width) { if (isDark(it % width / scale, it / width / scale)) 0xFF000000.toInt() else -1 }
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))
    }
}
