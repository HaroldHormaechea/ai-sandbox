# Use Case 83: Enrollment — read the invite QR from an image file (alternative to the live camera)

## Summary
The Android enrollment view currently obtains the mTLS invite QR only via the **live camera** scan. This is a problem when (a) the user received the invite QR as a screenshot / image file rather than on a second screen, and (b) for **headless/emulator testing**, where the emulator's virtual-scene back camera faces a non-overridable test card and cannot present a real QR (documented in the `android-testing` skill — enrollment currently has to fall back to an on-device probe). This use case adds a **"Read QR from file" button** to the enrollment view that lets the user pick an image from device storage, decodes the QR from that image, and feeds the decoded invite payload into the **exact same enrollment path** the camera scan uses (`EnrollmentClient → POST /v1/enrollment`, SPKI pin). It is an additive alternative input — the camera scan path is unchanged.

## Acceptance Criteria
1. The enrollment view shows a new affordance (e.g. a "Read QR from file" / "Import QR image" button) alongside the existing camera scanner, discoverable without leaving the enrollment screen.
2. Tapping it opens the system document/image picker (Storage Access Framework — `ACTION_OPEN_DOCUMENT`/`GetContent` for `image/*`), and the user can select a PNG/JPEG containing the invite QR.
3. The selected image is decoded for a QR code; the decoded payload is parsed and validated **identically** to a camera scan, then handed to the **same** enrollment flow (`EnrollmentClient → POST /v1/enrollment`, SPKI cert-pin) — no divergent parsing or networking path.
4. A successful file-based enrollment lands in exactly the same post-enrollment state as a camera scan (resumes to the sessions list per UC-16); no second prompt.
5. Error handling: an image with **no decodable QR**, an unreadable/oversized file, or a QR that decodes but fails invite-payload validation (malformed / wrong scheme / non-QR invite — reuse the UC-61 invalid-QR scoping) shows a clear, non-fatal error and returns the user to the enrollment view to retry; it does NOT crash or wedge the screen.
6. No regression to the live camera scan path (UC-04 enrollment), the cert-pin chain handling (UC-09/10/13/14), or the resume-to-sessions behavior (UC-16). Single-permission hygiene: the file path must NOT require the camera permission, and the image picker should use a scoped, permission-light mechanism (SAF, no broad storage permission).
7. CI gates pass: `:android:test` + `:android:lint`. (Server changes only if the enrollment payload contract changes — not expected; this is a client-side input alternative.)
8. **Documentation:** update the in-project `android-testing` skill (and, by reference, the `release` skill's Phase-1 functional gate) so the **QR-from-file route is the documented headless-enrollment guideline** — i.e. generate the invite-QR as a PNG, push it to the device, and enroll through this new button via the real production path, replacing the on-device-probe workaround. This is part of the same change so the test docs match the new capability.

## Potential Pitfalls & Open Questions
- **QR-decode dependency** — the project may already bundle a QR/barcode lib for the live scanner (e.g. ML Kit / ZXing). The analyst must determine what the camera path uses and reuse it to decode a still image (ML Kit `InputImage.fromFilePath`, or ZXing `RGBLuminanceSource` + `MultiFormatReader`) rather than adding a second QR library.
- **Shared parse/validate seam** — the camera scanner's "QR string → invite → enroll" logic must be factored so the file path calls the SAME function; do not fork the parsing/validation/networking (Criterion 3 + UC-61 invalid-QR scoping).
- **Headless-test benefit** — this directly enables emulator/CI enrollment: a test can drop a generated invite-QR PNG and drive enrollment through the real production path without the camera. The `android-testing` skill / pre-release `release` skill functional gate should adopt this as the enrollment route once it lands (replacing the on-device probe workaround).
- **Edge case** — a multi-QR or noisy image: decode the first valid invite QR or report "no valid invite QR found"; don't silently pick a wrong code.
- **Permissions** — use SAF (`OpenDocument`/`GetContent`) so no `READ_EXTERNAL_STORAGE`/`READ_MEDIA_IMAGES` runtime permission is needed where avoidable; never request the camera permission for this path.
- **Relationship** — UC-04 (enrollment + camera scanner), UC-09/10/13/14 (cert-pin/PKCS#12), UC-16 (resume to sessions), UC-61 (invalid-QR error scoped to QR processing), and the `android-testing` / `release` skills (which will use it for headless enrollment).

## Original Description
About app enrollment being an issue... add a new button to the enrollment view to read the QR from a file instead (of the live camera).

## Clarifications
- Status: **Captured during the autonomous batch run (2026-06-15) at the user's request**, prompted by the headless-emulator camera limitation surfacing during the pre-release functional gate. Interactive clarification loop skipped (autonomous capture). It is an additive client-side enrollment input; the camera path stays. To be implemented as a normal dev-team UC; it improves the live functional-gate enrollment route for future releases.
