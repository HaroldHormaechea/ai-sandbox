---
plan_for: (free-form task)
work_branch: feat/terminal-font-jetbrains-mono
team: ai-sandbox
approved: 2026-10-05
---

# Implementation plan — bundle JetBrains Mono for the terminal/tmux view

## Analysis

On a physical Oppo Reno 16 (ColorOS 16 / Android 16) the tmux/terminal view
renders with blocky box-drawing glyphs. **Corrected root cause (verified by
analyst + challenger):** the bug lives in the vendored Termux `TerminalView`,
which defaults to `Typeface.MONOSPACE` — `TerminalSurface.kt` never calls
`setTypeface`, so on ColorOS the device's default monospace (poor box-drawing
coverage) is used. `Typography.kt`'s placeholder `FontFamily.Monospace` only
feeds Compose chrome, not the Termux renderer — so it is a *secondary*
consistency fix, not the primary one. Both share one bundled JetBrains Mono set.

## Approach (challenger-approved)

- **Bundle static JetBrains Mono TTFs** (not a Downloadable/GoogleFont provider):
  the app is sideloaded onto a possibly-GMS-less ColorOS device where a provider
  can silently fall back to the blocky default — the bug. Deterministic/offline.
- **Weights:** Regular (W400) + Medium (W500). Terminal needs Regular only (the
  Termux renderer synthesizes bold via `setFakeBoldText`, `TerminalRenderer.java:229`).
  Static over variable (determinism on a quirky OEM renderer; ~+400 KB negligible at mvp).
- **Coverage:** JetBrains Mono natively covers U+2500–U+257F (box drawing) +
  U+2580–U+259F (block elements). No powerline/Nerd-Font glyphs are used anywhere
  (grepped) → stock JB Mono suffices.
- **OFL-1.1:** mirror the existing `LICENSE-termux` + `NOTICE` precedent; also ship
  an in-APK license copy.
- **Profiles** (`profile-java-server-architecture`, `profile-java-call-graph-tool`):
  N/A — both are Java/Spring server profiles scoped to `server/**`.

## Production code (developer)

1. **`android/src/main/kotlin/com/aisandbox/android/ui/components/TerminalSurface.kt`** (PRIMARY)
   - Add a testable helper `fun monoTypeface(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_regular)`.
   - In the `factory` `TerminalView(ctx,null).apply { … }` block, **immediately after
     `setTextSize(textSizePx)` and before `attachSession(...)`**, call
     `monoTypeface(ctx)?.let { setTypeface(it) }`. On the **null branch, log a
     warning** so a bad-packaging fallback to MONOSPACE is diagnosable. Order is
     verified safe: `setTypeface` rebuilds the renderer preserving `mTextSize`
     (`TerminalView.java:600-601`); before `attachSession` so initial column/row
     math uses JB Mono metrics.
   - Imports: `android.graphics.Typeface`, `androidx.core.content.res.ResourcesCompat`, `com.aisandbox.android.R`.
2. **`android/src/main/kotlin/com/aisandbox/android/ui/theme/Typography.kt`** (SECONDARY)
   - Replace `val Mono: FontFamily = FontFamily.Monospace` (line 21) with a
     `FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.W400), Font(R.font.jetbrains_mono_medium, FontWeight.W500))`.
   - Imports `androidx.compose.ui.text.font.Font`, `com.aisandbox.android.R`. Update
     the stale lines-14–21 placeholder comment. Leave `Sans` untouched.
3. **Font resources (new binaries):** `android/src/main/res/font/jetbrains_mono_regular.ttf`,
   `android/src/main/res/font/jetbrains_mono_medium.ttf`. Create `res/font/`. Fetch the
   **official OFL static TTFs** from the `JetBrains/JetBrainsMono` GitHub release
   (`JetBrainsMono-Regular.ttf` / `-Medium.ttf`); record version + file hashes in NOTICE.
4. **Licensing:** `LICENSE-jetbrains-mono` (repo root, OFL-1.1 full text, new);
   `NOTICE` — add a JetBrains Mono section mirroring the termux block (version/hashes);
   `android/src/main/assets/fonts/OFL.txt` — in-APK license copy (new).

## Test code (qa)

1. **`android/src/test/kotlin/com/aisandbox/android/ui/theme/TypographyMonoFontTest.kt`** (JVM/JUnit5 Jupiter)
   — assert `Mono !== FontFamily.Monospace`; assert it's a `FontListFontFamily`
   referencing `R.font.jetbrains_mono_regular`@W400 + `R.font.jetbrains_mono_medium`@W500
   (Compose `Font(resId,…)` is lazy — no Robolectric needed). Fallback if ResourceFont
   equality is env-sensitive: assert not `GenericFontFamily`, is `FontListFontFamily`.
2. **`android/src/test/kotlin/com/termux/view/TerminalTypefaceTest.kt`** (Robolectric,
   **JUnit4/vintage** — Robolectric 4.14.1 runs only via `junit-vintage-engine`):
   `@RunWith(RobolectricTestRunner::class)` + `org.junit.Test` + `@Config`, NOT Jupiter.
   In `com.termux.view` (reads package-private `mRenderer.mTypeface`): build a
   `TerminalView`, `setTypeface(jb)`, assert `mRenderer.mTypeface === jb`. Also add a
   Robolectric unit test for `monoTypeface(ctx)` (resolves, non-null).

**Coverage caveat (state in PR, do not hide):** no automated test asserts that
`TerminalSurface` *calls* `setTypeface` — that glue needs an instrumented/Compose-UI
test and CI runs no `androidTest`. The helper + the vendored seam are unit-tested; the
single glue line + on-device ColorOS glyph-shape correctness are **manual-verify-only**.

## Residual risks

- Developer must source the correct OFL TTFs and record provenance (version/hashes) in NOTICE.
- `monoTypeface` null-fallback logs a warning but still renders (degraded) — acceptable.

## Challenger verdict

**APPROVE** (no Critical/Major; 3 Minor refinements folded in: JUnit4/vintage Robolectric,
extract testable `monoTypeface(ctx)` helper, log on null-font fallback). Root cause
independently verified in the Termux `TerminalView`; bundled-not-downloadable, weights,
and OFL licensing all correct; no scope creep (Sans untouched).
