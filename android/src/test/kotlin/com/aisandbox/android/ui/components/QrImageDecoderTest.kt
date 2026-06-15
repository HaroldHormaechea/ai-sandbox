package com.aisandbox.android.ui.components

import com.aisandbox.android.net.QrPayload
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-83 — pure-JVM coverage for the still-image QR decode that backs the
 * "Read QR from file" enrollment path.
 *
 * <p>[decodeInviteCandidates] and [sampleSizeFor] are deliberately pure
 * functions over an ARGB `IntArray` (no [android.graphics.Bitmap], no
 * [android.content.Context]) precisely so QA can exercise the REAL ZXing
 * decode configuration off-device. We render genuine QR codes here with
 * ZXing's [QRCodeWriter] (the same library the production path decodes
 * with), rasterise them to an ARGB buffer exactly as
 * [decodeInviteFromUri] does via `Bitmap.getPixels`, then assert the
 * production decode round-trips them.
 *
 * <p>This is the camera-free contract: if the camera path and the file
 * path share one decoder (Criterion 3), a payload encoded into a QR image
 * MUST come back out byte-identical through [decodeInviteCandidates].
 */
class QrImageDecoderTest {

    private val validInvite =
        """{"u":"https://example.com:12410","t":"$TOKEN_64","exp":"2026-05-17T10:10:00Z","pin":"${"fa".repeat(32)}"}"""

    // ── decodeInviteCandidates: round-trip a real rendered QR ──────────────

    @Test
    fun `decodes a rendered invite QR back to its exact payload`() {
        val img = renderQr(validInvite)

        val candidates = decodeInviteCandidates(img.pixels, img.width, img.height)

        assertThat(candidates).containsExactly(validInvite)
        // The decoded string must also survive production invite validation —
        // this is the "decode → onQrPayload" contract the file path relies on.
        assertThat(QrPayload.parse(candidates.single()).isSuccess).isTrue
    }

    @Test
    fun `decodes a short non-invite QR string verbatim (validation is the caller's job)`() {
        // decodeInviteCandidates only decodes; invite validation happens in
        // the ViewModel. A QR holding arbitrary text must still round-trip so
        // the caller can decide it is not a valid invite (UC-61 scoping).
        val img = renderQr("not-an-ai-sandbox-invite")

        assertThat(decodeInviteCandidates(img.pixels, img.width, img.height))
            .containsExactly("not-an-ai-sandbox-invite")
    }

    // ── decodeInviteCandidates: no QR present ──────────────────────────────

    @Test
    fun `returns empty list for an image with no QR code`() {
        val width = 256
        val height = 256
        val allWhite = IntArray(width * height) { WHITE }

        assertThat(decodeInviteCandidates(allWhite, width, height)).isEmpty()
    }

    // ── decodeInviteCandidates: multiple QR codes in one image ─────────────

    @Test
    fun `decodes every distinct QR in a multi-QR image`() {
        val left = renderQr(validInvite)
        val right = renderQr("second-distinct-qr-payload")

        // Lay the two QR images side by side on a white canvas with a wide
        // quiet gap so the multi-reader can isolate both finder patterns.
        val gap = 96
        val canvasW = left.width + gap + right.width
        val canvasH = maxOf(left.height, right.height)
        val canvas = IntArray(canvasW * canvasH) { WHITE }
        blit(canvas, canvasW, left, 0, 0)
        blit(canvas, canvasW, right, left.width + gap, 0)

        val candidates = decodeInviteCandidates(canvas, canvasW, canvasH)

        assertThat(candidates)
            .containsExactlyInAnyOrder(validInvite, "second-distinct-qr-payload")
    }

    @Test
    fun `de-duplicates identical repeated QR codes`() {
        val a = renderQr(validInvite)
        val b = renderQr(validInvite)
        val gap = 96
        val canvasW = a.width + gap + b.width
        val canvasH = maxOf(a.height, b.height)
        val canvas = IntArray(canvasW * canvasH) { WHITE }
        blit(canvas, canvasW, a, 0, 0)
        blit(canvas, canvasW, b, a.width + gap, 0)

        // Two copies of the same payload de-dupe to a single candidate.
        assertThat(decodeInviteCandidates(canvas, canvasW, canvasH))
            .containsExactly(validInvite)
    }

    // ── decodeInviteCandidates: defensive guards ───────────────────────────

    @Test
    fun `returns empty for degenerate dimensions or undersized buffers`() {
        assertThat(decodeInviteCandidates(IntArray(0), 0, 0)).isEmpty()
        assertThat(decodeInviteCandidates(IntArray(4), -1, 4)).isEmpty()
        assertThat(decodeInviteCandidates(IntArray(4), 4, -1)).isEmpty()
        // Buffer shorter than width*height must not throw — guard returns empty.
        assertThat(decodeInviteCandidates(IntArray(3), 4, 4)).isEmpty()
    }

    // ── sampleSizeFor: power-of-two downsample boundaries ──────────────────

    @Test
    fun `sampleSizeFor is 1 when both edges are at or below the cap`() {
        assertThat(sampleSizeFor(2048, 2048, 2048)).isEqualTo(1)
        assertThat(sampleSizeFor(100, 100, 2048)).isEqualTo(1)
        assertThat(sampleSizeFor(2048, 10, 2048)).isEqualTo(1)
    }

    @Test
    fun `sampleSizeFor doubles once just past the cap`() {
        assertThat(sampleSizeFor(2049, 1000, 2048)).isEqualTo(2)
        assertThat(sampleSizeFor(1000, 4096, 2048)).isEqualTo(2)
    }

    @Test
    fun `sampleSizeFor grows to the next power of two for large images`() {
        // 4096 → /2 = 2048 (≤cap) ⇒ 2
        assertThat(sampleSizeFor(4096, 4096, 2048)).isEqualTo(2)
        // 8192 → 4096 → 2048 ⇒ 4
        assertThat(sampleSizeFor(8192, 8192, 2048)).isEqualTo(4)
        // 8193 still needs 8 (8193→4096→2048 is 4; but 8193/2=4096,/2=2048 ⇒ 4)
        assertThat(sampleSizeFor(8193, 100, 2048)).isEqualTo(4)
        // 16384 → 8192 → 4096 → 2048 ⇒ 8
        assertThat(sampleSizeFor(16384, 16384, 2048)).isEqualTo(8)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** A rendered QR as an ARGB pixel buffer (black modules on white). */
    private class QrImage(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Render [content] into a real QR code via ZXing's [QRCodeWriter] and
     * rasterise it to an ARGB buffer exactly the way the production
     * [decodeInviteFromUri] does (`Bitmap.getPixels` → ARGB ints). High
     * error correction + a generous module size keep the synthetic render
     * comfortably decodable.
     */
    private fun renderQr(content: String, size: Int = 360): QrImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 4,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return QrImage(pixels, w, h)
    }

    /** Copy [src] into [dst] (a [dstWidth]-wide canvas) at the given offset. */
    private fun blit(dst: IntArray, dstWidth: Int, src: QrImage, offX: Int, offY: Int) {
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                dst[(offY + y) * dstWidth + (offX + x)] = src.pixels[y * src.width + x]
            }
        }
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        // Obvious placeholder token — 63 chars of [A-Za-z0-9._-]; NOT real key
        // material. Mirrors QrPayloadTest.TOKEN_64 so AuditNoSecretsTest stays quiet.
        private const val TOKEN_64 =
            "abcd1234.fake-test-token-not-a-real-key.0123456789ab-cdefABCDEFX"
    }
}
