# Release signing and in-app updates

The **first production APK** must be signed with a long-lived private release key. Every subsequent update must use the **same application ID**, certificate and an **increasing versionCode**, otherwise Android will reject in-place updates.

## One-time setup (repository owner)

1. Generate and **back up privately** a release signing key. Do not upload it to this public repository:
   ```bash
   keytool -genkeypair -v -keystore release-keystore.jks -alias meowto \
     -keyalg RSA -keysize 3072 -validity 36500 -storetype PKCS12
   ```
2. Base64-encode the keystore. On PowerShell:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.jks")) | Set-Clipboard
   ```
3. In GitHub **Settings → Secrets and variables → Actions**, add:
   - `MEOWTO_KEYSTORE_BASE64` — keystore base64
   - `MEOWTO_STORE_PASSWORD` — keystore password
   - `MEOWTO_KEY_ALIAS` — `meowto`
   - `MEOWTO_KEY_PASSWORD` — signing key password
4. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
5. Tag the commit (e.g. `v0.8.0`) and push the tag. The release workflow builds a **signed release APK**, attaches it to GitHub Releases and publishes its SHA-256 digest.
6. Install that first signed release on the phone. Later versions can be fetched through **ตรวจสอบอัปเดต** inside the app.

## Important limitations

- **Debug preview APKs are NOT production-updatable.** An app first installed from GitHub Actions' ephemeral debug build usually must be uninstalled before the first properly signed release. After the production install, preserve the same private signing key and use sequential versionCodes.
- Android asks for user authorization to install new packages. This app will not silently install updates.
- Remote settings from `config/friend-points.json` change only validated numeric timing values and can take effect without an APK. Changing Kotlin bytecode, OS permissions or feature implementation requires a new signed APK.
- The former v0.7 Accessibility engine was imported as a **legacy compatibility mode**, pending a new MediaProjection + embedded Wireless ADB backend. Do not claim this preview resolves K PLUS compatibility.
- Secure the signing keystore offline and use GitHub secrets, never put its password or raw private key in code or public Git history.
