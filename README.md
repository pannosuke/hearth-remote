# Hearth Remote

An Android phone remote for Eric's Sharp AQUOS Android TV. It runs on a **Samsung Galaxy S24 Ultra** and communicates with the TV using the Android TV Remote Protocol v2.

## Features (Phase 1)

- **Trackpad mode**: Swipe to navigate, tap to select (like Apple TV remote)
- **D-Pad mode**: Traditional directional pad with OK button
- **Remote controls**: 
  - D-pad navigation (Up, Down, Left, Right, OK)
  - Back, Home, Menu
  - Volume Up/Down/Mute
  - Channel Up/Down
  - Power
  - Play/Pause (big button)
- **App launching**: Quick launch YouTube, Netflix, Disney+, Prime Video via deep links
- **Bilingual UI**: Full Japanese and English localization
- **Dark theme**: Apple TV-like dark styling with amber accents (Hearth brand)
- **Haptic feedback**: Vibration on button presses
- **Secure pairing**: TLS connection with certificate stored in Android Keystore

## Requirements

- Android phone with Android 14+ (API 34)
- Sharp AQUOS Android TV (Android 10) on the same LAN
- TV's Android TV Remote Service must be enabled (it usually is by default)

## Building

### Prerequisites

- JDK 17+
- Android SDK with API 34+

### Debug Build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Build

Release builds require signing. See [Release Setup](#release-setup) below.

```bash
export KEYSTORE_FILE=/path/to/release.keystore
export KEYSTORE_PASSWORD=your_store_password
export KEY_ALIAS=hearth_remote
export KEY_PASSWORD=your_key_password

./gradlew assembleRelease
```

## Pairing with the TV

1. Ensure your phone and TV are on the same WiFi network
2. Open Hearth Remote on your phone
3. Enter the TV's IP address (default: `192.168.10.105`)
4. Tap "Connect"
5. A 6-character pairing code will appear on the TV
6. Enter this code in the app and tap "Pair"
7. Once paired, the remote is ready to use

The pairing is saved and will reconnect automatically on subsequent launches.

## Installation via Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) can track releases from this private GitHub repository:

1. Install Obtainium on your phone
2. Add a new app with this repository URL
3. Configure authentication if this is a private repo
4. Obtainium will notify you of new releases

## Release Setup

### Generating a Signing Keystore

Create a new keystore for signing release APKs:

```bash
keytool -genkey -v -keystore release.keystore -alias hearth_remote -keyalg RSA -keysize 2048 -validity 10000
```

You'll be prompted for:
- Keystore password
- Key password (can be the same as keystore password)
- Your name, organization, etc.

Keep this keystore file safe and **never commit it to the repository**.

### GitHub Secrets

Add these secrets to your GitHub repository (Settings → Secrets and variables → Actions):

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded keystore file |
| `KEYSTORE_PASSWORD` | Password for the keystore |
| `KEY_ALIAS` | Alias of the signing key (e.g., `hearth_remote`) |
| `KEY_PASSWORD` | Password for the key |

To generate the base64-encoded keystore:

```bash
base64 -i release.keystore | tr -d '\n'
```

Copy the entire output and paste it as the `KEYSTORE_BASE64` secret.

### Creating a Release

1. Update the version in `app/build.gradle.kts` if needed
2. Commit your changes
3. Create and push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The GitHub Actions workflow will:
1. Build the signed release APK
2. Create a GitHub Release with the APK attached
3. Obtainium will detect the new release

## CI/CD

- **CI workflow** (`.github/workflows/ci.yml`): Runs on every push/PR to `main`. Builds debug APK and runs unit tests.
- **Release workflow** (`.github/workflows/release.yml`): Runs on `v*` tags. Builds signed release APK and publishes to GitHub Releases.

## Project Structure

```
app/
├── src/main/
│   ├── kotlin/net/pcg/hearth/remote/
│   │   ├── MainActivity.kt
│   │   ├── data/
│   │   │   └── SettingsRepository.kt
│   │   ├── protocol/
│   │   │   ├── CertificateManager.kt    # TLS certificate handling
│   │   │   ├── PairingProtocol.kt       # Polo pairing protocol
│   │   │   ├── RemoteProtocol.kt        # Remote commands
│   │   │   └── TvConnection.kt          # High-level connection API
│   │   └── ui/
│   │       ├── HearthRemoteApp.kt
│   │       ├── MainViewModel.kt
│   │       ├── NavigationBar.kt
│   │       ├── screens/
│   │       │   ├── AppsScreen.kt
│   │       │   ├── DPadScreen.kt
│   │       │   ├── SetupScreen.kt
│   │       │   ├── SettingsScreen.kt
│   │       │   └── TrackpadScreen.kt
│   │       └── theme/
│   ├── proto/
│   │   ├── polo.proto                   # Pairing protocol messages
│   │   └── remotemessage.proto          # Remote control messages
│   └── res/
│       ├── values/strings.xml           # English strings
│       └── values-ja/strings.xml        # Japanese strings
└── src/test/                            # Unit tests
```

## Protocol Details

The app uses the **Android TV Remote Protocol v2**, the same protocol used by the official Google TV app:

- **Pairing port**: 6467 (TLS with mutual certificate authentication)
- **Remote port**: 6466 (TLS, sends protobuf RemoteMessage commands)
- **Pairing flow**: Polo protocol with HEXADECIMAL encoding, 6-character code
- **Commands**: Key injection (any Android keycode), volume control, app deep links

Reference implementation: [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2)

## What's Not Included (Future Phases)

Per the plan in `handoff/PLAN.md`, these features are deferred to later phases:
- Jellyfin integration (resume, subtitles)
- Hearth LAN service integration (live TV guide, channel cards)
- Voice commands
- NHL highlights

## Known Limitations

- **Cannot test against real TV**: Protocol implementation is based on documentation and reference implementations. May need adjustments when first connected to the actual TV.
- **No mDNS discovery**: TV address must be entered manually. mDNS discovery could be added but requires multicast permissions.
- **No TV state reading**: The protocol doesn't support reading what's currently playing on the TV.

## License

Private repository for personal use.

## Related Projects

- [hearth-launcher](https://github.com/pannosuke/hearth-launcher) - The companion TV launcher
