# SuaCanvas clients

Client projects retain the earlier PicPilot name. They connect to the Web
platform; authentication, credits, generation and provider credentials remain
on the server.

| Project | Role |
| --- | --- |
| `picpilot-android/` | Kotlin / Jetpack Compose shell using Android WebView |
| `picpilot-windows/` | Tauri / WebView2 Windows shell |
| `picpilot-android-native/` | Native Android screens with a controlled canvas WebView |
| `picpilot-windows-native/` | Separate native Windows client project |

For your own deployment, review the service origins and navigation allowlists
in the client source before building. Default origins do not automatically
follow the server's `.env`. Never embed model provider keys in a client.

See the [client release guide](../docs/client-release.md) and the
[native Android guide](picpilot-android-native/README.md). The default Web
deployment does not build these clients or include installer binaries.
