# DubLiftApp

Run [DubLift](https://github.com/joojoooo/DubLift) on Android: a local addon
server for Stremio and Nuvio that combines your video sources with synchronized
Italian audio. The app includes DubLift, FFmpeg, ffprobe, and an in-app dashboard. No separate tools are needed.

## 📦 Install

Requires **Android 7.1 (API 25) or newer**. Download the matching APK from
[GitHub Releases](https://github.com/joojoooo/DubLiftApp/releases):

| APK | Android architecture |
| --- | --- |
| `DubLift-arm64.apk` | ARM64 (64-bit), `arm64-v8a` |
| `DubLift-armv7.apk` | ARMv7 (32-bit), `armeabi-v7a` |

Open the APK and allow your browser or file manager to install apps if Android
asks. Install new releases over the existing app to keep your settings.

On startup, DubLift checks for a newer GitHub release and shows its changelog.
**Open release page** opens your browser so you can choose an APK; downloads
start only when you select one. **Later** dismisses the prompt for that launch.

## 🚀 Usage

1. Open **DubLift**. The server starts automatically and loads its dashboard.
   Allow notifications and the battery-optimization exemption when prompted.
2. Follow the guided setup to connect your upstream addons and install DubLift
   in Stremio/Nuvio. For playback on this phone, set **Settings → Advanced
   settings → Public base URL** to `http://127.0.0.1:7000`. The addon manifest is
   `http://127.0.0.1:7000/manifest.json`. For Stremio, follow the account sync
   instructions under **LAN access** below.
3. Choose a movie or episode in your player and select a DubLift result marked
   with the Italian flag. Keep DubLift running during playback.

On Android TV and Fire TV, the pill dock stays visible. Press **Back** on the
remote to focus the controls, use the arrows and **Select/OK** to choose one,
then press **Back** again to return focus to the dashboard.

## 🌐 LAN access

Connect both devices to the same trusted Wi-Fi network. Find the phone's LAN IP
in Android's Wi-Fi settings, then set **Public base URL** to `http://PHONE-LAN-IP:7000`

Use `http://PHONE-LAN-IP:7000/manifest.json` in Nuvio, or sync it to your
Stremio account using the steps below.

Stremio does not allow installing HTTP local/LAN addons directly.
Open [Stremio Addon Manager](https://gateniomer.github.io/stremio-addon-manager/)
([source](https://github.com/gateniomer/stremio-addon-manager)), sign in with
your Stremio account, add the appropriate manifest URL, and sync the addon
list to your account. The addon then syncs to all devices signed in to that
account. Use the phone's LAN manifest URL for playback on other LAN devices;
the loopback URL works only on the phone running DubLift.

**DubLift has no built-in authentication. Use trusted networks only.**

## ⚠️ Android limitations

- Android and phone makers can still stop background processes, especially
  after a force-stop. **Battery** requests the battery exemption again;
  uninterrupted background playback is not guaranteed. See
  [Don't Kill My App](https://dontkillmyapp.com/) for your phone maker's
  background-process and battery settings.
- The server uses a foreground service and ongoing notification, attempts to
  recover from crashes and restart after boot while enabled. **Stop** in the
  app or notification disables boot restart until you start it again.
- Keeping the CPU and Wi-Fi awake consumes battery. Uninstalling removes the
  app's private settings and data.
- Without a usable WebView, the app shows an explanation and a button to open
  the dashboard in a browser on the same device using `http://127.0.0.1:7000/`.
  Install or enable Chrome or Android System WebView and reopen DubLift, or use
  the displayed LAN address on another device on the same LAN. Connect to Wi-Fi
  or Ethernet if no LAN address is shown. For dashboard issues on Android 7.1,
  also check for Chrome or Android System WebView updates.
- **Copy debug info** on the fallback screen copies device/provider versions,
  WebView failure details, and recent dashboard events to send to the developer.
  If the dashboard stays blank, tap the dock's status dot to access the same
  button. **Copy** beside the LAN address copies it for use on another device.

## 🛠️ Development

For contributions, see [building and project setup](docs/building.md),
[validation](docs/validation.md), and [releasing](docs/releasing.md).

## 📚 Related projects

[DubLift](https://github.com/joojoooo/DubLift) contains the core server and its
usage documentation. DubLiftApp builds it from source through the pinned
`DubLift/` Git submodule; Android packaging and device guidance live here.

## ☕ Support the project

If you love this project, you can support development here:</br>
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/s/a16a8bed86)

## 📄 License

DubLiftApp uses [GPL-3.0-only](LICENSE). The core server has its own
[GPLv3 license](DubLift/LICENSE). See [third-party notices](THIRD_PARTY.md) for
bundled components and their licenses.
