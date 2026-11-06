# Meowto Ranger 🐈

**Android automation hub for LINE Rangers** — a modular application intended to grow beyond friendship points.

> Development status: **v0.8 preview builds successfully**, but still uses the legacy Accessibility-based game controller. An embedded no-Accessibility input backend has not been implemented or verified. This preview does not solve K PLUS compatibility.

## Goals

- One installable Android application named **Meowto Ranger**, with separate feature modules.
- **Friend Points** as the first feature; other tasks can be added through the same module interface.
- No mandatory third-party companion app. Investigate an *embedded, user-paired Wireless ADB client* rather than pretending ordinary Android applications can tap other applications without privilege.
- MediaProjection requires a **user-approved screen capture session** and explicit foreground service.
- Visible **Start / Stop**, activity state, daily quota, tap safety, and out-of-game fail-closed behavior.
- GitHub Releases-powered in-app updater: detect new version, download APK, validate digest, launch Android's **user-confirmed** installer.
- Remote configuration for safe numeric settings (swipe duration, settling delay etc.). No dynamic code loading.
- Stable release signing identity across all versions; **never commit the signing key to a public repository**.

## Permissions / bank apps

This tool must not disguise itself from K PLUS or bypass banking security. The user reports K PLUS operates normally when the old Accessibility automation service is disabled. Embedded wireless debugging is a candidate, **not a verified K PLUS compatibility fix**; test it with the bank app before claiming compatibility.

## Roadmap

- [x] Repository and modular architecture plan.
- [ ] Implement and validate embedded wireless ADB pairing and input backend; explicit Android authorization is required.
- [ ] Implement MediaProjection capture pipeline, detection, and state-machine handoff.
- [ ] Port friend-points automation to the input/capture abstraction with safe recovery.
- [ ] Add extensible module registry and user-friendly Thai UI.
- [ ] Implement and test GitHub Release updater, remote settings, and rollback protections.
- [ ] Sign production release with persistent private keystore (CI secrets) and publish initial APK.
- [ ] Verify on a physical device, including K PLUS compatibility and game policy.

## Proposed structure

```text
app/               Android application, home dashboard
core/input/        Input backend interface and on-device Wireless ADB implementation
core/capture/      MediaProjection with per-session consent
core/update/       GitHub Releases APK updater
core/config/       Validated remote settings
modules/friends/   Daily friendship-points workflow and detection
.github/workflows  CI build and release signing
config/            Remote non-executable parameters
```

**Update behavior:** Adjusting remote numeric values may need no APK install. Changing executable Kotlin/Android code does require a new signed APK and Android user consent during installation.

## Previous implementation

The older Rangers Friend Auto v0.7 uses an AccessibilityService and is **not** directly suitable as a banking-compatible, Accessibility-free implementation. Its game detection workflow can be ported after the new backend is verified.

## Latest verified CI preview

The GitHub Actions Android CI build for commit `34dea2d0e6549912d0064766bf491170c947c34d` succeeded, including the new updater flow that resumes installation after Android's unknown-source permission is enabled. See the run: https://github.com/itonkungx-x/Meowto-Ranger/actions/runs/42201500652

**Important:** CI preview uses a debug certificate and is not a permanent installation/update channel. Configure the private signing credentials described in [docs/RELEASING.md](docs/RELEASING.md) and publish the first official signed release before telling users to use the in-app updater for future upgrades.

Current update capabilities: GitHub release check, SHA-256 validation, correct-package and increasing-version verification, permission handoff and installation confirmation. Android does not permit silent installation.

The remote numeric configuration refreshes from `config/friend-points.json` and can be updated independently of the APK. The legacy Accessibility service is not a bank-compatible no-Accessibility solution.
