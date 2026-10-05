package io.github.dgproman.pihome.connect

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What came of asking the person to scan a code. */
sealed interface Scan {
    data class Read(
        val text: String,
    ) : Scan

    /** The person backed out. Nothing to say about it. */
    data object Cancelled : Scan

    /** This phone cannot scan right now: no Play services, or its scanner is still being installed. */
    data object Unavailable : Scan
}

/** Reads an invitation's QR code. Tests hand the app one that reads what they need. */
fun interface InvitationScanner {
    suspend fun scan(context: Context): Scan
}

/**
 * Google's code scanner, which runs in Play services and shows its own camera
 * screen. The app never sees the camera, so needs no permission for it, and is
 * handed only the text of the code.
 */
class PlayServicesScanner : InvitationScanner {
    override suspend fun scan(context: Context): Scan {
        val options =
            GmsBarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        return suspendCancellableCoroutine { continuation ->
            GmsBarcodeScanning
                .getClient(context, options)
                .startScan()
                .addOnSuccessListener { continuation.resume(Scan.Read(it.rawValue.orEmpty())) }
                .addOnCanceledListener { continuation.resume(Scan.Cancelled) }
                .addOnFailureListener { continuation.resume(Scan.Unavailable) }
        }
    }
}
