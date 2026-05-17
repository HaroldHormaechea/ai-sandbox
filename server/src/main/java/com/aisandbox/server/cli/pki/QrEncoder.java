package com.aisandbox.server.cli.pki;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Tiny façade over ZXing's {@link QRCodeWriter} used by
 * {@code aisandboxctl client invite} (UC04 § B3) to render the
 * {@code {u, t, exp, pin}} JSON payload as either:
 *
 * <ul>
 *   <li>An ASCII block-art QR on stdout (TTY case — operator scans the
 *       terminal directly), or</li>
 *   <li>A 512×512 PNG written to a path passed via {@code --out} (non-TTY
 *       case — operator pipes the QR into a script or saves it).</li>
 * </ul>
 *
 * <p>QR parameters: error-correction level {@code Q} (~25 % redundancy —
 * conservative without burning capacity), 4-module quiet zone, UTF-8
 * encoding hint so the {@code exp} ISO-8601 string and {@code pin}
 * sha256 hex round-trip cleanly even though they're pure ASCII.
 *
 * <p>Reuses the {@code com.google.zxing:core} dep that the
 * {@code :android} module already pins (libs.versions.toml). The
 * implementation is stateless — every method is static.
 */
public final class QrEncoder {

    /** Default PNG output size when {@link #writePng(String, Path)} is used. */
    public static final int DEFAULT_PNG_SIZE = 512;

    /** Quiet-zone modules around the QR (4 is the spec-minimum). */
    public static final int QUIET_ZONE = 4;

    private QrEncoder() {}

    /**
     * Encode {@code payload} as a QR and write it to {@code out} as
     * UTF-8 block-art, one row per two terminal-cell rows (the top half
     * of each row uses {@code ▀} so the QR is square in a normal cell
     * aspect ratio).
     */
    public static void writeAscii(String payload, PrintStream out) throws WriterException {
        BitMatrix matrix = encode(payload, 0, 0);
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        StringBuilder sb = new StringBuilder((w + 1) * (h / 2 + 2));
        // Top white margin row.
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x++) {
                boolean top = matrix.get(x, y);
                boolean bot = (y + 1 < h) && matrix.get(x, y + 1);
                sb.append(blockChar(top, bot));
            }
            sb.append('\n');
        }
        out.print(sb);
        out.flush();
    }

    /**
     * Encode {@code payload} as a {@link #DEFAULT_PNG_SIZE}-pixel-square
     * PNG and write it to {@code path}. Parent directories are created
     * if absent.
     */
    public static void writePng(String payload, Path path) throws IOException, WriterException {
        writePng(payload, path, DEFAULT_PNG_SIZE);
    }

    /** Sized PNG variant. */
    public static void writePng(String payload, Path path, int sizePx) throws IOException, WriterException {
        BitMatrix matrix = encode(payload, sizePx, sizePx);
        BufferedImage img = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                img.setRGB(x, y, matrix.get(x, y) ? 0x00000000 : 0xFFFFFFFF);
            }
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (OutputStream os = Files.newOutputStream(path)) {
            ImageIO.write(img, "PNG", os);
        }
    }

    private static BitMatrix encode(String payload, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        return new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, width, height, hints);
    }

    /**
     * Map a 2-pixel vertical run to a Unicode half-block. The combinations
     * are:
     *
     * <pre>
     *   top dark, bot dark  → '█'  (full block)
     *   top dark, bot light → '▀'  (upper half)
     *   top light, bot dark → '▄'  (lower half)
     *   top light, bot light → ' ' (space)
     * </pre>
     *
     * Renders correctly in any modern terminal with a monospace font that
     * carries the U+2580 block-element range — all modern emulators do.
     */
    private static char blockChar(boolean top, boolean bot) {
        if (top && bot) {
            return '█';
        }
        if (top) {
            return '▀';
        }
        if (bot) {
            return '▄';
        }
        return ' ';
    }
}
