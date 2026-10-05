#!/bin/bash
# Runs inside the Android TV emulator job (.github/workflows/tv-screenshots.yml).
# Installs chud-supreme.apk, opens each main screen with remote-control key presses, and saves
# screenshots plus the crash log into the output folder.
#   usage: tv-screenshots.sh <output-dir> <api-level>
#
# Each tab is opened by relaunching the app with the "destination" launch extra
# (MainActivity reads it), so one wrong key press can't derail the rest of the walkthrough.
set -u
OUT="$1"
API="$2"
PKG=app.dial.supreme
ACTIVITY=com.m3u.tv.MainActivity
mkdir -p "$OUT"

UP=19; DOWN=20; LEFT=21; RIGHT=22; OK=23; BACK=4

shot() {
    adb exec-out screencap -p > "$OUT/api$API-$1.png"
    echo "screenshot: $1"
}
alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }
press() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.7; done; }
keyboard_up() { adb shell dumpsys input_method | grep -q "mInputShown=true"; }
# Hide the on-screen keyboard if a text field brought it up (Back only closes the keyboard).
hide_keyboard() {
    if keyboard_up; then
        adb shell input keyevent $BACK
        sleep 1
    fi
}
check() {
    if ! alive; then
        echo "::warning::CHUD STREAMS is not running after: $1 (API $API)"
        return 1
    fi
    return 0
}
# Restart the app on one tab: search, home, live, films, series, guide, favorites, mylibrary,
# markets, claude, games, account, settings.
# The launch animation takes about 3.5 seconds, so the wait includes it.
# Text boxes open the keyboard only when OK is pressed on them; a keyboard showing on arrival is a bug.
open_tab() {
    adb shell am start -S -W -n "$PKG/$ACTIVITY" --es destination "$1" >/dev/null
    sleep "${2:-8}"
    if keyboard_up; then
        echo "::error title=Keyboard opened by itself on API $API::The on-screen keyboard came up on the $1 tab without OK being pressed."
        shot "keyboard-by-itself-$1"
        hide_keyboard
    fi
}
# Type into the focused text field: OK starts typing (and opens the keyboard), then the keyboard is
# closed again so the key presses reach the field rather than the keyboard.
# A few characters at a time: on Android 11 a long burst loses its tail when the keyboard
# pops back up part-way through.
type_text() {
    local text="$1" i
    press $OK
    sleep 1
    hide_keyboard
    # Clear whatever the field already holds (a fresh install pre-fills the login).
    adb shell input keyevent 123 $(printf '67 %.0s' $(seq 1 90))
    sleep 1
    for (( i = 0; i < ${#text}; i += 4 )); do
        adb shell input text "${text:i:4}"
        sleep 0.4
        hide_keyboard
    done
    sleep 1
    hide_keyboard
}
# Sign in to the test Xtream server the workflow starts on the runner (the emulator reaches the
# runner at 10.0.2.2). Returns 1 if that server isn't running.
#   usage: sign_in <username> <password> <screenshot-prefix>
sign_in() {
    if ! curl -sf http://127.0.0.1:8080/health >/dev/null; then
        echo "No test Xtream server, so the signed-in screens are skipped."
        return 1
    fi
    open_tab account 8
    shot "$3-sign-in-empty"
    type_text "http://10.0.2.2:8080"
    press $DOWN; hide_keyboard
    type_text "$1"
    press $DOWN; hide_keyboard
    type_text "$2"
    shot "$3-sign-in-filled"
    # Past the optional name field to the Sign in button.
    press $DOWN; hide_keyboard
    press $DOWN; hide_keyboard
    local starts
    starts=$(adb logcat -d | grep -c "Starting work for com.m3u.data.worker.SubscriptionWorker")
    press $OK
    sleep 25
    # The emulator's key injection occasionally garbles a field; if no import started, the form
    # is still showing its message with focus on Sign in, so try once more.
    if [ "$(adb logcat -d | grep -c "Starting work for com.m3u.data.worker.SubscriptionWorker")" -le "$starts" ]; then
        shot "$3-first-try"
        press $OK
        sleep 25
    fi
    shot "$3-signed-in"
    # The account check and the channel import are separate steps; flag a failed import.
    if adb logcat -d | grep -q "Worker result FAILURE .*SubscriptionWorker"; then
        echo "::error title=Playlist import failed on API $API::Signed in to the test server, but loading its channels failed. See logcat-api$API.txt."
    fi
}

adb install -r chud-supreme.apk || { echo "::error::Install failed on API $API"; exit 0; }
adb logcat -c
# Room for a long session's worth of log (the big import is chatty).
adb logcat -G 16M || true
adb shell am start -n "$PKG/$ACTIVITY"
sleep 1.6; shot 01-launch-logo
sleep 7;   shot 02-first-screen

if check "launch"; then
    # The sign-in form: focus on the first box, and no keyboard until OK is pressed.
    if keyboard_up; then
        echo "::error title=Keyboard opened by itself on API $API::The keyboard came up on the first sign-in screen without OK being pressed."
    fi
    # The sign-in form with the keyboard closed and focus on the button.
    hide_keyboard
    for _ in 1 2 3 4 5; do press $DOWN; hide_keyboard; done
    shot 03-sign-in-button

    open_tab markets 12;  shot 04-markets
    press $RIGHT $DOWN $DOWN; sleep 2; shot 05-markets-token
    check "markets"

    open_tab games 8;     shot 06-games
    # The screen puts focus on the first game: open it, start it, let it run for a moment.
    shot 07-games-focus
    press $OK; sleep 2;   shot 08-snake-ready
    press $OK; sleep 3;   shot 09-snake
    # Back to the menu (focus returns to Snake), then the block game.
    press $BACK; sleep 2
    press $RIGHT $OK; sleep 2; press $OK; sleep 4; shot 10-blocks
    press $BACK; sleep 2
    press $RIGHT $OK; sleep 2; press $OK; sleep 3; shot 11-sky-hop
    press $BACK; sleep 1
    check "games"

    open_tab settings 8;  shot 12-settings
    press $RIGHT; for _ in 1 2 3 4 5 6 7 8; do press $DOWN; done; sleep 1; shot 13-settings-more
    for _ in $(seq 1 22); do press $DOWN; done; sleep 1; shot 13b-settings-player-buttons
    for _ in $(seq 1 15); do press $DOWN; done; sleep 1; shot 13c-settings-party
    # The settings tabs: Appearance (skins), Playback and Services.
    # Focus starts on the first tab; Appearance is the second.
    open_tab settings 8; press $RIGHT $OK; sleep 2; shot 15-settings-appearance
    # Down to the skin row and along it: each skin applies as it's chosen.
    press $DOWN; sleep 1; press $RIGHT $OK; sleep 2; shot 15a-skin-charcoal
    press $RIGHT $OK; sleep 2; shot 15b-skin-glass
    press $RIGHT $RIGHT $RIGHT $RIGHT $OK; sleep 2; shot 15c-skin-neon-city
    open_tab home 8; shot 15d-home-neon-city
    open_tab settings 8; press $RIGHT $OK; sleep 1; press $DOWN; sleep 1; press $OK; sleep 2; shot 15e-skin-black-again
    # Font and focus rows further down.
    for _ in 1 2 3 4 5 6 7; do press $DOWN; done; sleep 1; shot 15f-appearance-type
    open_tab settings 8; press $RIGHT $RIGHT $OK; sleep 2; shot 15g-settings-playback
    press $RIGHT $OK; sleep 2; shot 16-settings-services
    check "settings"

    open_tab guide 8;     shot 14-guide
    check "settings and guide"

    # Signed in to the test server: home, the side menu, live TV, the guide and the account list.
    if sign_in m3u m3u 17 && check "sign-in"; then
        open_tab home 10;     shot 19-home
        # Hold OK on the first channel of the row under the hero: the item menu.
        press $DOWN; sleep 1
        adb shell input keyevent --longpress $OK; sleep 2; shot 25-hold-menu
        press $BACK; sleep 1
        # The Menu key opens the hidden side menu with its labels; Left from the hero does too.
        press $UP; adb shell input keyevent 82; sleep 1; shot 26-menu-open
        press $DOWN $DOWN; sleep 1; shot 27-menu-moved
        open_tab live 10;     shot 20-live
        press $RIGHT; sleep 2; shot 20a-live-grid
        open_tab search 8;    shot 28-search
        open_tab guide 12;    shot 21-guide
        press $RIGHT; sleep 3; shot 22-guide-focus
        press $DOWN; sleep 3;  shot 23-guide-next
        open_tab account 8;   shot 24-account
    fi
    check "walkthrough"

    # A provider-sized account: 50k channels, 130k films (some malformed, slow to start), 20k
    # series. Start from a clean app, sign in, and wait for the import to finish.
    if curl -sf http://127.0.0.1:8080/health >/dev/null; then
        adb shell pm clear "$PKG" >/dev/null
        count_results() { adb logcat -d | grep -c "Worker result $1 .*SubscriptionWorker"; }
        successes=$(count_results SUCCESS)
        failures=$(count_results FAILURE)
        started=$(date +%s)
        if sign_in big big 30 && check "big sign-in"; then
            result=""
            for _ in $(seq 1 120); do
                if [ "$(count_results SUCCESS)" -gt "$successes" ]; then result=ok; break; fi
                if [ "$(count_results FAILURE)" -gt "$failures" ]; then result=failed; break; fi
                if ! alive; then result=died; break; fi
                sleep 5
            done
            seconds=$(( $(date +%s) - started ))
            shot 31-big-after-import
            case "$result" in
                ok) echo "::notice title=Big account on API $API::50k channels, 130k films and 20k series loaded in ${seconds}s." ;;
                failed) echo "::error title=Big account import failed on API $API::See logcat-api$API.txt (SubscriptionWorker)." ;;
                died) echo "::error title=App died during the big import on API $API::See logcat-api$API.txt." ;;
                *) echo "::error title=Big account import still running on API $API::Not finished after ${seconds}s." ;;
            esac
            if [ "$result" = ok ]; then
                # Films: categories on the left (focus starts there), posters on the right.
                open_tab live 12;  shot 32-big-live
                open_tab films 12; shot 33-big-films
                press $DOWN $DOWN; sleep 2; shot 33a-big-films-category
                press $RIGHT; sleep 2; shot 33b-big-films-grid
                press $DOWN $DOWN $RIGHT; sleep 2; shot 33c-big-films-scrolled
                adb shell input keyevent --longpress $OK; sleep 2; shot 33d-film-hold-menu
                press $BACK; sleep 1
                # Open a film and play it (the test server sends a real 30-second clip).
                errors_before=$(adb logcat -d | grep -c "ExoPlayerImplInternal: Playback error")
                press $OK; sleep 6; shot 33e-film-details
                press $OK; sleep 12; shot 33f-film-playing
                errors_after=$(adb logcat -d | grep -c "ExoPlayerImplInternal: Playback error")
                if curl -sf "http://127.0.0.1:8080/movie/big/big/1.mp4" | head -c 64 | grep -q ftyp; then
                    if [ "$errors_after" -gt "$errors_before" ]; then
                        echo "::error title=Film did not play on API $API::The player reported an error for the test film. See logcat-api$API.txt."
                    else
                        echo "::notice title=Film played on API $API::No player errors while the test film played."
                    fi
                fi
                check "film playback"
                # Player extras: the cursor and picture on the progress bar, stats for nerds,
                # the options panel (subtitle style, skip markers, watch party).
                press $UP; sleep 1; press $UP; sleep 1; press $RIGHT $RIGHT; sleep 3; shot 33g-seek-preview
                press $BACK; sleep 1; press $DOWN; sleep 1
                for _ in 1 2 3 4 5 6 7; do press $RIGHT; done; press $OK; sleep 3; shot 33h-stats
                press $OK; sleep 1
                adb shell input keyevent 82; sleep 2; shot 33i-player-options
                for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do press $DOWN; done; sleep 1; shot 33j-player-options-more
                press $BACK; sleep 1
                check "player extras"
                press $BACK; sleep 2; press $BACK; sleep 2
                # Series: into the first show's page.
                open_tab series 12; shot 37-big-series
                press $RIGHT $OK; sleep 6; shot 38-series-details
                # Search across everything.
                open_tab search 10
                type_text "Film%s12345"   # %s is a space for "adb shell input text"
                sleep 4; press $DOWN; sleep 1; shot 34-big-search
                open_tab guide 14;   shot 35-big-guide
                open_tab home 10;    shot 36-big-home
                press $DOWN $DOWN $DOWN; sleep 2; shot 36a-big-home-doors
            fi
            if adb logcat -d | grep -q "OutOfMemoryError"; then
                echo "::error title=Out of memory on API $API::The big account ran the app out of memory."
            fi
        fi

        # A plain M3U playlist with an XMLTV guide, added from the sign-in form's M3U mode.
        adb shell pm clear "$PKG" >/dev/null
        m3u_before=$(count_results SUCCESS)
        open_tab account 8
        press $UP $RIGHT $OK; sleep 1          # the "M3U link" switch above the fields
        press $DOWN
        type_text "http://10.0.2.2:8080/playlist/live.m3u"
        press $DOWN; hide_keyboard
        type_text "http://10.0.2.2:8080/epg.xml"
        shot 40-m3u-form
        press $DOWN; hide_keyboard             # name
        press $DOWN; hide_keyboard             # Add playlist
        press $OK
        sleep 20; shot 41-m3u-added
        if [ "$(count_results SUCCESS)" -le "$m3u_before" ]; then
            echo "::error title=M3U playlist did not load on API $API::See logcat-api$API.txt."
        fi
        open_tab guide 14;   shot 42-m3u-guide
        open_tab account 8;  shot 43-m3u-accounts
        open_tab claude 8;   shot 44-claude-setup
        open_tab mylibrary 8; shot 45-my-library
        open_tab markets 12; press $RIGHT $RIGHT $OK; sleep 10; shot 46-markets-robinhood
        check "m3u and claude"
    fi
fi

adb logcat -d > "$OUT/logcat-api$API.txt"
adb logcat -d -b crash > "$OUT/crash-api$API.txt" 2>/dev/null || true
# Keep the crash itself easy to read in the run summary.
# Only this app's crashes count (the emulator's own TV apps crash now and then).
if grep -q "Process: $PKG, PID" "$OUT/logcat-api$API.txt"; then
    grep -B 1 -A 40 "Process: $PKG, PID" "$OUT/logcat-api$API.txt" | head -60 > "$OUT/crash-summary-api$API.txt"
    python3 - "$OUT/crash-summary-api$API.txt" "$API" <<'PY'
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
body = "\n".join(line[:300] for line in text.splitlines()[:60])
body = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title=Crash on API {sys.argv[2]}::{body}")
PY
else
    echo "No crash on API $API."
fi
# GitHub won't take empty files as release assets (an empty crash log means no crash).
find "$OUT" -type f -size 0 -delete
ls -la "$OUT"
