# hummm for Android ♪

The Android sibling of [hummm](https://github.com/okusezu/hummm), the vibe-driven YouTube Music
terminal player. Same features, rebuilt for the phone: Kotlin, Jetpack Compose, Media3.

- **Home feed, search, albums, artists, playlists** from YouTube Music
- **Vibe**: describe a mood ("late night drive") and get a queue
- **Discovery**: same artist / similar artists / same genre / exploring / pure discovery,
  plus auto-discover when the queue runs dry (keeps working with the app closed)
- **Radio** from any song, **synced lyrics**, **equalizer**, shuffle / repeat-one / repeat-all
- Queue editor: reorder, remove, clear, save as a playlist
- Background playback with notification, lock-screen and Bluetooth controls
- **Chromecast**: the player's `cast` button moves the queue to a Cast device on the same Wi-Fi;
  the phone relays each song to it, so keep the app running while casting. The volume keys
  control the Chromecast while casting
- Guest mode works signed out; signing in (Library → Sign in) adds your library, likes,
  liked songs and playlists
- Colours follow your wallpaper (Material You)

Streams are resolved with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
(the phone's stand-in for yt-dlp); the catalogue comes from YouTube Music's own web API, as in ytmusicapi.

## Install

Copy `hummm-0.3.2.apk` to the phone and open it (allow "install unknown apps" for your file
manager), or with USB debugging on:

```sh
adb install -r hummm-0.3.2.apk
```

## Build

Needs JDK 17 and the Android SDK (here: `~/Android/jdk`, `~/Android/Sdk`).

```sh
export JAVA_HOME=~/Android/jdk ANDROID_HOME=~/Android/Sdk
./gradlew assembleRelease          # signed if keystore.properties exists
./gradlew testDebugUnitTest        # parser tests against saved responses
./gradlew testDebugUnitTest -Plive # + live API, stream and screenshot tests (build/screenshots)
```

`keystore.properties` (not in git) points at the release key in `~/.android-keys/`.
Keep that key: updates must be signed with the same one.
