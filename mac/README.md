# CHUD STREAMS for Mac

A native Mac version of CHUD STREAMS, written in Swift and SwiftUI for macOS Ventura (13) and
later. It's built for a 2017 MacBook Pro (Intel, 8 GB of RAM): Ventura is the newest macOS those
machines run. GitHub builds it for Intel Macs; Apple Silicon Macs run it through Rosetta.

This is a separate app from the Fire TV one: Android apps can't run on a Mac, so it's new code
with the same look, not a port.

## What's in it

### Watching

- **Sources**: any number of Xtream logins and M3U playlists, each with its own programme guide
  (XMLTV, plain or `.gz`). Switch between them at the top of the sidebar. Pasting a `get.php`
  link signs in with Xtream automatically. Passwords are kept in the macOS Keychain.
- **Big playlists**: the full list for each kind (channels, films, series) downloads in one go
  and is cached on disk, so providers with 50,000 channels and 130,000 films work. Tested with a
  mock account that size: loaded in about 20 seconds; searching it takes a fraction of a second.
- **Home**: trending films and series from TMDB, matched to what your provider actually has, then
  Continue watching (the last 10), favourite channels, newly added films and series, and your
  library.
- **Live TV**: categories, channel numbers and logos (animated GIF logos play), what's on now and
  next, with Favourites at the top. Categories can be reordered and hidden (the arrange button
  above the list), separately for each source.
- **Guide**: a TiviMate-style timeline with catch-up for programmes your provider archives.
- **Films and series**: poster grids, and a details page with a moving backdrop, synopsis, a
  slowly scrolling cast row, trailer, IMDb link, Trakt ratings and comments (spoilers blurred),
  and every season and episode. Click an actor for their page: photo, biography, IMDb profile,
  and their other work, with a WATCH badge on anything your provider has.
- **Library and favourites**: save films and series to your library; put channels into
  favourites or your own groups (Sport, Kids, anything), reorder the groups, and play a group in
  multiview.
- **Search** across channels, films and series at once.
- **Right-click (or two-finger click) anything**: play, add to multiview, open in VLC, add to
  your library, favourites or a group, or search for similar titles.

### The player

- **The built-in player (mpv)** plays everything: MKV, MP4, TS, HLS, catch-up, HEVC, AV1, DTS,
  TrueHD and styled subtitles. The Apple player and VLC are still in Settings as options.
- **Mini player**: close the player (or press P) and it keeps playing in a corner while you
  browse. Drag it to any corner; double-click it to go back to full size.
- **Multiview**: four live channels at once. Click a tile to hear it.
- **Subtitles**: forced subtitles (signs and foreign dialogue) turn on by themselves; full
  subtitles only when the audio isn't in your language. Styled (libass) subtitles can be switched
  to plain, with a background box and size. Search and download from OpenSubtitles in the player.
- **Sync sliders** for subtitles and audio, speed, and picture options.
- **HDR and Dolby Vision**: an Advanced (Metal) renderer shows HDR on HDR screens and applies
  Dolby Vision metadata; on ordinary screens it tone-maps to SDR. Automatic picks it on HDR
  screens. Audio passthrough sends Dolby and DTS to a receiver.
- **Up next** for series, resume positions saved every 5 seconds, and live channels retry by
  themselves when a stream drops.

### Extras

- **Ask Claude**: a chat that can search your provider's catalogue, see what's on, read your
  favourites and library, and start playback. Uses your own Anthropic API key.
- **Markets**: a read-only crypto tracker.
  - Memecoins from DEX Screener (Trending, Pump.fun, New), top coins from CoinGecko (or
    CoinMarketCap with your key), the coins Robinhood lists, and Hyperliquid perpetuals.
  - Live candlestick charts (GeckoTerminal, CoinGecko and Hyperliquid), a watchlist, and links
    to DEX Screener, Birdeye, CoinGecko and more.
  - Prices and charts refresh every 30 seconds while the tab is open.
  - Telegram hand-off: copy a token and open your own trading bot, or send it to your own chat
    through your own bot. Nothing in the app trades, signs or touches a wallet.
- **Arcade**: Snake, Blocks and Sky Hop, played with the keyboard.
- **Crash and error reports**: saved compressed, with passwords, keys and usernames removed. Export them as a zip, or have them sent as issues to a GitHub repository of
  your choice (use a private one) with your own token.

## Keys for the online services

TMDB (trending, cast, actor pages), Trakt (ratings and comments), OpenSubtitles, Claude,
CoinMarketCap, GitHub (reports) and Telegram each need your own free key or token. Enter them in
**Settings > Services**. They're saved in the macOS Keychain, never in the app's code or files,
and each is only ever sent to its own service. Everything else works without them.

## Keyboard

| Key | In the player |
| --- | --- |
| Space | Pause and play |
| Left / Right arrow | Skip back / forward (films and series) |
| Up / Down arrow, Page Up / Page Down | Next / previous channel (live), volume (films) |
| C | Channel list (live) |
| V | Multiview (live) |
| N | Next episode |
| P | Mini player |
| F | Full screen |
| M | Mute |
| S / A | Next subtitle / audio track |
| Z / X | Subtitles earlier / later |
| , / . | Audio earlier / later |
| [ / ] / 0 | Slower / faster / normal speed |
| I | Info |
| Esc | Close a panel, then the player |

Anywhere: Command-1 to Command-9 for the sections, Command-F for Search and Command-, for
Settings (see the Go menu), Command-Shift-P to play or
pause, Command-Shift-M for the mini player, Command-Shift-F for the full player,
Command-Shift-V for multiview, and Command-. to stop.

## Download and install

GitHub builds the app automatically from this folder, so there's nothing to compile.

1. On your Mac, open this link to download the app:
   `https://github.com/yungblockchain/Chud-Streams/releases/download/mac-latest/chud-streams-mac.zip`
2. Open the downloaded `chud-streams-mac.zip` (in Downloads) to unzip it, then drag
   **CHUD STREAMS** into your **Applications** folder, replacing the old one if you have it.
3. The first time only: in Applications, **right-click** (or Control-click) CHUD STREAMS, choose
   **Open**, then click **Open** in the warning. macOS asks because the app isn't from the App
   Store or an Apple-registered developer. After that it opens normally.

If macOS says the app "is damaged and can't be opened", run this once in Terminal, then open
it again:

```
xattr -dr com.apple.quarantine "/Applications/CHUD STREAMS.app"
```

The same link always gives the newest version. Your sign-in, favourites and positions from
version 1 carry over.

## Build it yourself (optional)

1. Install Xcode 15.2 or later (or `xcode-select --install` for the command-line tools).
2. In Terminal, go into this `mac` folder: type `cd `, with a space, drag the folder onto the
   Terminal window, and press Return.
3. Run `./build-app.sh --install`. The first build downloads the player library (MPVKit) and
   takes a few minutes; the app then appears in Applications. Without `--install`, it's left in
   the `build` folder.

## Troubleshooting

- **A channel or film won't play**: try another user agent in Settings > Playback > Network
  (VLC and TiviMate are there), or right-click it and open it in VLC.
- **The picture stutters on an older Mac**: keep Hardware decoding on and the renderer on
  Automatic or Standard.
- **Keychain asks to allow access after an update**: each build has a new signature, so macOS
  checks before sharing the saved passwords with it. Click Always Allow.
- **"Couldn't reach that server"**: check the port (usually `:8080` or `:80`) and whether the
  address should start with `https://`.
- **Something broke**: Settings > Reports has the saved report. Export it or send it to your
  GitHub repository.

## Testing

`ChudStreams --selftest-mpv <video>` checks the player draws frames, and `CHUD_TOUR=<folder>`
walks through every screen against the mock server in `Tests/` and saves screenshots there.
GitHub runs both on every build; see `Tests/README.md`.

## Files

- `Sources/ChudStreams/`
  - `App.swift`: app entry, menus, main window and sidebar.
  - `AppModel.swift`: sources, sign-in, resume positions, guide cache and playback requests.
  - `Catalog.swift`, `Network.swift`: big catalogue downloads, the disk cache, search, and the
    M3U and XMLTV parsers.
  - `XtreamAPI.swift`: the Xtream client.
  - `MPV.swift`, `Playback.swift`, `PlayerViews.swift`: the mpv player, mini player, multiview
    and player controls.
  - `Preferences.swift`, `Settings.swift`: playback settings and the Settings screen.
  - `Home.swift`, `Browse.swift`, `Guide.swift`, `Details.swift`, `Library.swift`,
    `Search.swift`: the main screens.
  - `Services.swift`, `Secrets.swift`: TMDB, Trakt and OpenSubtitles, and the Keychain.
  - `Claude.swift`, `Markets.swift`, `MarketCharts.swift`, `Games.swift`: Ask Claude, Markets
    and the Arcade.
  - `Reports.swift`: crash and error reports.
  - `Images.swift`, `Components.swift`, `Theme.swift`: images and logos, shared views, and the
    neon look.
  - `SelfTest.swift`, `Tour.swift`: the automated checks.
- `Resources/`: fonts, the logo badge and the app icon.
- `Tests/`: the mock server and sample videos used by the checks.
- `build-app.sh`: compiles and packages the app.
