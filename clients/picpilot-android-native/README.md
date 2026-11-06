# PicPilot Android Native

Kotlin / Jetpack Compose client for SuaCanvas. Business screens are native;
the two canvas integrations use the controlled `/workflows` WebView container.

## Local build

Install JDK 17 and Android SDK 35. Configure your own `JAVA_HOME` and
`ANDROID_HOME`, or create an ignored `local.properties` with your `sdk.dir`.
Use the checked-in Gradle wrapper from this directory:

```powershell
.\gradlew.bat :app:assembleStagingDebug
```

On macOS or Linux use `./gradlew`. Output uses the normal Gradle directories,
including `app/build/outputs/apk/staging/debug/`. No fixed drive is required.
The staging origin uses the Android emulator's host alias; review both API and
Web origins in `app/build.gradle.kts` for a physical device or another server.

## Signed production build

Set `PIC_PILOT_ANDROID_SIGNING_PROPERTIES` to your private signing configuration,
or place an ignored `signing.properties` in this project root. Supply
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Set the production
origins and check the navigation allowlists before building:

```powershell
.\gradlew.bat :app:assembleProductionRelease
```

The production release task rejects missing signing configuration. Verify the
APK signature before distribution. Keep signing files, credentials and provider
secrets out of Git. See the [release guide](../../docs/client-release.md) for
download metadata and source delivery. These commands were not executed during
the repository cleanup.
