# Chud Supreme

Chud Supreme is this repository's Fire TV build. It is published separately from earlier CHUD STREAMS (`yungblockchain/Chud-Streams`, application id `app.dial.tv`). This app's id is `app.dial.supreme`, so the two install side by side. The notes below were written for the CHUD STREAMS shell and still describe how the player, guide, and addons work. Where they say `app.dial.tv` or "CHUD STREAMS", this repo means `app.dial.supreme` and "Chud Supreme".

The original product note follows.

# CHUD STREAMS

CHUD STREAMS is a Fire TV build of [M3UAndroid](https://github.com/oxyroid/M3UAndroid) (oxyroid),
reworked for Xtream Codes accounts and the Fire TV remote. Only the TV app (`app/tv`) is rebranded
and changed; the phone app in `app/smartphone` is upstream code and isn't part of the Firestick
build. "Dial" was the working name, and it's still used in code, file names and the app id
(`app.dial.tv`); none of that shows on screen.

To rename it, change `app_name` in `app/tv/src/main/res/values/stings.xml` and `tv_home_title` in
`dial_strings.xml`, then change the banner text in `tools/dial-branding/make_brand.py` and run it
(see "Branding" below).

There's also a native Mac version in `mac/` (Swift and SwiftUI, Intel Macs on macOS 13 Ventura or
later). GitHub builds it automatically; see `mac/README.md` for the download link.

## What's different from upstream

**Sign in on the TV.** Upstream's TV app can only receive an Xtream account pushed from the phone
app. Dial opens on a sign-in screen: server, username, password. Pasting a provider's full M3U
link (`get.php?username=…&password=…`) into the server field fills in the rest. Details are
checked against `player_api.php` before anything is imported, so a typo gets a clear message
(unreachable server, wrong login, expired account) instead of a silent empty library. Live TV,
films and series are then imported by upstream's own `SubscriptionWorker`.

**M3U playlists.** The sign-in form has an "M3U link" switch for plain playlists: the playlist
link, an optional XMLTV guide link, and a name. The guide then shows that playlist's listings
(matched on tvg-id). M3U playlists appear on the Account tab with Reload and Remove. A get.php
link pasted into the M3U field is recognised as Xtream and signs in that way instead, which adds
films, series and catch-up.

**Provider-sized accounts.** Tested with 50,000 live channels, 130,000 films and 20,000 series:
the import downloads and saves them in a few minutes with progress on screen ("45,000 so far"),
tolerates malformed entries and slow servers, and says why if it fails (timeout, connection,
storage). The Library and Guide then load one category at a time, in the provider's order, so
the Fire TV never holds the whole catalogue in memory; playlists over 40,000 entries open on
their first category instead of "All". The Library has a search box across every playlist.

**Play with VLC.** Settings > Player and startup > Play with: the built-in player (Media3
ExoPlayer), VLC, or choose each time. Outside players get the title and the resume position,
and the position they report back is saved so "Resume from" still works. Channels that need DRM
or a media server always use the built-in player.

**Ask Claude.** A tab that uses your own Anthropic API key (get one at console.anthropic.com;
the Fire TV phone app's keyboard can paste it). The key is checked, then stored encrypted with a
key from the Android Keystore and only ever sent to api.anthropic.com. Claude can search your
library, browse categories, see what's on now, read your favourites and start playback, so its
suggestions are things you can actually watch. Models: Haiku 4.5 (fastest), Sonnet 5.5 (default)
and Opus 5.5; chats are billed to your Anthropic account.

**Account tab.** Shows each Xtream login's status, expiry date with days left (amber inside a
week, red once expired), and connections in use out of the maximum. Accounts can be re-checked,
added or removed.

**Player rebuilt for the remote.**
- Controls hide after 5 seconds and come back on any button, without that press also
  triggering the focused button.
- Up/down, CH+/CH−, and next/previous flip channels, with the channel number shown large.
- Play/pause works from the remote's media button at any time.
- Films and series: rewind skips back 10 s, fast-forward skips ahead 30 s, with a progress bar.
- Favourite toggle and a sleep timer (off, 30, 60, 90, 120 min).
- The screen stays awake while playing, so the Fire TV screensaver no longer interrupts streams.
- Back hides the controls first, then leaves the player.

**Films and series (Nuvio-style).** Choosing a film or series opens a details page instead of
playing straight away: backdrop, poster, year, rating, runtime, genre, plot, director and cast
from the provider. Films offer "Resume from 42:10" and "Start over". Series have a season picker
and every episode listed with its plot and length, plus "Continue S2 E5" for the last episode
you opened. Home gains a Continue watching row. Resume positions use upstream's own
continue-watching store.

**Guide and catch-up (TiviMate-style).** The Guide tab opens on a timeline grid: channels down
the side, time across the top, each programme a block as wide as it is long, an amber line at the
current time, and a panel describing whatever's focused. Up/down moves between channels at the
same time of day, left/right moves through time (24 hours back, 24 ahead), and the whole grid
scrolls together. Past programmes that can't be replayed are dimmed; replayable ones carry a
catch-up mark. The older channel-list layout (what's on now on the left, the focused channel's
schedule on the right) is one button away, and the choice is remembered. Both layouts filter by
category. Programmes
the provider has archived are marked Catch up and play from the start with rewind and
fast-forward. Schedules come straight from the Xtream API as you browse, so there's no large
XMLTV download to wait for on a Fire TV Stick.

**Settings (TiviMate-style).** Settings has a "Player and startup" tab: open on Home, the last
channel watched or the Guide; hide controls after 3, 5, 8 or 12 seconds; normal or reversed
channel up/down; channel numbers on or off; channel banner on or off; picture size (fit,
stretch, zoom); guide layout (timeline or channel list); resume on or off; rewind and
fast-forward lengths; and clear continue watching.
Upstream's sources and extensions screen is the second tab.

**4K, HDR and frame rate.** Video now goes through a SurfaceView instead of upstream's
TextureView, so 4K frames go straight to the display hardware and HDR10, HDR10+, HLG and Dolby
Vision reach the TV intact (upstream's TextureView flattened everything to SDR and made the GPU
composite every 4K frame). Settings > Player and startup > Match the video's frame rate switches
the TV to a refresh rate that suits the video (24, 25, 50, 59.94 Hz and so on) to remove judder,
like TiviMate's auto frame rate; it's off by default because the TV blanks briefly when it
switches. 120 Hz isn't possible: the Fire TV Stick 4K Max outputs at most 4K at 60 Hz, and its
decoders top out at 60 fps.

**Markets tab.** A read-only memecoin tracker using DEX Screener's public API (no key needed).
Sections: Trending (most-boosted tokens), Pump.fun, New listings, Watchlist and Search, each
filterable by chain (Solana, Base, Ethereum, BNB Chain). Rows show price, 24-hour change and
market cap; the side panel adds 5-minute, 1-hour and 6-hour changes, fully diluted value,
liquidity, 24-hour volume, buys and sells, pool age, exchange, token address and active boosts.
OK adds a token to the watchlist or removes it. Prices refresh every 30 seconds while the tab is
open, well inside DEX Screener's rate limits. Pump.fun tokens come from the same data (Solana
tokens on pump.fun's exchanges or with pump.fun's "pump" address suffix), because pump.fun and
GMGN don't publish a documented public API; `MarketsApi.kt` is the place to add another source.
Nothing in the tab trades or touches a wallet.

**Jellyfin and Emby.** Built into upstream: Settings > Sources and extensions > Emby / Jellyfin,
choose Jellyfin, then enter the server address, username and password.

**90s cyberpunk anime theme.** Midnight-indigo backgrounds with CRT scanlines, a faint retro
grid floor and a magenta horizon glow; neon cyan for everything the remote can land on, with a
cyan glow when focused; hot magenta for brand moments. Every focusable panel has two chamfered
corners, like a heads-up display. Audiowide, a wide techno face, is used for the wordmark and all
numbers; Atkinson Hyperlegible stays for body text because it reads well from the sofa. The
launch screen spins the logo badge over scanlines with a misregistered magenta-and-cyan wordmark
and the name in katakana. The launch window matches the background, so there's no flash.

**Games tab.** Three small arcade games played with the remote, drawn in the same neon style,
with best scores kept on the device. Snake (arrows steer, speeds up as you eat), Blocks (a
falling-blocks puzzle: left and right move, up turns, down drops faster, OK drops at once, with
a ghost piece and next-piece preview) and Sky Hop (an endless vertical jumper: hold left or right
to steer, go off one side to come back on the other; gaps widen and ledges start moving as you
climb). Back returns to the games menu.

**Packaging.** Own application id (`app.dial.tv`) so it installs next to upstream M3U rather than
over it, optional fixed release signing, and a GitHub Actions workflow that builds the APK.

### Files changed (2026-09-28)

New: `app/tv/src/main/java/com/m3u/tv/XtreamAccounts.kt`, `XtreamScreens.kt`,
`XtreamCatalog.kt`, `DialViewModel.kt`, `DialSettings.kt`, `DialSettingsScreen.kt`,
`DetailsScreen.kt`, `GuideScreen.kt`, `GuideGrid.kt`, `BrandSplash.kt`, `BrandLogo.kt`,
`res/drawable-nodpi/brand_mascot.png`, `tools/dial-branding/logo_source.jpeg`, `MarketsApi.kt`,
`MarketsViewModel.kt`, `MarketsScreen.kt`, `GamesScreen.kt`, `MiniGames.kt`,
`res/values/dial_strings.xml`, `res/values/dial_colors.xml`, `res/drawable-xhdpi/dial_banner.png`,
`res/font/atkinson_hyperlegible_*.ttf`, `res/font/audiowide_regular.ttf`,
`.github/workflows/firestick.yml`, `licenses/`, this file.

Modified: `TvPlayerScreen.kt` (rewritten), `TvStyle.kt`, `TvComponents.kt`, `TvScreens.kt`,
`App.kt`, `AndroidManifest.xml`, `res/values/stings.xml`, `res/values/themes.xml`, launcher
icons, `app/tv/build.gradle.kts`, `baselineprofile/tv/build.gradle.kts`,
`data/.../TvRepositoryImpl.kt` (recognises the new package id as a TV build),
`data/.../service/PlayerManager.kt` and `internal/PlayerManagerImpl.kt` (a `MediaCommand.Url`
play command for catch-up), `.gitignore`.

Removed: upstream's CI workflows (they need upstream's secrets and build the phone app too), the
Inter and Lexend fonts, `fastlane/`, `.github/images/`, `.idea/`. The `parser` and
`native-load-gradle-plugin` git submodules are included as plain folders so the project builds
from the zip.

## Addons, debrid and P2P

The full patch notes for this work, written so another model can change it without guessing, are in [README.md](README.md) under "Patch notes (2026-09-30)" and "If you are another LLM". Read that before editing `app/tv/src/main/java/com/m3u/tv/stremio/`. The short version is below.

The **Addons** tab speaks the Stremio addon protocol (the same one Nuvio uses).

- **Metadata.** One press installs Cinemeta. The Movie Database addon is there too. Any other manifest URL can be pasted.
- **Streams.** Torrentio is installed the same way. If a Real-Debrid token or TorBox key is saved, Torrentio is configured with it so cached links come back first. AIOStreams needs the manifest URL from your own config.
- **Debrid.** Real-Debrid and TorBox tokens are typed on the Addons tab or in Settings, Services. They stay in the encrypted store. Magnets are added and unrestricted on the device; a cached HTTP link is what the player opens.
- **P2P.** On by default. If TorrServe is running (the address is on the Addons tab, usually `http://127.0.0.1:8090`), playback goes through it. Otherwise the built-in engine asks trackers for peers, downloads the video in order, and plays it through a local address. Turn P2P off to require a direct link or a debrid account.

Addons work before an Xtream account is signed in. The hidden "Addons" playlist used for playback does not show up under Live TV.

## Build it

The code hasn't been compiled yet: the tool that wrote it couldn't reach Google's Maven
repository. Expect the first build to possibly flag a few small errors; Android Studio points at
the exact line.

### Option A: Android Studio (simplest)

1. Install the latest stable Android Studio. The project targets SDK 37, so accept the SDK
   updates it offers.
2. File > Open, and pick this folder. Wait for the Gradle sync to finish.
3. In the Build Variants panel, set `app.tv` to `release` (or keep `debug` for testing).
4. Build > Build App Bundle(s) / APK(s) > Build APK(s).
5. The APK is in `app/tv/build/outputs/` (look under `published-apk/release` or `apk/release`).

### Option B: GitHub Actions (no Android Studio needed)

1. Create a new GitHub repository and push this folder to it (GitHub Desktop is easiest).
2. Open the Actions tab, pick "Build CHUD STREAMS for Fire TV", and click Run workflow.
3. When it finishes (around 15–25 minutes), download `chud-streams-apk` from the run's
   Artifacts section and unzip it.

Keep the repository private if you add signing secrets. Dial is GPL-3.0, so if you share the APK
with anyone, you must also make this source available to them.

### Stable signing (recommended)

Android only installs an update if it's signed with the same key as the installed version.
Without a fixed key, CI builds use a throwaway key, so each update means uninstalling first and
losing your accounts and favourites. Create a key once:

```
keytool -genkeypair -v -keystore dial-release.jks -alias dial -keyalg RSA -keysize 2048 -validity 10000
```

For local builds, put `dial-release.jks` in the project root and create
`app/tv/dial-keystore.properties`:

```
storeFile=dial-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=dial
keyPassword=YOUR_KEY_PASSWORD
```

For GitHub Actions, add these repository secrets instead: `DIAL_KEYSTORE_BASE64` (the output of
`base64 -w0 dial-release.jks`, or `base64 -i dial-release.jks` on a Mac), `DIAL_KEYSTORE_PASSWORD`,
`DIAL_KEY_ALIAS`, `DIAL_KEY_PASSWORD`. Both files are git-ignored; back the keystore up somewhere
safe, since losing it means uninstalling to update.

## Install on the Fire TV Stick 4K Max

1. Settings > My Fire TV > About: select the device name seven times to unlock Developer Options.
2. Settings > My Fire TV > Developer Options: turn on ADB debugging, and under
   "Install unknown apps" allow the app you'll install from (Downloader, if using it).
3. Get the APK across. Every successful build publishes the newest APK at a fixed address, so
   the simplest way is to open **Downloader** on the Fire TV and enter:

   `https://github.com/yungblockchain/Chud-MAXXX/releases/latest/download/chud-supreme.apk`

   then choose **Install**. (If you fork or rename the repository, swap in its name.) With a
   computer and adb instead: find the stick's IP under Settings > My Fire TV > About > Network,
   run `adb connect IP:5555` and `adb install -r chud-supreme.apk`, and accept the prompt on the
   TV the first time.
4. Open Chud Supreme from Your Apps & Channels. The logo spins, then the sign-in screen opens.

## Troubleshooting

- **"App not installed" when updating:** the new APK was signed with a different key. Uninstall
  Dial first, then set up stable signing so it doesn't happen again.
- **"App not installed" on first install while upstream M3U TV is installed:** both apps declare
  the same signature-protected permission. Uninstall M3U TV, or rename
  `com.m3u.permission.BIND_EXTENSION_HOST` in `app/tv/src/main/AndroidManifest.xml`.
- **"Couldn't reach that server":** check the port (usually `:8080` or `:80`) and whether the
  address should be `https://`. Some providers block unfamiliar apps; the check sends the user
  agent `Dial/1.0 (Android TV)`, set in `XtreamClient.fetchAccount`.
- **No schedule or no Catch up labels:** both come from the provider. Many providers only
  archive some channels, and some send no programme data at all for smaller channels.
- **Catch up won't play:** Dial uses the common Xtream address
  `/timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/id.ts`. A few panels use
  `streaming/timeshift.php` instead; change `XtreamCatalog.timeshiftUrl` if yours does.
- **Up/down doesn't change channel:** flipping walks the list you opened the channel from (the
  selected playlist in Library, or Favourites). Channels opened from elsewhere play on their own.

## Branding

The logo is the meme face in `tools/dial-branding/logo_source.jpeg`, turned into a neon badge:
cyan linework with a magenta offset, a retro grid floor, scanlines and a cyan ring.
`tools/dial-branding/make_brand.py` builds everything the app uses from that source
(`pip install pillow`, then run it from anywhere): the launcher icons in `res/mipmap-*`, the
320×180 Fire TV banner with the name and katakana in `res/drawable-xhdpi/dial_banner.png`, and
the badge in `res/drawable-nodpi/brand_mascot.png`. The badge sits in the top-left corner, at the
top of the nav rail, turning slowly in 3D (one full turn every nine seconds) over a steady cyan
glow (`BrandLogo.kt`). It pauses while the player or a details page covers the menu and carries
on from the same angle afterwards, and it stays still if the Fire TV's accessibility setting
"Remove animations" is on. The same badge spins on the launch screen (`BrandSplash.kt`). To change the logo, replace `logo_source.jpeg` with
any black-on-white line drawing and run the script. Settings > Player and startup > Spinning
logo on launch turns the launch animation off.

## Licences

CHUD STREAMS is distributed under the GNU GPL v3, like M3UAndroid (see `LICENSE`). Atkinson Hyperlegible
and Audiowide are under the SIL Open Font License (see `licenses/`).
