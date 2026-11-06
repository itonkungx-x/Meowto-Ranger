# Meowto Ranger

**LINE Rangers Android automation hub — one APK, extensible modules.**

## v0.9 preview (no AccessibilityService)

The preview has an embedded Android Wireless Debugging ADB client to inject touches, a user-consented MediaProjection foreground service to read the game screen, and the original friendship-point workflow (including slower swipes, button confirmation, OCR quota reading, wrong-page recovery). No separate Shizuku application or root is required.

This is **not** an install-and-run-with-zero-authorization product. Android still requires initial Wireless Debugging pairing from Developer Options, and screen-capture permission **once per capture session**. Wireless Debugging may need to be reenabled after restarting the phone. This prototype is not yet verified against K PLUS or the game on a physical device. Do not change bank settings or bypass security warnings.

## Quick setup

1. Install the APK. Open `Meowto Ranger`.
2. On the same phone, enable **Developer options → Wireless debugging** while connected to Wi-Fi.
3. Open **Pair device with pairing code** in Android Settings. Use split-screen with Meowto Ranger to keep the pairing dialog visible.
4. Enter the **pairing port** and six-digit code into Meowto Ranger, then press **จับคู่**. Do not confuse pairing port with connection port.
5. Back at the main **Wireless Debugging** screen, read **IP address & Port**, enter its **connection port** in Meowto Ranger and press **เชื่อมต่อ**.
6. Press **เริ่มช่วยเพื่อน**, confirm LINE sending, and approve Android's **Share entire screen** dialog. The app opens LINE Rangers and runs the friend-points module.
7. Use the persistent **Meowto Ranger notification → หยุด** to stop while in the game. It also stops automatically upon leaving the game.

If the game does not run, verify that Wireless Debugging remains enabled and reconnect with the current port. On Android 14+, a new capture session always needs another Android permission dialog.

## Safety and permissions

- **NO** `BIND_ACCESSIBILITY_SERVICE` in the v0.9 manifest; legacy service removed.
- Commands only run if Wireless ADB confirms the foreground app is LINE Rangers (`com.linecorp.LGRGS`). Unknown foreground = do not tap.
- Screen capture exists only during active, user-authorized gameplay and is released when the service stops.
- No screenshot content or pairing codes are uploaded to any server. ADB signing key lives in app-private `noBackupFilesDir`.
- The automation sends real LINE messages to friends. It asks for in-app consent when starting and respects the 50-message daily game limit.
- K PLUS compatibility remains **unverified**. Do not use or attempt to bypass banking security if the bank warns.

## Modules

`modules/friends/FriendSession.kt` implements friendship points. The `ModuleRegistry` and `core/input` + `core/capture` boundaries support future game modules.

## Updates

- Safe numeric timing settings live in `config/friend-points.json` and can refresh without changing the APK.
- The in-app **ตรวจสอบอัปเดต** uses official GitHub Releases, validates APK SHA-256, and opens Android's official installation confirmation.
- Code updates require a new signed APK; Android still requires installation confirmation.
- **Critical:** Preview/debug APKs are not a permanent release channel. Configure a **single stable private release signing key** following [docs/RELEASING.md](docs/RELEASING.md). First install the official signed release; then future releases install as in-place updates.

## Build and status

GitHub Actions CI builds a debug preview after pushes and PRs. Device integration testing is still required for Wireless Debugging pairing, MediaProjection orientation handling, game scene recognition, and K PLUS compatibility. Do not claim an APK built by CI has passed real-game testing.
