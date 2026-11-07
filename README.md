# VagiiArk TV (`vagiiark-tv`)

Ultra-fast custom Android TV YouTube client built for low-RAM / MStar Android TVs (Xiaomi MiTV-MSSP2), inspired by **TizenTube** with native Android hardware acceleration.

## Features
- **Zero-Latency Dual-Layer AdBlocker**:
  - **Network Layer (`shouldInterceptRequest`)**: Blocks Google Ads, DoubleClick, PageAd, and ad telemetry before network requests even complete.
  - **InnerTube JSON Hooker (`JSON.parse` & `Response.prototype.json`)**: Strips `adPlacements`, `playerAds`, `adSlots`, `masthead`, and promoted feed items before the player parses the video response.
- **SponsorBlock Integration (`sponsor.ajay.app`)**:
  - Automatically skips `sponsor`, `selfpromo`, `interaction`, `intro`, `outro`, `preview`, and `music_offtopic` segments with millisecond precision and shows an on-screen TV toast with time saved.
- **Instant Video Speed Control (`0.5x` – `4.0x`)**:
  - Change playback speed anytime (`0.5x`, `0.75x`, `1.0x`, `1.25x`, `1.5x`, `1.75x`, `2.0x`, `2.5x`, `3.0x`, `4.0x`) from the Quick Settings Overlay or via remote shortcut keys.
- **60fps MStar Turbo Mode**:
  - Strips GPU-heavy CSS blurs, box-shadows, and ambient canvas effects so D-pad navigation stays locked at 60fps on 1.4 GB RAM sets.
- **GitHub In-App OTA Updater & Live Hotfix Engine**:
  - Checks `https://api.github.com/repos/Vagii-Kavagi/vagiiark-tv/releases/latest` for one-click in-app APK updates.
  - Pulls live JavaScript hotfixes from `hotfix/vagiiark-patch.js` on the `main` branch so selector/adblock updates apply immediately without reinstalling the APK.

## Opening the In-App Quick Menu on Your TV Remote
- **Hold OK (Center D-Pad)** for `0.6s`, or press **MENU / INFO / GUIDE / SETTINGS** on your remote to open the **VagiiArk TV Settings** side panel over any screen or video.

## Building from Source
```powershell
$env:JAVA_HOME="$env:LOCALAPPDATA\Android\jdk17"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon assembleRelease
```
