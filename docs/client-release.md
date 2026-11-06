# Client builds and releases

The Android and Windows clients connect to the Web platform. Authentication,
credits, provider credentials and generation tasks remain on the server. The
default Docker Compose deployment does not build or publish client installers.

## Configure your service

Client projects include a default service origin and navigation allowlists.
Before distributing a client for your own deployment, configure its URLs and
trusted hosts together. Relevant locations include the Android
`app/build.gradle.kts` and `PicPilotUrlPolicy.kt`, the native Android environment
flavors, and the Windows Tauri `src-tauri/src/lib.rs` and capabilities. Preserve
HTTPS and navigation restrictions when changing origins.

## Android WebView client

Use JDK 17 and Android SDK 35. Set your own `JAVA_HOME` and `ANDROID_HOME`, or
configure the SDK through an ignored `clients/picpilot-android/local.properties`
file. The checked-in Gradle wrapper downloads the project's Gradle version.

From `clients/picpilot-android`, build a debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

On macOS or Linux use `./gradlew` instead. The default output directory is
`app/build/outputs/apk/debug/`.

For a release, provide a private `signing.properties` file through the
`PIC_PILOT_ANDROID_SIGNING_PROPERTIES` environment variable, or place it in the
client project root. Its fields are `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. Keep the file and keystore out of Git. Then build:

```powershell
.\gradlew.bat :app:assembleRelease
```

Use the Android SDK's `apksigner verify --verbose` on the generated APK and
publish only a signed release. Debug and unsigned APKs are not release assets.
The separate native Android client has its own [build guide](../clients/picpilot-android-native/README.md).

## Windows Tauri client

Install Node.js with npm, the Rust MSVC toolchain, Visual Studio C++ build tools
and WebView2. Use your local toolchain configuration; no fixed drive or cache
directory is required. From `clients/picpilot-windows`, run:

```powershell
npm ci
npm run build
```

The default NSIS installer output is `src-tauri/target/release/bundle/nsis/`, or
the equivalent under `CARGO_TARGET_DIR` if you override it. Configure Windows
code signing separately for your distribution.

## Publish download metadata

The tracked manifest helper currently expects these files under
`openImage-ts/public/downloads/`:

- `PicPilot-Android-1.0.0.apk`
- `PicPilot-Windows-1.0.0-setup.exe`

Copy reviewed release artifacts there, then run from the repository root:

```powershell
.\scripts\update-client-release-manifest.ps1
```

The helper writes each available installer's version, URL, size and SHA-256 to
`manifest.json`; missing files get disabled entries. When releasing a new
version, update the helper's names and version metadata together. Installers
are ignored by Git, so a source checkout does not include the binaries. The
Web image must include the reviewed files for the `/download` page to serve
them. Keep the matching source and third-party notices available alongside
distributed binaries.

These build instructions were reviewed statically; client builds and deployment
have not been executed as part of this cleanup.
