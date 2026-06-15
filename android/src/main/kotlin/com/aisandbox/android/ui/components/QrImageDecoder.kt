package com.aisandbox.android.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.util.EnumMap

/**
 * Shared QR decode for both the live camera ([QrScanner]) and the UC-83
 * "read QR from file" enrollment path.
 *
 * <p>This is the single owner of the ZXing decode configuration
 * (`QR_CODE` only + `TRY_HARDER`), so the camera frame path and the
 * still-image path decode QR codes *identically* — there is no second QR
 * library and no divergent hint set (UC-83 Criterion 3).
 *
 * <p>Library choice mirrors [QrScanner]: ZXing core, never ML Kit /
 * `play-services-*` (the CI `:android:dependencies | grep gms` gate
 * forbids it). The file path uses [RGBLuminanceSource] +
 * [GenericMultipleBarcodeReader] (multi-QR aware, falls back to a single
 * decode) instead of the camera's [com.google.zxing.PlanarYUVLuminanceSource].
 *
 * <p>The pixel-level decode ([decodeInviteCandidates]) is a pure JVM
 * function over an ARGB `IntArray`, so QA can unit-test it without a real
 * [android.graphics.Bitmap] or a device.
 */

/** Hints shared by every decode: QR format only, exhaustive search. */
private fun qrHints(): Map<DecodeHintType, Any> =
    EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        put(DecodeHintType.TRY_HARDER, true)
    }

/**
 * Decode one [LuminanceSource] for a single QR. Returns the raw payload
 * string, or null if no QR was found. This is the seam the camera frame
 * path delegates to ([QrScanner.decodeQr]) so both paths share one
 * [MultiFormatReader] configuration.
 */
internal fun decode(source: LuminanceSource): String? {
    val binary = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader().apply { setHints(qrHints()) }
    return try {
        reader.decode(binary).text
    } catch (_: NotFoundException) {
        null
    } catch (t: Throwable) {
        Log.v(TAG, "ZXing decode skipped: ${t.javaClass.simpleName}")
        null
    } finally {
        reader.reset()
    }
}

/**
 * Pure-JVM decode of an ARGB pixel buffer. Attempts to read EVERY QR code
 * in the image via [GenericMultipleBarcodeReader], falling back to a
 * single [MultiFormatReader.decode] (the multi reader can miss a lone,
 * centred code a direct decode finds). Returns every decoded string,
 * de-duplicated; empty when nothing decodes.
 *
 * <p>Unit-testable without Android: [RGBLuminanceSource] takes a plain
 * `IntArray`, so QA can feed a synthetic / rendered QR bitmap's pixels
 * directly. Returning *all* candidates lets the caller pick the first one
 * that parses as a valid invite (UC-83 multi-QR edge case) rather than
 * silently committing to the wrong code.
 */
internal fun decodeInviteCandidates(pixels: IntArray, width: Int, height: Int): List<String> {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
    val source = RGBLuminanceSource(width, height, pixels)
    val binary = BinaryBitmap(HybridBinarizer(source))
    val hints = qrHints()
    return try {
        val multi = GenericMultipleBarcodeReader(MultiFormatReader().apply { setHints(hints) })
        multi.decodeMultiple(binary, hints).mapNotNull { it.text }.distinct()
    } catch (_: NotFoundException) {
        decodeSingle(binary, hints)
    } catch (t: Throwable) {
        Log.v(TAG, "ZXing multi decode skipped: ${t.javaClass.simpleName}")
        decodeSingle(binary, hints)
    }
}

private fun decodeSingle(binary: BinaryBitmap, hints: Map<DecodeHintType, Any>): List<String> {
    val reader = MultiFormatReader().apply { setHints(hints) }
    return try {
        listOf(reader.decode(binary).text)
    } catch (_: NotFoundException) {
        emptyList()
    } catch (t: Throwable) {
        Log.v(TAG, "ZXing single decode skipped: ${t.javaClass.simpleName}")
        emptyList()
    } finally {
        reader.reset()
    }
}

/**
 * Outcome of decoding a still image picked from storage.
 *
 * - [Candidates] — the file was read and decoded; `payloads` holds every
 *   QR string found (possibly empty when the image has no QR at all).
 * - [Unreadable] — the file could not be opened / decoded into a bitmap
 *   (corrupt, not an image, OOM-guarded too large). Distinct from "read
 *   fine but no QR" so the caller can render the right error (Criterion 5).
 */
sealed interface QrDecodeResult {
    data class Candidates(val payloads: List<String>) : QrDecodeResult

    data object Unreadable : QrDecodeResult
}

/**
 * Android entry point for UC-83: decode the QR(s) from a user-picked image
 * [uri]. Reads the image via [android.content.ContentResolver], downsamples
 * it so a huge picture can't OOM the decode (caps the longest edge at
 * ~[MAX_EDGE_PX]; Criterion 5), then delegates to the pure
 * [decodeInviteCandidates].
 *
 * <p>Does NOT call `takePersistableUriPermission` — the SAF read grant is
 * valid for the lifetime of this call, which is all we need; we never
 * persist the Uri.
 */
internal fun decodeInviteFromUri(context: Context, uri: Uri): QrDecodeResult {
    val resolver = context.contentResolver
    // First pass: bounds only, so we can compute a downsample factor
    // without decoding the full bitmap into memory. NOTE: with
    // inJustDecodeBounds = true, BitmapFactory.decodeStream returns null
    // BY DESIGN (it only fills outWidth/outHeight) — so the null check has
    // to be on the *stream*, not on the decode return, otherwise every
    // image is misreported as Unreadable.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = try {
        resolver.openInputStream(uri)
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot open image: ${t.javaClass.simpleName}")
        return QrDecodeResult.Unreadable
    } ?: return QrDecodeResult.Unreadable
    try {
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot read image bounds: ${t.javaClass.simpleName}")
        return QrDecodeResult.Unreadable
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return QrDecodeResult.Unreadable

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
    }
    val bitmap = try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return QrDecodeResult.Unreadable
    } catch (t: Throwable) {
        // OutOfMemoryError included — never crash the screen on a hostile file.
        Log.w(TAG, "Cannot decode image bitmap: ${t.javaClass.simpleName}")
        return QrDecodeResult.Unreadable
    } ?: return QrDecodeResult.Unreadable

    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        QrDecodeResult.Candidates(decodeInviteCandidates(pixels, width, height))
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot read image pixels: ${t.javaClass.simpleName}")
        QrDecodeResult.Unreadable
    } finally {
        bitmap.recycle()
    }
}

/**
 * Largest power-of-two `inSampleSize` that keeps both edges at or below
 * [maxEdge]. Pure arithmetic so it is unit-testable.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w > maxEdge || h > maxEdge) {
        sample *= 2
        w /= 2
        h /= 2
    }
    return sample
}

/** Cap on the longest bitmap edge fed to the decoder (memory bound). */
private const val MAX_EDGE_PX = 2048

private const val TAG = "QrImageDecoder"
