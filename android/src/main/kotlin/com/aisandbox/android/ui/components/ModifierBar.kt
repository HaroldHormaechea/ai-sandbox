package com.aisandbox.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aisandbox.android.ui.theme.Accent
import com.aisandbox.android.ui.theme.AccentContainer
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnAccent
import com.aisandbox.android.ui.theme.OnAccentContainer
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.SurfaceHigh

/**
 * UC04 § Components — docked modifier bar that floats above the system
 * soft keyboard (AC15). Tiles in row order:
 *
 * <pre>
 *   [⌘ tmux] [Ctrl] [Alt] [Esc] [Tab] [↑] [↓] [←] [→] [Fn]
 * </pre>
 *
 * Modifier tiles are **sticky-one-shot**: tap to arm (visual flips to
 * accent-on-accent), then the next character gets the modifier applied
 * and the tile clears. Same idea for the tmux prefix tile.
 *
 * <p>Mouse-mode-aware gesture routing (AC19) lives in the Terminal
 * pane composable; this bar is purely an input keyboard.
 *
 * <p>For v0.1 the bar is docked only — the floating / collapsible
 * variants the design exposes are operator design-time variations not
 * required for ship.
 */
@Composable
fun ModifierBar(
    onKey: (KeyEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tmuxArmed by remember { mutableStateOf(false) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var fnVisible by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceHigh,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tile(
                label = "⌘ tmux",
                armed = tmuxArmed,
                onTap = {
                    tmuxArmed = !tmuxArmed
                    if (tmuxArmed) onKey(KeyEvent.TmuxArmed) else onKey(KeyEvent.TmuxDisarmed)
                },
            )
            Tile("Ctrl", ctrlArmed) { ctrlArmed = !ctrlArmed; onKey(if (ctrlArmed) KeyEvent.CtrlArmed else KeyEvent.CtrlDisarmed) }
            Tile("Alt", altArmed) { altArmed = !altArmed; onKey(if (altArmed) KeyEvent.AltArmed else KeyEvent.AltDisarmed) }
            Tile("Esc") { onKey(KeyEvent.Escape) }
            Tile("Tab") { onKey(KeyEvent.Tab) }
            Tile("↑") { onKey(KeyEvent.ArrowUp) }
            Tile("↓") { onKey(KeyEvent.ArrowDown) }
            Tile("←") { onKey(KeyEvent.ArrowLeft) }
            Tile("→") { onKey(KeyEvent.ArrowRight) }
            Tile("Fn", fnVisible) { fnVisible = !fnVisible }
        }
    }

    if (fnVisible) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceHigh,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (i in 1..12) {
                    Tile("F$i") { onKey(KeyEvent.Function(i)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    armed: Boolean = false,
    onTap: () -> Unit,
) {
    val bg = if (armed) Accent else AccentContainer
    val fg = if (armed) OnAccent else OnAccentContainer
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .pointerInput(label) {
                kotlinx.coroutines.coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.any { it.pressed }) {
                                onTap()
                                // Consume so the underlying terminal
                                // pane doesn't also see the tap.
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = AiSandboxMonoTypography.terminalSmall,
            color = fg,
        )
    }
}

/** Sealed key/modifier event emitted by the bar back up to the screen. */
sealed interface KeyEvent {
    data object TmuxArmed : KeyEvent
    data object TmuxDisarmed : KeyEvent
    data object CtrlArmed : KeyEvent
    data object CtrlDisarmed : KeyEvent
    data object AltArmed : KeyEvent
    data object AltDisarmed : KeyEvent
    data object Escape : KeyEvent
    data object Tab : KeyEvent
    data object ArrowUp : KeyEvent
    data object ArrowDown : KeyEvent
    data object ArrowLeft : KeyEvent
    data object ArrowRight : KeyEvent
    data class Function(val n: Int) : KeyEvent
}

/**
 * Translate a [KeyEvent] into the actual byte sequence the PTY expects.
 * The pure-data lookup keeps this trivially unit-testable.
 *
 * <p>ANSI escape sequences match xterm conventions; Ctrl modifies the
 * next character by masking high bits via the {@code & 0x1F} convention.
 * AC15 leaves the precise wire encoding to the implementation — these
 * are the choices most terminal emulators expect.
 */
object KeyEncoding {

    val TMUX_PREFIX: ByteArray = byteArrayOf(0x01) // Ctrl-A (configurable in tmux, default in ai-sandbox image)

    fun bytesFor(event: KeyEvent): ByteArray? = when (event) {
        KeyEvent.Escape -> byteArrayOf(0x1b)
        KeyEvent.Tab -> byteArrayOf(0x09)
        KeyEvent.ArrowUp -> "[A".toByteArray()
        KeyEvent.ArrowDown -> "[B".toByteArray()
        KeyEvent.ArrowRight -> "[C".toByteArray()
        KeyEvent.ArrowLeft -> "[D".toByteArray()
        is KeyEvent.Function -> functionBytes(event.n)
        else -> null
    }

    fun ctrlByte(printable: Char): Byte {
        return (printable.code and 0x1F).toByte()
    }

    /**
     * VT/xterm function-key encodings: F1-F4 use the SS3 form
     * `ESC O [PQRS]`; F5+ use `ESC [ <n> ~`.
     */
    private fun functionBytes(n: Int): ByteArray = when (n) {
        1 -> "OP".toByteArray()
        2 -> "OQ".toByteArray()
        3 -> "OR".toByteArray()
        4 -> "OS".toByteArray()
        5 -> "[15~".toByteArray()
        6 -> "[17~".toByteArray()
        7 -> "[18~".toByteArray()
        8 -> "[19~".toByteArray()
        9 -> "[20~".toByteArray()
        10 -> "[21~".toByteArray()
        11 -> "[23~".toByteArray()
        12 -> "[24~".toByteArray()
        else -> "[24~".toByteArray()
    }
}

/** Mini helper for the keyboard's status dot indicator in the terminal toolbar. */
@Suppress("UnusedPrivateMember")
private fun keyDot() = OnSurface
