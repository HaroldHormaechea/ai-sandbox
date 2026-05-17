package com.aisandbox.android.ui.components

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.BarcodeFormat
import com.google.zxing.NotFoundException
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX preview + ZXing QR analyzer. UC04-1 onboarding viewfinder.
 *
 * <p>Why ZXing over ML Kit: ML Kit pulls in `play-services-mlkit-*`,
 * which depend on Google Play Services. UC04 AC29 forbids any
 * play-services dependency (the build is sideload-only). ZXing is a
 * pure-Java barcode library with zero GMS deps, and the CI's
 * `:android:dependencies | grep gms` check enforces this.
 *
 * <p>Pipeline: CameraX `ImageAnalysis` → YUV_420_888 frame → drop into a
 * [PlanarYUVLuminanceSource] (only the Y plane — discards chroma) →
 * [HybridBinarizer] → [MultiFormatReader.decode]. Decode runs on a
 * single-thread executor that strict-backpressures the camera (default
 * STRATEGY_KEEP_ONLY_LATEST), so a slow decode never queues frames.
 *
 * <p>`onQr` fires at most once per scan session — the analyzer uses an
 * [AtomicBoolean] guard so a rapid double-decode doesn't re-trigger
 * onboarding. Caller is responsible for halting the scanner once it
 * collects a valid payload.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onQr: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val triggered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    if (!enabled || triggered.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val raw = decodeQr(proxy)
                    proxy.close()
                    if (raw != null && triggered.compareAndSet(false, true)) {
                        coroutineScope.launchWhenSafe { onQr(raw) }
                    }
                }

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot bind camera use cases: $t")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

/**
 * Decode one CameraX frame. Returns the raw QR payload string, or null
 * if no QR was found in this frame. Caller MUST call [ImageProxy.close]
 * regardless of return value.
 */
@OptIn(ExperimentalGetImage::class)
private fun decodeQr(proxy: ImageProxy): String? {
    val image = proxy.image ?: return null
    if (image.format != android.graphics.ImageFormat.YUV_420_888) return null

    val yPlane = image.planes[0]
    val width = image.width
    val height = image.height
    val yBuffer = yPlane.buffer
    val yBytes = ByteArray(yBuffer.remaining())
    yBuffer.get(yBytes)

    // PlanarYUVLuminanceSource takes the Y plane only — chroma is
    // irrelevant for QR decode and the rowStride may not equal width on
    // some hardware, but for monochrome decode we tolerate the
    // padding.
    val source = PlanarYUVLuminanceSource(
        yBytes,
        yPlane.rowStride,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    val binary = BinaryBitmap(HybridBinarizer(source))

    val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        setHints(hints)
    }
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
 * Launch [block] on this scope but swallow cancellation noise so a
 * navigation away from the screen mid-decode doesn't crash. The caller
 * is the recompose scope, which is cancelled when the screen leaves
 * composition; we just want a fire-and-forget semantic for the single
 * onQr emission.
 */
private fun kotlinx.coroutines.CoroutineScope.launchWhenSafe(block: suspend () -> Unit) {
    kotlinx.coroutines.launch {
        try {
            block()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected when the screen leaves composition mid-emission
        }
    }
}

private const val TAG = "QrScanner"
