package io.github.dgproman.pihome.people

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code as a grid of modules, quiet zone included, ready to draw.
 *
 * Made here, on the phone, and nowhere else: the hub only ever hands over the
 * token, and never draws it into an image that something could cache or keep.
 */
class QrCode private constructor(
    /** Width and height, in modules. */
    val size: Int,
    private val dark: BooleanArray,
) {
    fun isDark(
        x: Int,
        y: Int,
    ): Boolean = dark[y * size + x]

    // The text it holds is a credential; so is this, if it ever printed its modules.
    override fun toString(): String = "QrCode(size=$size)"

    companion object {
        /**
         * Modules of quiet zone around the code, which the standard asks for. Fewer,
         * and some cameras fail to find the code against whatever is beside it.
         */
        const val QUIET_ZONE = 4

        /**
         * Encode [text]. Error correction M, as the web client draws it: it survives
         * a scuffed screen or a glare, and keeps a link of this length to a code a
         * phone reads from across a table.
         */
        fun encode(text: String): QrCode {
            val hints =
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to QUIET_ZONE,
                )
            // Asked for no size, ZXing makes one pixel per module.
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrCode(size, BooleanArray(size * size) { matrix.get(it % size, it / size) })
        }
    }
}
