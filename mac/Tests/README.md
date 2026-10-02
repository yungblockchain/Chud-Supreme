# CHUD STREAMS test server

`mock_server.py` is a single-file fake of every network service the Mac app uses, so GitHub can take screenshots and run checks without real accounts or keys. It needs only the system `python3` (standard library, Python 3.8 or later).

```sh
python3 Tests/mock_server.py --port 8080 [--big] [--fixtures DIR] [--host 127.0.0.1] [--vod-delay 12] [--verbose]
curl http://127.0.0.1:8080/health   # -> ok
```

- `--fixtures` is the folder with `sample.mkv`, `sample.mp4` and `sample.ts`. It defaults to `Tests/fixtures`.
- The `big`/`big` account always works at provider size. `--big` only prints its sizes at start-up.
- `--vod-delay` sets how long the big account's full film list waits before it starts. The default is 12 seconds.
- Logging is off unless you pass `--verbose`.

## Point the app at it

| Variable | Value |
| --- | --- |
| `CHUD_TMDB_API` | `http://127.0.0.1:8080/tmdb/3` |
| `CHUD_TMDB_IMAGES` | `http://127.0.0.1:8080/tmdbimg` |
| `CHUD_TRAKT_API` | `http://127.0.0.1:8080/trakt` |
| `CHUD_OPENSUBS_API` | `http://127.0.0.1:8080/opensubs/api/v1` |
| `CHUD_CLAUDE_API` | `http://127.0.0.1:8080/claude` |
| `CHUD_GITHUB_API` | `http://127.0.0.1:8080/github` |
| `CHUD_TELEGRAM_API` | `http://127.0.0.1:8080/telegram` |

These services accept any key or token. The provider server is `http://127.0.0.1:8080`, with the login `demo`/`demo`. You can also use the M3U link `http://127.0.0.1:8080/get.php?username=demo&password=demo&type=m3u_plus&output=ts`, or the plain playlist `http://127.0.0.1:8080/playlist.m3u`.

## Accounts

- **demo/demo** has a small catalogue with full details:
  - 40 live channels in 6 categories. The logo for "UK: Pulse Music Hits" is the animated GIF.
  - 30 films in 5 categories, as mkv and mp4.
  - 10 series in 4 categories, each with 2–3 seasons of 4–8 episodes.
- **big/big** is provider-sized: 50,000 channels, 130,000 films and 20,000 series in 60/80/40 categories.
  - Entries are generated from their index and streamed in chunks, so the server stays at about 30 MB.
  - The full film list (with no `category_id`) waits 12 seconds before sending its first byte.
  - About 1 film in 10,000 is broken on purpose: its `stream_id` is `""`, `null` or `"n/a"`.
- A wrong login returns `{"user_info":{"auth":0}}` from the API and 403 from streams, `get.php` and `xmltv.php`.

The film, series and channel titles are the same on every endpoint. For example, "Neon Runner (2021)" is Xtream `stream_id` 2001 and TMDB id 910001, and on Trakt it is `neon-runner-2021`.

## Endpoints

**Xtream**

- `/player_api.php` (GET, or a form POST)
  - Account info: no `action`.
  - Categories: `get_live_categories`, `get_vod_categories`, `get_series_categories`.
  - Lists: `get_live_streams`, `get_vod_streams` and `get_series`, each with an optional `category_id`.
  - Details: `get_vod_info&vod_id=` and `get_series_info&series_id=`.
  - Guide: `get_short_epg&stream_id=&limit=`, which returns the current programme and the next ones (`limit` entries in all, 4 by default), and `get_simple_data_table&stream_id=`.
- `/xmltv.php?username=&password=` returns XMLTV for the demo channels.
- `/get.php?username=&password=&type=m3u_plus&output=ts|m3u8` returns the account as an M3U. For big it is streamed.

**Streams** (all with `Range` and `HEAD` support)

| Path | Serves |
| --- | --- |
| `/live/<u>/<p>/<id>.ts` | `sample.ts` |
| `/live/<u>/<p>/<id>.m3u8` | An HLS playlist of two segments: `/hls/seg0.ts` and `/hls/seg1.ts` |
| `/movie/<u>/<p>/<id>.mkv` or `.mp4` | `sample.mkv` or `sample.mp4` |
| `/series/<u>/<p>/<id>.<ext>` | `sample.mkv` |
| `/timeshift/<u>/<p>/<minutes>/<YYYY-MM-DD:HH-MM>/<id>.ts` | `sample.ts` |
| `/stream/<n>.ts`, `/vod/<n>.mkv` | Streams for the plain playlist |

**Guide and plain playlist**

- `/playlist.m3u` has 25 channels and 5 films. Its header points to `url-tvg="…/epg.xml.gz"`.
- `/epg.xml` and `/epg.xml.gz` cover every channel, from 12 hours ago to 24 hours ahead.
- Programmes are 30, 60 or 90 minutes long. The schedule is the same on the Xtream, XMLTV and gzip endpoints.
- On the Xtream endpoints, programmes that have ended are marked `has_archive: 1`.

**Images**

- `/images/<name>.png` is generated on request and depends only on the name:
  - Names starting with `poster-`, `film-` or `series-` are 400×600.
  - Names starting with `backdrop-` are 1280×720.
  - Any other name is 256×256.
- `/images/anim.gif` is a 96×96 animated GIF with 6 frames that loops forever.
- `/tmdbimg/<size>/<file>` returns the same images.

**Fake services**

| Base path | Endpoints |
| --- | --- |
| `/tmdb/3` | `trending/all/week`, `search/movie`, `search/tv`, `search/multi`, `movie/<id>`, `tv/<id>`, `person/<id>`, `configuration` |
| `/trakt` | `search/tmdb/<id>?type=movie\|show`, `movies\|shows/<slug>/ratings`, `…/comments/likes`, and the same for `shows/<slug>/seasons/<s>/episodes/<e>/…` |
| `/opensubs/api/v1` | `GET subtitles`, `POST login`, `POST download`. `/subs/<id>.srt` serves the downloaded file. |
| `/claude/v1` | `GET models` and `POST messages` (see below) |
| `/github` | `POST repos/<owner>/<repo>/issues` returns 201 with `html_url` set to `/issues/<n>` |
| `/telegram` | `bot<token>/getUpdates` (chat 424242), `bot<token>/sendMessage` and `bot<token>/getMe` |

TMDB honours `append_to_response`. TMDB ids that aren't in the catalogue still return made-up details, so every cast and credit link opens. Searches only match demo titles.

`POST messages` answers in two steps:

1. When the last user message is a plain string, it replies with a `tool_use` block that calls `search_catalog` with `{"query":"neon"}`.
2. When the last user message contains `tool_result` blocks, it replies with text that names "Neon Runner (2021)".

`"stream": true` returns the same reply as server-sent events.
