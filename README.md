# Meowto Ranger — v0.11.0 Accessibility preview

This release keeps the v0.10 Accessibility-based LINE Rangers friend helper and automatic self-disable outside the game.

## Faster scrolling and mid-list recovery
- Swipe duration: **480 ms** (previously 720 ms).
- Rest after swipe: **650 ms** (previously 850 ms).
- Settle probe: **320 ms** (previously 410 ms).
- Verify a green help button in **two separate screenshots** before tapping.
- If the carousel does not advance, retry with a longer swipe rather than stopping after four unchanged images; stop only after nine unchanged attempts or the bounded scan limit.
- Allow 2.2 seconds before automatic Accessibility shutoff outside LINE Rangers, to reduce false shutdown from transient system overlays. K PLUS-specific shutdown remains immediate.

**Android limitation:** The app can disable its own Accessibility service using `disableSelf()`, but cannot re-enable it silently. You must turn the service on again in Android Accessibility Settings. This preview has **not** been verified on the physical phone or with K PLUS.

---

## Previous release: v0.10.0 Accessibility preview

LINE Rangers friendship-point helper. The user starts the friend-point workflow inside the game via the visible floating play control and can stop it at any time.

## Accessibility + banking compatibility

- This preview **restores Android AccessibilityService** for game-only screenshots and gestures. Wireless Debugging and MediaProjection are **not required** for this mode.
- When the device leaves LINE Rangers after the service has seen the game, the service **calls Android AccessibilityService.disableSelf()**. This genuinely turns off the accessibility permission. It does not merely pause the bot or pretend that the service is disabled.
- The **ปิด Accessibility ทันที (ก่อนใช้ K PLUS)** button can also disable the service before opening the bank app.
- If K PLUS is detected while this service is still active, the service tries to turn itself off immediately. **The bank may check earlier**, so compatibility is not guaranteed. Follow any security warnings from the bank.
- Android **does not let ordinary apps enable AccessibilityService programmatically**. Each time the service has disabled itself, the user must re-enable it in **Android Settings → Accessibility**, then return to the app or game. The app includes a shortcut to that screen.
- No Root, Shizuku, hidden setting changes, WRITE_SECURE_SETTINGS or banking app modification is used. **Fully automatic off-and-on switching is not possible with ordinary Android permissions.**

## Quick setup

1. Install the APK, open Meowto Ranger.
2. Tap **เปิดสิทธิ์ Accessibility** and enable Meowto Ranger in Android Accessibility Settings. Sideloaded APKs may require the user to allow restricted settings.
3. Return to Meowto Ranger and select **เปิด LINE Rangers**. Confirm sending LINE messages.
4. In LINE Rangers, press the floating **▶** to begin; press **■** to stop.
5. When leaving the game, wait for the service to turn itself off. Check Accessibility Settings if K PLUS still warns.
6. For a guaranteed intentional shutdown before opening K PLUS, tap **ปิด Accessibility ทันที (ก่อนใช้ K PLUS)** in Meowto Ranger.
7. The next game session requires manually enabling Accessibility again.

## Safety and limitations

- Input and screenshots are limited to the LINE Rangers foreground app.
- A daily limit of 50 friend messages is enforced through the game counter.
- No K PLUS content is captured, tapped or controlled.
- Builds in GitHub Actions are **preview/debug** builds and are not tested against a real phone, LINE Rangers or K PLUS.
- Preview/debug and future release-signed APKs may require uninstall/reinstall when signing certificates differ.
- In-app updates use official GitHub Releases with package/version/hash checks. No signed release has been verified for v0.10.

## Project files

- `app/src/main/java/com/itonkung/meowtoranger/FriendAccessibilityService.kt`: Accessibility game engine, safe automatic shutdown.
- `app/src/main/java/com/itonkung/meowtoranger/MainActivity.kt`: status, consent, game launcher, shutdown and settings shortcuts.
- `app/src/main/res/xml/friend_accessibility_config.xml`: Android service declaration.
- `config/friend-points.json`: editable validated timing controls.
- `docs/RELEASING.md`: stable signing and update process.
