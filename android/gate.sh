#!/usr/bin/env bash
# UC-85 — the single, deterministic, LLM-free functional gate (AC-8, local entry).
#
# One command brings up the WHOLE real stack and runs the on-device instrumented
# gate suite against it, with NO live Claude and NO LLM in the loop:
#
#   1. build the server bootJar + aisandboxctl + the app & androidTest APKs
#   2. stand up the REAL mTLS management server under the `replay` Spring profile
#      (recorded protocol fixtures instead of the docker conversation-tail helper;
#      synthetic sessions; answer-echo) — see server/CONVERSATION_PROTOCOL.md
#   3. boot a headless KVM emulator with ANR-mitigation flags (AC-10)
#   4. enroll the device over mTLS via the UC-83 QR-from-file route (no camera, AC-9)
#   5. run the instrumented gate suite by stable testTag (QA owns the suite; AC-4)
#   6. tear everything down
#
# Determinism: the question/answer + conversation-view behaviours are raised from
# committed fixtures and asserted programmatically by the gate suite; there is no
# screenshot eyeballing and no `adb input tap` coordinates.
#
# The SAME flow runs in CI (.github/workflows/android-gate.yml). The /dev/kvm
# go/no-go precondition is proven separately by android-gate-smoke.yml.
#
# Env knobs (all optional, sensible defaults):
#   JAVA_HOME            required — JDK 21
#   ANDROID_HOME         required — Android SDK with platform-tools + emulator
#   GATE_TEST_PACKAGE    instrumented gate suite package (default com.aisandbox.android.gate)
#   GATE_MIN_TESTS       minimum tests the gate suite MUST run, else hard-fail (default 1) —
#                        guards against a missing/misnamed suite silently passing the gate
#                        (`am instrument` exits 0 even when it runs ZERO tests)
#   GATE_ENROLL_CLASS    UC-83 enrollment test FQN (default com.aisandbox.android.net.E2eQrFileEnrollmentTest)
#   GATE_PORT            server TLS port (default 18443)
#   GATE_AVD             AVD name (default ai_sandbox_gate)
#   GATE_KEEP            set to 1 to skip teardown (leave emulator + server running for debugging)
#   GATE_SKIP_EMULATOR   set to 1 to reuse an ALREADY-RUNNING emulator (CI: the
#                        reactivecircus action owns the AVD lifecycle) — gate.sh then
#                        neither creates/launches nor kills the emulator.
set -euo pipefail

# ── resolve repo + required env ────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"

GATE_TEST_PACKAGE="${GATE_TEST_PACKAGE:-com.aisandbox.android.gate}"
GATE_MIN_TESTS="${GATE_MIN_TESTS:-1}"
GATE_ENROLL_CLASS="${GATE_ENROLL_CLASS:-com.aisandbox.android.net.E2eQrFileEnrollmentTest}"
PORT="${GATE_PORT:-18443}"
AVD="${GATE_AVD:-ai_sandbox_gate}"
DEV="emulator-5554"

ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
AVDM="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
SYS_IMG="system-images;android-34;google_apis;x86_64"

GATE_DIR="$REPO/.gate-run"
mkdir -p "$GATE_DIR"/{pki,clients,enrollment,secrets,sessions,log}

log() { printf '\n\033[1;36m[gate] %s\033[0m\n' "$*"; }

# Assert an `am instrument` run PASSED and actually ran at least $min tests.
# Args: <min> <output-file> <label>. `am instrument` exits 0 even when it runs
# ZERO tests (missing/misnamed package) and prints "OK (0 tests)", so a plain
# "OK (" check would let an empty suite silently pass the gate. We parse the test
# count from the AndroidJUnitRunner output ("OK (N test(s))" on success, else
# "Tests run: N") and hard-fail on a test failure, a count below the minimum, or
# a missing count line (instrumentation did not run at all).
assert_tests_ran() {
  local min="$1" out="$2" label="$3" count=""
  if grep -q "FAILURES!!!" "$out"; then
    echo "::error:: $label: test failures — see $out"; exit 1
  fi
  if grep -qE 'OK \([0-9]+ test' "$out"; then
    count="$(grep -oE 'OK \([0-9]+ test' "$out" | grep -oE '[0-9]+' | head -1)"
  elif grep -qE 'Tests run: [0-9]+' "$out"; then
    count="$(grep -oE 'Tests run: [0-9]+' "$out" | grep -oE '[0-9]+' | head -1)"
  fi
  if [ -z "$count" ]; then
    echo "::error:: $label: no test-count line in the runner output — instrumentation did not run (missing/misnamed suite?). See $out"; exit 1
  fi
  if [ "$count" -lt "$min" ]; then
    echo "::error:: $label ran $count test(s); require >= $min. A green gate with zero tests is a gate failure. See $out"; exit 1
  fi
  log "$label: $count test(s) passed"
}

SERVER_PID=""
cleanup() {
  local rc=$?
  if [ "${GATE_KEEP:-0}" = "1" ]; then
    log "GATE_KEEP=1 — leaving emulator + server up (server.log: $GATE_DIR/log/server.log)"
    return
  fi
  log "teardown"
  # In CI the reactivecircus action owns the emulator — never kill it here.
  [ "${GATE_SKIP_EMULATOR:-0}" = "1" ] || "$ADB" -s "$DEV" emu kill >/dev/null 2>&1 || true
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT

# ── 1. build ────────────────────────────────────────────────────────────────────
log "building server jars + APKs"
"$REPO/gradlew" -p "$REPO" \
  :server:bootJar :server:aisandboxctlJar \
  :android:assembleDebug :android:assembleDebugAndroidTest --no-daemon

SERVER_JAR="$(ls "$REPO"/server/build/libs/aisandbox-server*.jar | head -1)"
CTL_JAR="$(ls "$REPO"/server/build/libs/aisandboxctl*.jar | head -1)"
APP_APK="$(ls "$REPO"/android/build/outputs/apk/debug/*.apk | head -1)"
TEST_APK="$(ls "$REPO"/android/build/outputs/apk/androidTest/debug/*.apk | head -1)"
for f in "$SERVER_JAR" "$CTL_JAR" "$APP_APK" "$TEST_APK"; do
  [ -f "$f" ] || { echo "::error:: build artifact missing: $f"; exit 1; }
done

# ── 2. real mTLS server under the replay profile ─────────────────────────────────
log "standing up the mTLS server (replay profile) on :$PORT"
if [ ! -s "$GATE_DIR/pki/server.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
    -keyout "$GATE_DIR/pki/server.key" -out "$GATE_DIR/pki/server.crt" \
    -subj "/CN=ai-sandbox-server" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" \
    -addext "basicConstraints=critical,CA:FALSE" \
    -addext "keyUsage=critical,digitalSignature,keyEncipherment"
  chmod 644 "$GATE_DIR/pki/server.crt"; chmod 600 "$GATE_DIR/pki/server.key"
fi
cat > "$GATE_DIR/config.yaml" <<YAML
ai-sandbox:
  server:
    tls: { port: $PORT, bind-address: "0.0.0.0" }
    pki:        { dir: $GATE_DIR/pki }
    clients:    { dir: $GATE_DIR/clients }
    enrollment: { dir: $GATE_DIR/enrollment, default-ttl-minutes: 10, rate-limit-per-window: 20, rate-limit-window-seconds: 60 }
    hostscripts: { repo-root: $REPO }
    sessions:   { host-state-root: $GATE_DIR/sessions }
    secrets:    { dir: $GATE_DIR/secrets }
    audit:      { file: $GATE_DIR/log/audit.log, retention-days: 7 }
    replay:     { dir: $REPO/fixtures/replay }
YAML

# The -D audit override is read at early logback init (before YAML applies); the
# replay profile activates the fixture-backed transcript source + synthetic sessions.
"$JAVA_HOME/bin/java" -Xms256m -Xmx1g -Dfile.encoding=UTF-8 \
  -Dai-sandbox.server.audit.file="$GATE_DIR/log/audit.log" \
  -jar "$SERVER_JAR" \
  --spring.profiles.active=replay \
  --spring.config.additional-location=file:"$GATE_DIR/config.yaml" \
  > "$GATE_DIR/log/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 60); do
  grep -q "Netty started on port $PORT" "$GATE_DIR/log/server.log" 2>/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "::error:: server exited early"; tail -40 "$GATE_DIR/log/server.log"; exit 1; }
  sleep 1
done
grep -q "Netty started on port $PORT" "$GATE_DIR/log/server.log" || { echo "::error:: server did not start"; tail -40 "$GATE_DIR/log/server.log"; exit 1; }
log "server up (synthetic replay sessions exposed)"

# ── 3. boot the emulator (ANR mitigation, AC-10) ─────────────────────────────────
if [ "${GATE_SKIP_EMULATOR:-0}" = "1" ]; then
  log "GATE_SKIP_EMULATOR=1 — reusing the already-running emulator"
else
  log "booting AVD $AVD"
  "$AVDM" list avd 2>/dev/null | grep -q "$AVD" || {
    yes | "$SDKM" "$SYS_IMG" >/dev/null 2>&1 || true
    echo no | "$AVDM" create avd -n "$AVD" -k "$SYS_IMG" -d pixel_6
  }
  pgrep -f "avd $AVD" >/dev/null || \
    nohup "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
      -gpu swiftshader_indirect -no-metrics -camera-back none -camera-front none \
      -accel auto -port 5554 > "$GATE_DIR/log/emulator.log" 2>&1 &
fi
"$ADB" wait-for-device
until [ "$("$ADB" -s "$DEV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
# AC-10 — kill animations + the ANR dialog so launcher/SystemUI ANRs are out of path.
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" -s "$DEV" shell settings put global "$s" 0 || true
done
"$ADB" -s "$DEV" shell settings put secure anr_show_dialog 0 || true
log "emulator booted"

# ── 4. install + enroll (UC-83 QR-from-file, camera-free, AC-9) ──────────────────
log "installing APKs"
"$ADB" -s "$DEV" install -r "$APP_APK"
"$ADB" -s "$DEV" install -r "$TEST_APK"

log "enrolling device (UC-83 QR-from-file route)"
PAYLOAD="$("$JAVA_HOME/bin/java" -jar "$CTL_JAR" client invite gate-vm \
  --pki-dir "$GATE_DIR/pki" --enrollment-dir "$GATE_DIR/enrollment" \
  --server-url "https://10.0.2.2:$PORT" --json)"
printf '%s' "$PAYLOAD" > "$GATE_DIR/invite.json"
ZXING_JAR="$(find "$HOME/.gradle/caches" -path '*zxing*' -name 'core-*.jar' 2>/dev/null | head -1)"
[ -n "$ZXING_JAR" ] || { echo "::error:: ZXing core jar not found in the gradle cache (build the app first)"; exit 1; }
cat > "$GATE_DIR/QrGen.java" <<'JAVA'
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
public class QrGen {
  public static void main(String[] a) throws Exception {
    String payload = new String(Files.readAllBytes(new File(a[0]).toPath())).trim();
    BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600);
    BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++)
      img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
    ImageIO.write(img, "png", new File(a[1]));
  }
}
JAVA
( cd "$GATE_DIR" && "$JAVA_HOME/bin/javac" -cp "$ZXING_JAR" QrGen.java \
    && "$JAVA_HOME/bin/java" -cp ".:$ZXING_JAR" QrGen "$GATE_DIR/invite.json" "$GATE_DIR/invite-qr.png" )
"$ADB" -s "$DEV" push "$GATE_DIR/invite-qr.png" /sdcard/Download/invite-qr.png
"$ADB" -s "$DEV" shell "am instrument -w \
  -e class $GATE_ENROLL_CLASS \
  -e qrImagePath /sdcard/Download/invite-qr.png \
  com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner" | tee "$GATE_DIR/log/enroll.out"
assert_tests_ran 1 "$GATE_DIR/log/enroll.out" "enrollment"
log "device enrolled"

# ── 5. run the instrumented gate suite (testTag-driven, QA-owned) ────────────────
log "running the deterministic gate suite ($GATE_TEST_PACKAGE)"
"$ADB" -s "$DEV" shell "am instrument -w \
  -e package $GATE_TEST_PACKAGE \
  com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner" | tee "$GATE_DIR/log/gate.out"
assert_tests_ran "$GATE_MIN_TESTS" "$GATE_DIR/log/gate.out" "deterministic gate suite"
log "DETERMINISTIC GATE PASSED ✅"
