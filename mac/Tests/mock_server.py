#!/usr/bin/env python3
"""
CHUD STREAMS mock server.

One self-contained file (Python 3 standard library only, 3.8+) that stands in for every
network service the Mac app talks to, so GitHub can take screenshots and run checks without
real accounts or keys:

  * an Xtream Codes panel (player_api.php, get.php, xmltv.php, live/movie/series/timeshift streams)
  * a plain M3U playlist with an XMLTV guide (plain and gzip)
  * generated PNG artwork and an animated GIF logo
  * fake TMDB, Trakt, OpenSubtitles, Claude (Anthropic Messages API), GitHub and Telegram APIs

    python3 mock_server.py --port 8080 [--big] [--fixtures DIR] [--host 127.0.0.1] [--verbose]

Accounts: demo/demo (small, rich data) and big/big (provider-sized, generated lazily and
streamed). See README.md next to this file for the endpoint list and the CHUD_* variables.
"""

import argparse
import base64
import colorsys
import gzip
import json
import math
import os
import random
import re
import struct
import sys
import threading
import time
import traceback
import zlib
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlsplit
from xml.sax.saxutils import escape as xml_escape

try:
    from zoneinfo import ZoneInfo

    LONDON = ZoneInfo("Europe/London")
except Exception:  # no tz database: fall back to UTC, the timestamps stay correct
    LONDON = timezone.utc


# ---------------------------------------------------------------------------------------------
# Settings (filled in by main())
# ---------------------------------------------------------------------------------------------

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
VERBOSE = False
BIG_VOD_DELAY = 12.0  # seconds before the first byte of the big account's full film list

ACCOUNTS = {"demo": "demo", "big": "big"}

# Provider-sized account.
BIG_LIVE, BIG_VOD, BIG_SERIES = 50_000, 130_000, 20_000
BIG_LIVE_BASE, BIG_VOD_BASE, BIG_SERIES_BASE = 100_000, 200_000, 400_000
BIG_EPISODE_BASE = 10_000_000


def valid_login(user, password):
    return user in ACCOUNTS and ACCOUNTS[user] == password


# ---------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------


def now():
    return int(time.time())


def day_floor(t):
    return t - t % 86400


def local_str(t):
    return datetime.fromtimestamp(t, LONDON).strftime("%Y-%m-%d %H:%M:%S")


def xmltv_time(t):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))


def iso_z(t):
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime(t))


def b64(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def slugify(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def seeded(*parts):
    """A random generator that gives the same numbers for the same parts on every run."""
    return random.Random("|".join(str(p) for p in parts))


def attr(value):
    return xml_escape(str(value), {'"': "&quot;"})


def php_json(obj):
    """Compact JSON with escaped slashes, the way PHP panels send it (still valid JSON)."""
    return json.dumps(obj, separators=(",", ":")).replace("/", "\\/")


def normalize_title(text):
    text = re.sub(r"\(\d{4}\)", " ", text or "")
    text = re.sub(r"[^a-z0-9]+", " ", text.lower())
    return " ".join(text.split())


# ---------------------------------------------------------------------------------------------
# Demo catalogue: one set of titles used by every endpoint
# ---------------------------------------------------------------------------------------------

LIVE_CATEGORIES = [
    ("1", "UK | Entertainment"),
    ("2", "UK | News"),
    ("3", "UK | Sport"),
    ("4", "UK | Movies"),
    ("5", "UK | Kids"),
    ("6", "UK | Music & Documentary"),
]
VOD_CATEGORIES = [
    ("11", "EN | Action & Adventure"),
    ("12", "EN | Sci-Fi & Fantasy"),
    ("13", "EN | Drama"),
    ("14", "EN | Comedy"),
    ("15", "EN | Thriller & Horror"),
]
SERIES_CATEGORIES = [
    ("21", "EN | Drama Series"),
    ("22", "EN | Crime & Mystery"),
    ("23", "EN | Sci-Fi & Fantasy Series"),
    ("24", "EN | Comedy Series"),
]

# name, EPG id, category, guide theme
_CHANNEL_ROWS = [
    ("UK: Neon News HD", "neon.news", "2", "news"),
    ("UK: Global 24 News", "global24.news", "2", "news"),
    ("UK: Metro News London", "metro.london", "2", "news"),
    ("UK: Market Watch", "market.watch", "2", "news"),
    ("UK: Weather Now", "weather.now", "2", "news"),
    ("UK: Parliament Live", "parliament.live", "2", "news"),
    ("UK: Crown One HD", "crown.one", "1", "entertainment"),
    ("UK: Crown One +1", "crown.one.plus1", "1", "entertainment"),
    ("UK: Crown Two HD", "crown.two", "1", "entertainment"),
    ("UK: Brightside TV", "brightside.tv", "1", "entertainment"),
    ("UK: Channel Vista HD", "channel.vista", "1", "drama"),
    ("UK: Gold Coast Drama", "goldcoast.drama", "1", "drama"),
    ("UK: Replay Comedy", "replay.comedy", "1", "comedy"),
    ("UK: Lifestyle Plus", "lifestyle.plus", "1", "lifestyle"),
    ("UK: Real Stories", "real.stories", "1", "docs"),
    ("UK: Arena Sports 1 HD", "arena.sports1", "3", "sport"),
    ("UK: Arena Sports 2 HD", "arena.sports2", "3", "sport"),
    ("UK: Arena Football HD", "arena.football", "3", "football"),
    ("UK: Racing Live", "racing.live", "3", "sport"),
    ("UK: Fight Night TV", "fightnight.tv", "3", "sport"),
    ("UK: Cricket Central", "cricket.central", "3", "sport"),
    ("UK: Golf Green HD", "golf.green", "3", "sport"),
    ("UK: Motor Sport Max", "motorsport.max", "3", "sport"),
    ("UK: Cinema Premiere HD", "cinema.premiere", "4", "movies"),
    ("UK: Cinema Action", "cinema.action", "4", "movies"),
    ("UK: Cinema Classics", "cinema.classics", "4", "movies"),
    ("UK: Thriller Zone", "thriller.zone", "4", "movies"),
    ("UK: Family Movies", "family.movies", "4", "movies"),
    ("UK: Tiny Toons TV", "tinytoons.tv", "5", "kids"),
    ("UK: Kidz Club", "kidz.club", "5", "kids"),
    ("UK: Cartoon Carnival", "cartoon.carnival", "5", "kids"),
    ("UK: Junior Science", "junior.science", "5", "kids"),
    ("UK: Pulse Music Hits", "pulse.music", "6", "music"),  # animated GIF logo
    ("UK: Rewind 90s", "rewind.90s", "6", "music"),
    ("UK: Chill Beats TV", "chill.beats", "6", "music"),
    ("UK: Wild Planet HD", "wild.planet", "6", "docs"),
    ("UK: History Vault", "history.vault", "6", "docs"),
    ("UK: Science Lab HD", "science.lab", "6", "docs"),
    ("UK: Travel Bug", "travel.bug", "6", "lifestyle"),
    ("UK: Engineered", "engineered.tv", "6", "docs"),
]
ANIMATED_LOGO_CHANNEL = "pulse.music"

# title, year, category, genres, rating, minutes, container, tagline, plot
_FILM_ROWS = [
    ("Neon Runner", 2021, "12", ["Science Fiction", "Action"], 7.8, 118, "mkv",
     "The city never sleeps. Neither does she.",
     "A courier with a stolen memory chip races across a rain-soaked megacity while every screen in town hunts for her face."),
    ("The Glass Orchard", 2019, "13", ["Drama"], 7.4, 124, "mp4",
     "Some harvests take a lifetime.",
     "Three estranged sisters return to their late mother's greenhouse farm in Kent and find the letters she never sent."),
    ("Midnight Protocol", 2023, "15", ["Thriller"], 7.1, 109, "mkv",
     "Trust no one after 00:00.",
     "A night-shift analyst at a London bank spots a transfer that should not exist, and has until dawn to prove it before she is framed for it."),
    ("Harbour Lights", 2018, "13", ["Drama", "Romance"], 6.9, 102, "mp4",
     "Every light is someone waiting.",
     "A ferry captain and a visiting photographer spend one stormy summer on a Hebridean island that is about to lose its only harbour."),
    ("Iron Meridian", 2020, "11", ["Action", "Adventure"], 7.0, 131, "mkv",
     "Hold the line.",
     "A demolition expert leads a ragged crew across a frozen border to rescue the engineers who built the world's longest bridge."),
    ("Paper Moons", 2017, "14", ["Comedy"], 6.8, 94, "mp4",
     "Fake it till you make it big.",
     "Two failing stand-up comics pose as a famous double act and accidentally book a sold-out national tour."),
    ("The Last Lighthouse", 2022, "15", ["Mystery", "Thriller"], 7.3, 112, "mkv",
     "The light went out. They didn't.",
     "When a lighthouse keeper vanishes from a locked tower, a retired detective and her grandson follow a trail of logbook codes."),
    ("Velvet Static", 2024, "12", ["Science Fiction", "Drama"], 7.6, 121, "mkv",
     "Tune in. Don't look away.",
     "A late-night radio host starts receiving calls from listeners who claim to be living one week in the future."),
    ("Borrowed Thunder", 2016, "11", ["Action"], 6.5, 105, "mp4",
     "One last job. One very loud getaway.",
     "A retired stunt driver is pulled back in for a heist that only works if it happens during a thunderstorm."),
    ("Quiet Hours", 2021, "15", ["Horror"], 6.7, 97, "mkv",
     "Don't make a sound after nine.",
     "A family moves into a converted care home where the building itself seems to enforce the old curfew."),
    ("Salt & Saffron", 2019, "14", ["Comedy", "Drama"], 7.2, 108, "mp4",
     "Family recipes. Family secrets.",
     "A burnt-out chef returns to Leicester to save her father's curry house and ends up competing against him on live TV."),
    ("Northbound", 2015, "11", ["Adventure", "Drama"], 7.0, 116, "mkv",
     "The road is the only way home.",
     "A young woman and her grandfather drive a vintage camper van from Cornwall to John o' Groats to keep an old promise."),
    ("The Cartographer's Daughter", 2020, "13", ["Drama", "History"], 7.5, 133, "mkv",
     "She mapped the world they tried to hide.",
     "In 1850s Lisbon, a mapmaker's daughter secretly finishes her father's charts of a coast the navy wants kept blank."),
    ("Echo Chamber", 2022, "15", ["Thriller"], 6.9, 101, "mp4",
     "Every voice is lying.",
     "A podcast producer realises the anonymous source behind her hit true-crime series might be the killer."),
    ("Signal Lost", 2018, "12", ["Science Fiction"], 6.6, 99, "mkv",
     "Out here, silence is an answer.",
     "The crew of a deep-space relay station lose contact with Earth and must decide whether the last message was a warning."),
    ("Kingdom of Rust", 2023, "12", ["Fantasy", "Adventure"], 7.2, 142, "mkv",
     "Every crown corrodes.",
     "A scrapyard mechanic discovers she is the heir to a clockwork kingdom that is slowly seizing up."),
    ("Weekend at Wexford", 2017, "14", ["Comedy"], 6.4, 92, "mp4",
     "What happens in Wexford gets posted online.",
     "A stag weekend in Ireland goes wrong when the groom's gran insists on coming along."),
    ("Cold Harbour", 2021, "15", ["Crime", "Thriller"], 7.4, 117, "mkv",
     "The docks keep their secrets.",
     "An undercover customs officer inside a Liverpool smuggling ring has to choose between her badge and her brother."),
    ("Starfall Academy", 2024, "12", ["Fantasy", "Family"], 7.0, 110, "mp4",
     "Class is now in orbit.",
     "A boarding school for young astronomers discovers that one of its falling stars has landed on the hockey pitch."),
    ("The Understudy", 2016, "14", ["Comedy", "Drama"], 6.9, 103, "mkv",
     "Break a leg. Not literally.",
     "When a West End lead goes missing on opening night, his nervous understudy has one evening to become him."),
    ("Blackwater Run", 2019, "11", ["Action", "Thriller"], 6.8, 111, "mkv",
     "The river decides who gets out.",
     "A white-water guide must lead a group of strangers downstream after they witness a crime in the canyon."),
    ("Silver Lanterns", 2022, "13", ["Drama", "Romance"], 7.3, 119, "mp4",
     "Light the way back.",
     "Two former sweethearts meet again at the lantern festival in their home town, twenty years after one of them left without a word."),
    ("Deep Field", 2020, "12", ["Science Fiction", "Mystery"], 7.7, 126, "mkv",
     "Look closer.",
     "An astronomer finds a pattern in the oldest light in the universe, and someone wants her to stop looking."),
    ("The Hollow Pines", 2018, "15", ["Horror", "Mystery"], 6.5, 95, "mkv",
     "The woods remember.",
     "Friends on a camping trip find a village that appears on no map and a guest book with their names already in it."),
    ("Double Espresso", 2023, "14", ["Comedy", "Romance"], 6.7, 98, "mp4",
     "Love, extra shot.",
     "Rival baristas on the same street are forced to share a stall at a charity coffee festival."),
    ("Parallel Lines", 2021, "15", ["Thriller", "Science Fiction"], 7.1, 114, "mkv",
     "Same life. Different ending.",
     "A train driver keeps waking up on the same morning commute, each time on a slightly different line."),
    ("White Summit", 2019, "11", ["Adventure", "Drama"], 7.2, 122, "mkv",
     "The mountain doesn't care who you are.",
     "A disgraced climber returns to the Himalayas to guide the expedition that could clear his name."),
    ("Glasshouse Kings", 2024, "13", ["Crime", "Drama"], 7.5, 128, "mkv",
     "Everyone throws stones.",
     "Two brothers run a counterfeit-art empire out of a failing Essex garden centre."),
    ("Afterglow Avenue", 2022, "13", ["Romance", "Drama"], 6.8, 104, "mp4",
     "Some summers last forever.",
     "Neighbours on a Brighton street fall in and out of love over one long heatwave."),
    ("The Clockmaker's Gambit", 2020, "15", ["Mystery"], 7.3, 115, "mkv",
     "Time is running out. Literally.",
     "A chess-playing clockmaker in 1920s Vienna is the only suspect when his automaton wins a match it should have lost."),
]

# title, year, category, genres, rating, tagline, plot
_SERIES_ROWS = [
    ("Harbour Street", 2019, "21", ["Drama"], 8.1, "Home is where the rent is.",
     "The residents of a Glasgow tenement block navigate love, debt and a mysterious new landlord."),
    ("The Night Desk", 2021, "22", ["Crime", "Drama"], 8.3, "The news never sleeps.",
     "The overnight team at a struggling London newspaper chase the stories nobody else is awake for."),
    ("Orbit Nine", 2022, "23", ["Sci-Fi & Fantasy", "Drama"], 7.9, "Nine strangers. One way home.",
     "Nine strangers wake aboard a space station with no memory of how they got there and one working escape pod."),
    ("Flatmates", 2018, "24", ["Comedy"], 7.5, "Sharing is not caring.",
     "Five twenty-somethings share a damp Manchester flat and absolutely nothing else."),
    ("Blackthorn Manor", 2020, "22", ["Mystery", "Drama"], 7.8, "Every family has a locked room.",
     "A family inherits a country house along with the unsolved disappearance that comes with it."),
    ("Signal & Noise", 2023, "23", ["Sci-Fi & Fantasy", "Mystery"], 8.0, "Someone is listening.",
     "A small-town radio astronomer picks up a transmission that seems to be describing her own life."),
    ("The Allotment", 2017, "24", ["Comedy"], 7.2, "Grow up. Or don't.",
     "Petty feuds, prize marrows and a stolen shed at the most competitive allotment in Yorkshire."),
    ("Northern Line", 2021, "22", ["Crime"], 7.9, "Mind the gap.",
     "Transport police detectives work the cases that happen underground, one stop at a time."),
    ("Kestrel Bay", 2024, "21", ["Drama"], 7.6, "The tide always turns.",
     "A fishing town on the Cornish coast fights to survive when the last cannery announces it will close."),
    ("Ghost Frequencies", 2022, "23", ["Sci-Fi & Fantasy", "Mystery"], 7.7, "Turn it up.",
     "Two paranormal podcasters discover their equipment picks up something far stranger than ghosts."),
]

PEOPLE = [
    "Ava Castellano", "Marcus Hale", "Priya Raman", "Tom Ashdown", "Lena Voss", "Daniel Okafor",
    "Sofia Marchetti", "Callum Reid", "Hannah Kim", "Jonah Pierce", "Maya Delacroix", "Oliver Brandt",
    "Zara Mensah", "Felix Moreau", "Grace Whitlock", "Idris Kaya", "Nora Lindqvist", "Ethan Cole",
    "Chloe Bennett", "Rafael Ortega", "Isla McKenzie", "Samuel Adeyemi", "Elena Petrova", "Lucas Ferreira",
    "Amelia Hart", "Kenji Watanabe", "Beatrice Lowe", "Theo Lambert", "Anika Sharma", "Rory Galloway",
    "Mei Lin", "Victor Stahl", "Freya Holm", "Omar Haddad", "Julia Novak", "Harvey Quinn",
    "Leila Farouk", "Declan Burke", "Imogen Frost", "Caleb Wright",
]
PERSON_BASE = 7001  # TMDB person id of PEOPLE[0]
BIRTHPLACES = [
    "Manchester, England, UK", "Toronto, Ontario, Canada", "Lagos, Nigeria", "Melbourne, Victoria, Australia",
    "Dublin, Ireland", "Mumbai, Maharashtra, India", "Stockholm, Sweden", "Lyon, France", "São Paulo, Brazil",
    "Seoul, South Korea", "Glasgow, Scotland, UK", "Cape Town, South Africa",
]
CHAR_FIRST = ["Ruth", "Kit", "Morgan", "Elliot", "Nadia", "Sol", "Iris", "Cass", "Jude", "Farah", "Leon", "Tess",
              "Ari", "Bex", "Conor", "Dani", "Esme", "Finn", "Gwen", "Hal", "Ines", "Jem", "Kai", "Lottie",
              "Milo", "Noor", "Otto", "Pip", "Quinn", "Rhys"]
CHAR_LAST = ["Hale", "Marlow", "Kerr", "Stroud", "Vance", "Oduya", "Pike", "Rook", "Sato", "Tully", "Wren", "Yates",
             "Abbott", "Blake", "Crane", "Doyle", "Ellis", "Frost", "Grey", "Hart"]
EPISODE_TITLES = [
    "The Long Night", "Low Tide", "Loose Ends", "Crossing Over", "Paper Trail", "Blind Spot", "Aftershock",
    "Homecoming", "Dead Air", "The Offer", "Fault Lines", "Open Water", "Undertow", "The Visitor", "Last Orders",
    "Signal Fire", "Sleeper", "Ghost Notes", "Burn Rate", "Fresh Start", "Cold Open", "Snakes and Ladders",
    "Brass Tacks", "Double Bind", "Early Doors", "Northern Lights", "High Water", "The Reckoning", "Small Hours",
    "Checkmate", "Static", "Parallax", "Tipping Point", "Flashpoint", "The Quiet Part", "Echoes",
    "Second Chances", "Full Circle",
]

# Words for generated (big account and unknown-id) titles.
ADJ = ["Silent", "Crimson", "Hidden", "Broken", "Golden", "Frozen", "Electric", "Midnight", "Wild", "Lost",
       "Burning", "Hollow", "Savage", "Velvet", "Iron", "Distant", "Shattered", "Secret", "Scarlet", "Endless",
       "Silver", "Restless", "Fallen", "Brave", "Bitter", "Hungry", "Lonely", "Stolen", "Wicked", "Final",
       "Northern", "Southern", "Twisted", "Glass", "Paper", "Neon", "Copper", "Quiet", "Violent", "Sacred"]
NOUN = ["Harbor", "Empire", "Witness", "Frontier", "Signal", "Garden", "Kingdom", "Protocol", "Horizon", "Circuit",
        "Legacy", "Hunter", "Island", "Mirror", "Promise", "Storm", "Station", "Voyage", "Shadow", "Canyon",
        "Code", "Tide", "Crown", "Machine", "River", "Summer", "Winter", "Letter", "Ghost", "City",
        "Bridge", "Orchard", "Engine", "Archive", "Lagoon", "Desert", "Heart", "Journey", "Echo", "Pact"]
SUFFIX = ["", "", "", " II", " Returns", ": Reckoning", ": Origins", " Rising", " 2", ""]

TMDB_GENRE_IDS = {
    "Action": 28, "Adventure": 12, "Animation": 16, "Comedy": 35, "Crime": 80, "Documentary": 99, "Drama": 18,
    "Family": 10751, "Fantasy": 14, "History": 36, "Horror": 27, "Music": 10402, "Mystery": 9648,
    "Romance": 10749, "Science Fiction": 878, "Thriller": 53, "War": 10752, "Sci-Fi & Fantasy": 10765,
    "Action & Adventure": 10759, "Reality": 10764,
}


def _cast(*key, count=12):
    """Deterministic cast (indexes into PEOPLE) plus a director who is not in the cast."""
    rng = seeded("cast", *key)
    picks = rng.sample(range(len(PEOPLE)), count + 1)
    roles = []
    for _ in range(count):
        roles.append("%s %s" % (rng.choice(CHAR_FIRST), rng.choice(CHAR_LAST)))
    return picks[:count], roles, picks[count]


def _build_films():
    films = []
    for i, (title, year, cat, genres, rating, minutes, ext, tagline, plot) in enumerate(_FILM_ROWS):
        cast, roles, director = _cast("film", i)
        films.append({
            "kind": "movie", "index": i, "num": i + 1, "stream_id": 2001 + i,
            "title": title, "year": year, "name": "%s (%d)" % (title, year),
            "category_id": cat, "genres": genres, "rating": rating, "minutes": minutes, "ext": ext,
            "tagline": tagline, "plot": plot,
            "tmdb_id": 910001 + i, "imdb_id": "tt%07d" % (9100001 + i),
            "poster": "film-%d" % (i + 1), "backdrop": "backdrop-%d" % (i + 1),
            "release_date": "%d-%02d-%02d" % (year, (i * 5) % 12 + 1, (i * 11) % 27 + 1),
            "cast": cast, "roles": roles, "director": director,
            "age": ((i * 37) % 400 + 1) * 86400 + (i * 7919) % 86400,  # seconds since "added"
        })
    return films


def _build_series():
    shows = []
    for j, (title, year, cat, genres, rating, tagline, plot) in enumerate(_SERIES_ROWS):
        cast, roles, director = _cast("series", j)
        rng = seeded("episodes", j)
        titles = rng.sample(EPISODE_TITLES, len(EPISODE_TITLES))
        seasons = []
        for s in range(1, 2 + (j % 2) + 1):  # 2 or 3 seasons
            episodes = []
            for e in range(1, 4 + (j * 3 + s) % 5 + 1):  # 4 to 8 episodes
                ep_id = 5000 + j * 100 + (s - 1) * 10 + e
                ep_title = "Pilot" if (s, e) == (1, 1) else titles[((s - 1) * 8 + e) % len(titles)]
                episodes.append({
                    "id": ep_id, "season": s, "episode_num": e, "title": ep_title,
                    "minutes": 38 + (ep_id * 7) % 21,
                    "plot": "%s: %s" % (ep_title, rng.choice([
                        "Old loyalties are tested when an unexpected visitor turns up with a proposal.",
                        "A mistake from the past resurfaces at the worst possible moment.",
                        "Everyone scrambles when a secret comes out in front of the whole street.",
                        "A late-night phone call changes the plan for good.",
                        "An uneasy truce falls apart before the credits roll.",
                        "The team split up to chase two very different leads.",
                    ])),
                    "image": "backdrop-e%d" % ep_id,
                    "air_date": "%d-%02d-%02d" % (year + s - 1, (e % 12) + 1, (e * 3) % 27 + 1),
                })
            seasons.append({"season": s, "episodes": episodes})
        shows.append({
            "kind": "tv", "index": j, "num": j + 1, "series_id": 3001 + j,
            "title": title, "year": year, "name": "%s (%d)" % (title, year),
            "category_id": cat, "genres": genres, "rating": rating, "tagline": tagline, "plot": plot,
            "tmdb_id": 920001 + j, "imdb_id": "tt%07d" % (9200001 + j),
            "poster": "series-%d" % (j + 1), "backdrop": "backdrop-s%d" % (j + 1),
            "first_air_date": "%d-%02d-%02d" % (year, (j * 4) % 12 + 1, (j * 7) % 27 + 1),
            "cast": cast, "roles": roles, "director": director, "seasons": seasons,
            "age": ((j * 23) % 200 + 1) * 86400 + (j * 4057) % 86400,
        })
    return shows


def _build_channels():
    channels = []
    for i, (name, epg_id, cat, theme) in enumerate(_CHANNEL_ROWS):
        logo = "anim.gif" if epg_id == ANIMATED_LOGO_CHANNEL else slugify(epg_id) + ".png"
        channels.append({
            "num": i + 1, "stream_id": 1001 + i, "name": name, "plain_name": name.split(": ", 1)[-1],
            "epg_id": epg_id, "category_id": cat, "theme": theme, "logo": "/images/" + logo,
            "added": 1700000000 + i * 3600, "archive": 1,
        })
    return channels


FILMS = _build_films()
SERIES = _build_series()
CHANNELS = _build_channels()
PLAIN_CHANNELS = CHANNELS[:25]  # the channels in /playlist.m3u
PLAIN_FILMS = FILMS[:5]  # the films in /playlist.m3u

FILM_BY_STREAM = {f["stream_id"]: f for f in FILMS}
FILM_BY_TMDB = {f["tmdb_id"]: f for f in FILMS}
SERIES_BY_ID = {s["series_id"]: s for s in SERIES}
SERIES_BY_TMDB = {s["tmdb_id"]: s for s in SERIES}
CHANNEL_BY_STREAM = {c["stream_id"]: c for c in CHANNELS}
CATEGORY_NAMES = dict(LIVE_CATEGORIES + VOD_CATEGORIES + SERIES_CATEGORIES)


def _person_credits():
    """For each person in PEOPLE, the demo titles they act in or direct."""
    credits = {p: [] for p in range(len(PEOPLE))}
    for item in FILMS + SERIES:
        for person, role in zip(item["cast"], item["roles"]):
            credits[person].append((item, role, None))
        credits[item["director"]].append((item, None, "Director"))
    return credits


PERSON_CREDITS = _person_credits()


# ---------------------------------------------------------------------------------------------
# Provider-sized account (generated on demand from the index, never stored)
# ---------------------------------------------------------------------------------------------

BIG_COUNTRIES = ["UK", "US", "CA", "IE", "DE", "FR", "ES", "IT", "NL", "PT", "TR", "IN"]
BIG_LIVE_GENRES = [("Entertainment", "entertainment"), ("News", "news"), ("Sport", "sport"),
                   ("Movies", "movies"), ("Kids", "kids")]
BIG_LANGS = ["EN", "DE", "FR", "ES", "IT", "NL", "TR", "HI"]
BIG_VOD_GENRES = ["Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Thriller", "Animation", "Documentary",
                  "Romance", "Family"]
BIG_SERIES_GENRES = ["Drama", "Comedy", "Crime", "Sci-Fi", "Reality"]
BIG_BRANDS = ["Crown", "Vista", "Arena", "Pulse", "Metro", "Nova", "Atlas", "Orbit", "Summit", "Harbour", "Echo",
              "Prime"]
BIG_QUALITY = ["HD", "FHD", "SD", "4K", "HEVC"]

BIG_LIVE_CATEGORIES = [(str(i + 1), "%s | %s" % (BIG_COUNTRIES[i // 5], BIG_LIVE_GENRES[i % 5][0]))
                       for i in range(60)]
BIG_VOD_CATEGORIES = [(str(101 + i), "%s | %s" % (BIG_LANGS[i // 10], BIG_VOD_GENRES[i % 10])) for i in range(80)]
BIG_SERIES_CATEGORIES = [(str(201 + i), "%s | %s" % (BIG_LANGS[i // 5], BIG_SERIES_GENRES[i % 5]))
                         for i in range(40)]


def big_channel(i):
    cat = i % 60
    country, (genre, theme) = BIG_COUNTRIES[cat // 5], BIG_LIVE_GENRES[cat % 5]
    brand, n = BIG_BRANDS[(i // 60) % len(BIG_BRANDS)], i // 60 + 1
    return {
        "num": i + 1, "stream_id": BIG_LIVE_BASE + i,
        "name": "%s: %s %s %d %s" % (country, brand, genre, n, BIG_QUALITY[(i // 7) % 5]),
        "epg_id": "%s%s%d.%s" % (brand.lower(), genre.lower(), n, country.lower()),
        "category_id": str(cat + 1), "theme": theme, "logo": "/images/logo-%d.png" % (i % 499),
        "added": 1600000000 + i * 60, "archive": 1 if i % 3 == 0 else 0,
    }


def _generated_title(i, with_suffix=True):
    title = "%s %s%s" % (ADJ[(i * 7) % 40], NOUN[(i * 13 + i // 40) % 40],
                         SUFFIX[(i // 1600) % len(SUFFIX)] if with_suffix else "")
    return ("The " + title) if i % 3 == 0 else title


def big_film(i):
    cat = i % 80
    lang, genre = BIG_LANGS[cat // 10], BIG_VOD_GENRES[cat % 10]
    year = 1960 + (i * 31) % 66
    title = _generated_title(i)
    return {
        "kind": "movie", "num": i + 1, "stream_id": BIG_VOD_BASE + i, "title": title, "year": year,
        "name": "%s - %s (%d)" % (lang, title, year), "category_id": str(101 + cat), "genres": [genre],
        "rating": round(4.0 + ((i * 37) % 55) / 10.0, 1), "minutes": 80 + (i * 17) % 70,
        "ext": "mkv" if i % 4 else "mp4", "tagline": "",
        "plot": "A %s story about a %s, a %s and one very long night." % (
            genre.lower(), NOUN[(i * 3) % 40].lower(), NOUN[(i * 11) % 40].lower()),
        "tmdb_id": "", "imdb_id": "", "poster": "film-b%d" % (i % 997), "backdrop": "backdrop-b%d" % (i % 199),
        "release_date": "%d-%02d-%02d" % (year, i % 12 + 1, i % 27 + 1),
        "cast": _cast("bigfilm", i % 5000, count=6)[0], "director": (i * 7) % len(PEOPLE),
        "age": (i * 7919) % (86400 * 900),
    }


def big_series(i):
    cat = i % 40
    lang, genre = BIG_LANGS[cat // 5], BIG_SERIES_GENRES[cat % 5]
    year = 1985 + (i * 29) % 41
    title = _generated_title(i + 7, with_suffix=False)
    seasons = []
    for s in range(1, 1 + (i % 3) + 1):
        episodes = []
        for e in range(1, 6 + (i + s) % 5):
            ep_id = BIG_EPISODE_BASE + i * 100 + (s - 1) * 20 + e
            episodes.append({
                "id": ep_id, "season": s, "episode_num": e,
                "title": "%s - S%02dE%02d - Episode %d" % (title, s, e, e),  # provider-style title
                "minutes": 40 + (ep_id % 20), "plot": "Episode %d of season %d." % (e, s),
                "image": "backdrop-b%d" % (ep_id % 199), "air_date": "%d-%02d-%02d" % (year + s - 1, e, e + 1),
            })
        seasons.append({"season": s, "episodes": episodes})
    return {
        "kind": "tv", "num": i + 1, "series_id": BIG_SERIES_BASE + i, "title": title, "year": year,
        "name": "%s - %s (%d)" % (lang, title, year), "category_id": str(201 + cat), "genres": [genre],
        "rating": round(5.0 + ((i * 13) % 45) / 10.0, 1), "tagline": "",
        "plot": "A %s series set around a %s." % (genre.lower(), NOUN[(i * 5) % 40].lower()),
        "tmdb_id": "", "imdb_id": "", "poster": "series-b%d" % (i % 499), "backdrop": "backdrop-b%d" % (i % 199),
        "first_air_date": "%d-01-15" % year, "cast": _cast("bigseries", i % 2000, count=6)[0],
        "director": (i * 3) % len(PEOPLE), "seasons": seasons, "age": (i * 4057) % (86400 * 900),
    }


def channel_for(stream_id):
    if stream_id in CHANNEL_BY_STREAM:
        return CHANNEL_BY_STREAM[stream_id]
    if BIG_LIVE_BASE <= stream_id < BIG_LIVE_BASE + BIG_LIVE:
        return big_channel(stream_id - BIG_LIVE_BASE)
    return None


def film_for(stream_id):
    if stream_id in FILM_BY_STREAM:
        return FILM_BY_STREAM[stream_id]
    if BIG_VOD_BASE <= stream_id < BIG_VOD_BASE + BIG_VOD:
        return big_film(stream_id - BIG_VOD_BASE)
    return None


def series_for(series_id):
    if series_id in SERIES_BY_ID:
        return SERIES_BY_ID[series_id]
    if BIG_SERIES_BASE <= series_id < BIG_SERIES_BASE + BIG_SERIES:
        return big_series(series_id - BIG_SERIES_BASE)
    return None


# ---------------------------------------------------------------------------------------------
# TV guide: a deterministic schedule per channel, the same on every endpoint
# ---------------------------------------------------------------------------------------------

GUIDE = {
    # An optional third item limits a title to local (London) hours [from, to).
    "news": [
        ("Breakfast Headlines", "The morning's top stories, with the papers, travel and the weather.", (5, 10)),
        ("Morning Briefing", "Live interviews and analysis of the stories shaping the day.", (6, 12)),
        ("The World Today", "Reports from our correspondents around the globe."),
        ("Lunchtime News", "The latest national and international headlines.", (12, 14)),
        ("Business Live", "Markets, money and the companies making the news.", (7, 19)),
        ("Politics Now", "Westminster's week, with the people who make the decisions.", (9, 22)),
        ("The Evening Bulletin", "A full round-up of the day's news, sport and weather.", (17, 23)),
        ("Newsnight Review", "In-depth discussion of the day's biggest story.", (21, 24)),
        ("Weather Watch", "The detailed forecast for the week ahead."),
        ("World Report", "Stories from every continent, told by the people living them."),
        ("Sports Desk", "Results, reaction and the stories from the dressing room.", (12, 24)),
        ("Overnight News", "Rolling headlines through the night.", (0, 5)),
    ],
    "entertainment": [
        ("The Big Quiz Night", "Four teams, one studio and a jackpot that rolls over every week."),
        ("Dream Home Makeover", "A family's tired terrace gets a top-to-bottom transformation."),
        ("Celebrity Kitchen Showdown", "Famous faces cook for their lives in front of top chefs."),
        ("The Talent Hour", "Singers, dancers and a very determined magician chase the final."),
        ("Bargain Hunters Roadshow", "Two teams, three hours and a car-boot sale full of surprises."),
        ("Late Night Live", "Chat, comedy and music with tonight's special guests."),
        ("Coastal Rescue", "Lifeboat crews answer calls along the Welsh coast."),
        ("The Pet Vets", "A busy animal hospital treats everything from hamsters to horses."),
        ("Wedding Rush", "Couples plan an entire wedding in just seven days."),
        ("Spin the Wheel", "One spin could change everything in the nation's favourite game show."),
    ],
    "drama": [(s["title"], s["plot"]) for s in SERIES if "Comedy" not in s["genres"]] + [
        ("Ward 12", "Doctors on a busy night shift face an impossible choice."),
        ("The Inheritance", "A will reading turns a quiet family into rivals."),
    ],
    "comedy": [(s["title"], s["plot"]) for s in SERIES if "Comedy" in s["genres"]] + [
        ("Stand-Up Central", "The best new comedians from clubs across the country."),
        ("Sketch Pad", "Quick-fire sketches from a cast of rising stars."),
        ("Office Hours", "The worst-run branch office in Swindon does it again."),
        ("Panel Games", "Four comedians, one host and questions nobody can answer."),
        ("Laugh Track Classics", "Favourite sitcom moments from the last forty years."),
    ],
    "lifestyle": [
        ("Weekend Kitchen", "Easy recipes for long, lazy weekends."),
        ("Little House, Big Ideas", "Clever design for small spaces."),
        ("The Budget Traveller", "Seeing the world on fifty pounds a day."),
        ("Style Swap", "Two friends restyle each other's wardrobes."),
        ("Green Fingers", "Seasonal jobs for every size of garden."),
        ("Flea Market Finds", "Turning junk into treasure, one stall at a time."),
        ("Coast to Coast Eats", "The best street food between Cornwall and the Highlands."),
        ("Renovation Rescue", "Builders step in to save a project that has gone wrong."),
    ],
    "docs": [
        ("Frozen Frontiers", "Life at the edge of the Arctic ice."),
        ("Deep Sea Detectives", "Divers investigate shipwrecks that rewrite history."),
        ("Ancient Engineers", "How the ancient world built wonders without machines."),
        ("Brain Games Lab", "Experiments that show how easily our minds are fooled."),
        ("Railways of the World", "Epic train journeys across five continents."),
        ("Volcano Watch", "Scientists live beside one of Europe's most active volcanoes."),
        ("Secrets of the Pyramids", "New scans reveal hidden chambers."),
        ("Wild Planet: Oceans", "The creatures of the open ocean, filmed as never before."),
        ("How It's Built", "Inside the factories that make everyday things."),
        ("The Lost Cities", "Archaeologists uncover cities swallowed by the jungle."),
    ],
    "sport": [
        ("Live Boxing: Title Fight", "Twelve rounds for the heavyweight belt."),
        ("Racing Live from Newmarket", "Every race from the July meeting."),
        ("Test Cricket: Day Three", "Live coverage of the third day's play."),
        ("Golf Tour Highlights", "The best shots from the final round."),
        ("Motor Racing: Qualifying", "The fight for pole position."),
        ("Rugby Union Live", "Top-of-the-table clash in the Premiership."),
        ("Tennis Tonight", "Highlights and interviews from the evening session."),
        ("Snooker Masters", "Live from the Crucible-style arena."),
        ("Darts: World Series", "The world's best chase a nine-darter."),
        ("Sports Round-Up", "Every result and every goal from the weekend."),
    ],
    "football": [
        ("Live: Northbridge United v Eastfield City", "Top-of-the-table clash, live from the Riverside."),
        ("Match of the Week", "Extended highlights of the weekend's standout game."),
        ("Goals Galore", "Every goal from every league."),
        ("Pre-Match Build-Up", "Team news and analysis before kick-off."),
        ("Transfer Talk", "The latest deals, rumours and signings."),
        ("Classic Matches", "Relive a legendary cup final in full."),
        ("The Football Show", "Pundits pick apart the weekend's big talking points."),
        ("Monday Night Kick-Off", "Live coverage of the Monday night game."),
    ],
    "movies": [(f["title"], f["tagline"] + " " + f["plot"]) for f in FILMS],
    "kids": [
        ("Dino Detectives", "Three young dinosaurs solve mysteries in the valley."),
        ("Space Pups", "A crew of puppies explore the solar system."),
        ("Maths Monsters", "Friendly monsters make numbers fun."),
        ("Craft Club", "Make something brilliant from the recycling box."),
        ("Story Time", "A favourite picture book read aloud."),
        ("Robot Friends", "A robot learns what it means to be a good friend."),
        ("Jungle Juniors", "Adventures deep in the rainforest."),
        ("Science Squad", "Safe, messy experiments to try at home."),
        ("Tiny Toons Morning", "A bundle of cartoons to start the day.", (6, 11)),
    ],
    "music": [
        ("Top 40 Countdown", "This week's biggest songs, counted down."),
        ("90s Anthems", "Non-stop hits from the nineties."),
        ("Chill Out Sessions", "Downtempo tracks for a slow evening."),
        ("Live at the Roundhouse", "A full concert from one of London's best venues."),
        ("Indie Hour", "New releases from independent labels."),
        ("Floor Fillers", "Dance classics to get you moving."),
        ("Acoustic Mornings", "Stripped-back sessions from studio three.", (6, 12)),
        ("Throwback Thursday", "Videos you forgot you loved."),
        ("Artist Spotlight", "One artist, their story and their best videos."),
        ("Non-Stop Hits", "Back-to-back chart music."),
    ],
}
GUIDE_DURATIONS = {  # programme lengths in minutes
    "news": [30, 30, 60], "entertainment": [30, 60, 60, 90], "drama": [60, 60, 90], "comedy": [30, 30, 60],
    "lifestyle": [30, 60], "docs": [60, 60, 30], "sport": [60, 90, 90], "football": [60, 90, 90],
    "movies": [90], "kids": [30], "music": [60, 30],
}
GUIDE_CATEGORY = {
    "news": "News", "entertainment": "Entertainment", "drama": "Drama", "comedy": "Comedy",
    "lifestyle": "Lifestyle", "docs": "Documentary", "sport": "Sports", "football": "Sports",
    "movies": "Movie", "kids": "Children", "music": "Music",
}
BLOCK = 3 * 3600  # schedules are built in 3-hour blocks so a block is identical on every request


def guide_window(t=None):
    """12 hours ago to 24 hours ahead, on the hour."""
    t = now() if t is None else t
    hour = t - t % 3600
    return hour - 12 * 3600, hour + 24 * 3600


def schedule(channel, window=None):
    """Programmes for one channel overlapping the window, as dicts with start/stop/title/desc/id."""
    start, end = window or guide_window()
    theme = channel["theme"]
    pool, lengths = GUIDE[theme], GUIDE_DURATIONS[theme]
    programmes = []
    block = start - start % BLOCK
    last_title = None
    while block < end:
        rng = seeded("guide", channel["epg_id"], block)
        t, remaining = block, 180
        while remaining > 0:
            options = [d for d in lengths if d <= remaining]
            minutes = rng.choice(options) if options else remaining
            hour = datetime.fromtimestamp(t, LONDON).hour
            fits = [e for e in pool if len(e) == 2 or e[2][0] <= hour < e[2][1]]
            fresh = [e for e in fits if e[0] != last_title] or fits
            title, desc = rng.choice(fresh)[:2]
            last_title = title
            stop = t + minutes * 60
            if stop > start and t < end:
                programmes.append({
                    "start": t, "stop": stop, "title": title, "desc": desc,
                    "id": zlib.crc32(("%s|%d" % (channel["epg_id"], t)).encode()) & 0x7FFFFFFF,
                    "category": GUIDE_CATEGORY[theme],
                })
            t, remaining = stop, remaining - minutes
        block += BLOCK
    return programmes


def xtream_listing(programme, channel, t):
    past = programme["stop"] <= t
    return {
        "id": str(programme["id"]), "epg_id": "1", "title": b64(programme["title"]), "lang": "en",
        "start": local_str(programme["start"]), "end": local_str(programme["stop"]),
        "description": b64(programme["desc"]), "channel_id": channel["epg_id"],
        "start_timestamp": str(programme["start"]), "stop_timestamp": str(programme["stop"]),
        "now_playing": 1 if programme["start"] <= t < programme["stop"] else 0,
        "has_archive": 1 if past and channel["archive"] else 0,
    }


def xmltv_document(host, channels):
    """XMLTV for the given channels: <channel> entries then every programme."""
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<!DOCTYPE tv SYSTEM "xmltv.dtd">',
           '<tv generator-info-name="CHUD STREAMS mock server" source-info-name="mock">']
    window = guide_window()
    for ch in channels:
        out.append('  <channel id="%s">' % attr(ch["epg_id"]))
        out.append('    <display-name lang="en">%s</display-name>' % xml_escape(ch["name"]))
        if ch.get("plain_name") and ch["plain_name"] != ch["name"]:
            out.append('    <display-name lang="en">%s</display-name>' % xml_escape(ch["plain_name"]))
        out.append('    <icon src="%s"/>' % attr(host + ch["logo"]))
        out.append("  </channel>")
    for ch in channels:
        for p in schedule(ch, window):
            out.append('  <programme start="%s" stop="%s" channel="%s">' % (
                xmltv_time(p["start"]), xmltv_time(p["stop"]), attr(ch["epg_id"])))
            out.append('    <title lang="en">%s</title>' % xml_escape(p["title"]))
            out.append('    <desc lang="en">%s</desc>' % xml_escape(p["desc"]))
            out.append('    <category lang="en">%s</category>' % xml_escape(p["category"]))
            out.append("  </programme>")
    out.append("</tv>")
    return ("\n".join(out) + "\n").encode("utf-8")


_EPG_CACHE = {}
_EPG_LOCK = threading.Lock()


def cached_epg(host):
    """(xml, gzipped xml) for the demo channels; rebuilt each hour."""
    key = (host, guide_window()[0])
    with _EPG_LOCK:
        hit = _EPG_CACHE.get(key)
    if hit:
        return hit
    xml = xmltv_document(host, CHANNELS)
    value = (xml, gzip.compress(xml, 6, mtime=0))
    with _EPG_LOCK:
        _EPG_CACHE.clear()
        _EPG_CACHE[key] = value
    return value


# ---------------------------------------------------------------------------------------------
# Xtream JSON shapes
# ---------------------------------------------------------------------------------------------


def category_list(rows):
    return [{"category_id": cid, "category_name": name, "parent_id": 0} for cid, name in rows]


def live_entry(ch, host):
    return {
        "num": ch["num"], "name": ch["name"], "stream_type": "live", "stream_id": ch["stream_id"],
        "stream_icon": host + ch["logo"], "epg_channel_id": ch["epg_id"], "added": str(ch["added"]),
        "is_adult": 0, "category_id": ch["category_id"], "category_ids": [int(ch["category_id"])],
        "custom_sid": "", "tv_archive": ch["archive"], "direct_source": "",
        "tv_archive_duration": 7 if ch["archive"] else 0,
    }


def vod_entry(f, host, t):
    return {
        "num": f["num"], "name": f["name"], "stream_type": "movie", "stream_id": f["stream_id"],
        "stream_icon": "%s/images/%s.png" % (host, f["poster"]), "rating": "%.1f" % f["rating"],
        "rating_5based": round(f["rating"] / 2, 1), "added": str(day_floor(t) - f["age"]), "is_adult": 0,
        "category_id": f["category_id"], "category_ids": [int(f["category_id"])],
        "container_extension": f["ext"], "custom_sid": "", "direct_source": "",
    }


def series_entry(s, host, t):
    return {
        "num": s["num"], "name": s["name"], "series_id": s["series_id"],
        "cover": "%s/images/%s.png" % (host, s["poster"]), "plot": s["plot"],
        "cast": ", ".join(PEOPLE[p] for p in s["cast"][:5]), "director": PEOPLE[s["director"]],
        "genre": ", ".join(s["genres"]), "releaseDate": s["first_air_date"],
        "last_modified": str(day_floor(t) - s["age"]), "rating": "%.1f" % s["rating"],
        "rating_5based": round(s["rating"] / 2, 1),
        "backdrop_path": ["%s/images/%s.png" % (host, s["backdrop"])], "youtube_trailer": "",
        "episode_run_time": "45", "category_id": s["category_id"], "category_ids": [int(s["category_id"])],
    }


def vod_info(f, host, t):
    poster = "%s/images/%s.png" % (host, f["poster"])
    seconds = f["minutes"] * 60
    return {
        "info": {
            "name": f["title"], "o_name": f["title"], "plot": f["plot"], "description": f["plot"],
            "releasedate": f["release_date"], "rating": "%.1f" % f["rating"],
            "duration_secs": seconds, "duration": "%02d:%02d:00" % (seconds // 3600, seconds // 60 % 60),
            "genre": ", ".join(f["genres"]), "cast": ", ".join(PEOPLE[p] for p in f["cast"][:6]),
            "actors": ", ".join(PEOPLE[p] for p in f["cast"][:6]), "director": PEOPLE[f["director"]],
            "movie_image": poster, "cover_big": poster,
            "backdrop_path": ["%s/images/%s.png" % (host, f["backdrop"])],
            "tmdb_id": str(f["tmdb_id"]), "youtube_trailer": "", "country": "United Kingdom", "age": "",
        },
        "movie_data": {
            "stream_id": f["stream_id"], "name": f["name"], "added": str(day_floor(t) - f["age"]),
            "category_id": f["category_id"], "container_extension": f["ext"], "custom_sid": "", "direct_source": "",
        },
    }


def series_info(s, host, t):
    cover = "%s/images/%s.png" % (host, s["poster"])
    episodes = {}
    seasons = []
    for season in s["seasons"]:
        n = season["season"]
        seasons.append({
            "season_number": n, "name": "Season %d" % n, "episode_count": len(season["episodes"]),
            "air_date": season["episodes"][0]["air_date"], "overview": "", "cover": cover, "cover_big": cover,
        })
        episodes[str(n)] = [{
            "id": str(e["id"]), "episode_num": e["episode_num"], "title": e["title"],
            "container_extension": "mkv", "season": n, "added": str(day_floor(t) - s["age"]),
            "custom_sid": "", "direct_source": "",
            "info": {
                "plot": e["plot"], "duration_secs": e["minutes"] * 60, "duration": "00:%02d:00" % e["minutes"],
                "movie_image": "%s/images/%s.png" % (host, e["image"]), "releasedate": e["air_date"],
                "rating": "%.1f" % s["rating"], "name": e["title"],
            },
        } for e in season["episodes"]]
    return {
        "seasons": seasons,
        "info": {
            "name": s["title"], "plot": s["plot"], "releaseDate": s["first_air_date"],
            "rating": "%.1f" % s["rating"], "genre": ", ".join(s["genres"]),
            "cast": ", ".join(PEOPLE[p] for p in s["cast"][:6]), "director": PEOPLE[s["director"]],
            "cover": cover, "backdrop_path": ["%s/images/%s.png" % (host, s["backdrop"])],
            "tmdb_id": str(s["tmdb_id"]), "last_modified": str(day_floor(t) - s["age"]),
            "episode_run_time": "45", "youtube_trailer": "", "category_id": s["category_id"],
        },
        "episodes": episodes,
    }


def big_live_entries(host, category):
    idx = range(BIG_LIVE) if category is None else range(category, BIG_LIVE, 60)
    for i in idx:
        yield live_entry(big_channel(i), host)


def big_vod_entries(host, category):
    t = now()
    idx = range(BIG_VOD) if category is None else range(category, BIG_VOD, 80)
    for i in idx:
        entry = vod_entry(big_film(i), host, t)
        if i % 10_000 == 4_321:  # about 1 in 10,000 entries is broken, the way real panels are
            entry["stream_id"] = ["", None, "n/a"][(i // 10_000) % 3]
        yield entry


def big_series_entries(host, category):
    t = now()
    idx = range(BIG_SERIES) if category is None else range(category, BIG_SERIES, 40)
    for i in idx:
        yield series_entry(big_series(i), host, t)


def json_array_chunks(items, size=64 * 1024):
    """Streams a JSON array item by item in ~64 KB pieces, never holding the whole list."""
    buf, used, first = [b"["], 1, True
    for item in items:
        piece = php_json(item).encode("utf-8")
        if not first:
            piece = b"," + piece
        first = False
        buf.append(piece)
        used += len(piece)
        if used >= size:
            yield b"".join(buf)
            buf, used = [], 0
    buf.append(b"]")
    yield b"".join(buf)


def account_m3u_lines(host, user, password, ext):
    """M3U lines for an Xtream account (the demo catalogue, or the big one streamed)."""
    u, p = quote(user, safe=""), quote(password, safe="")
    yield '#EXTM3U url-tvg="%s/epg.xml"\n' % host
    if user == "big":
        channels = (big_channel(i) for i in range(BIG_LIVE))
        films = (big_film(i) for i in range(BIG_VOD))
        names = dict(BIG_LIVE_CATEGORIES + BIG_VOD_CATEGORIES)
    else:
        channels, films, names = iter(CHANNELS), iter(FILMS[:8]), CATEGORY_NAMES
    for ch in channels:
        yield '#EXTINF:-1 tvg-id="%s" tvg-name="%s" tvg-logo="%s" group-title="%s",%s\n%s/live/%s/%s/%d.%s\n' % (
            ch["epg_id"], ch["name"], host + ch["logo"], names[ch["category_id"]], ch["name"],
            host, u, p, ch["stream_id"], ext)
    for f in films:
        yield '#EXTINF:-1 tvg-id="" tvg-name="%s" tvg-logo="%s/images/%s.png" group-title="%s",%s\n%s/movie/%s/%s/%d.%s\n' % (
            f["name"], host, f["poster"], names[f["category_id"]], f["name"], host, u, p, f["stream_id"], f["ext"])


def plain_m3u(host):
    lines = ['#EXTM3U url-tvg="%s/epg.xml.gz"' % host]
    for n, ch in enumerate(PLAIN_CHANNELS, 1):
        group = CATEGORY_NAMES[ch["category_id"]].split(" | ", 1)[-1]
        lines.append('#EXTINF:-1 tvg-id="%s" tvg-name="%s" tvg-logo="%s" group-title="%s",%s' % (
            ch["epg_id"], ch["plain_name"], host + ch["logo"], group, ch["plain_name"]))
        lines.append("%s/stream/%d.ts" % (host, n))
    for n, f in enumerate(PLAIN_FILMS, 1):
        lines.append('#EXTINF:-1 tvg-id="" tvg-name="%s" tvg-logo="%s/images/%s.png" group-title="Movies",%s' % (
            f["name"], host, f["poster"], f["name"]))
        lines.append("%s/vod/%d.mkv" % (host, n))
    return ("\n".join(lines) + "\n").encode("utf-8")


# ---------------------------------------------------------------------------------------------
# Generated images: PNG (zlib + struct) and a hand-built animated GIF
# ---------------------------------------------------------------------------------------------

_IMAGE_CACHE = {}
_IMAGE_LOCK = threading.Lock()
# Lookup tables that darken a whole row at C speed (bytes.translate): factor 1.0 down to 0.5.
_SHADE = [bytes(int(v * (1.0 - k / 126.0)) for v in range(256)) for k in range(64)]


def image_size(name):
    if name.startswith(("poster-", "film-", "series-")):
        return 400, 600
    if name.startswith("backdrop-"):
        return 1280, 720
    return 256, 256


def _png(width, height, raw):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
            + chunk(b"IEND", b""))


def make_png(name):
    """A colourful diagonal gradient with bright discs, rings and stripes, the same for the same name."""
    w, h = image_size(name)
    rng = seeded("image", name)
    hue = rng.random()
    c1 = colorsys.hsv_to_rgb(hue, 0.65 + rng.random() * 0.3, 0.95)
    c2 = colorsys.hsv_to_rgb((hue + 0.3 + rng.random() * 0.35) % 1.0, 0.85, 0.3 + rng.random() * 0.25)

    # The gradient depends only on x + y, so every row is a slice of one long line of pixels.
    n = w + h
    line = bytearray(3 * n)
    for k in range(n):
        t = k / (n - 1)
        line[3 * k:3 * k + 3] = bytes(int(255 * (a + (b - a) * t)) for a, b in zip(c1, c2))

    def bright():
        r, g, b = colorsys.hsv_to_rgb(rng.random(), 0.55 + rng.random() * 0.4, 1.0)
        return bytes((int(r * 255), int(g * 255), int(b * 255)))

    small = min(w, h)
    shapes = []
    for _ in range(rng.randint(3, 6)):
        shapes.append(("disc", rng.randint(0, w), rng.randint(0, h), rng.randint(small // 14, small // 4), bright()))
    for _ in range(rng.randint(1, 2)):
        r = rng.randint(small // 6, small // 3)
        shapes.append(("ring", rng.randint(0, w), rng.randint(0, h), r, max(4, r // 6), bright()))
    for _ in range(rng.randint(1, 3)):
        shapes.append(("band", rng.randint(0, n), rng.randint(6, max(8, small // 12)), bright()))

    def spans(shape, y):
        if shape[0] == "disc":
            _, cx, cy, r, _c = shape
            dy = y - cy
            if abs(dy) < r:
                dx = int(math.sqrt(r * r - dy * dy))
                yield max(0, cx - dx), min(w, cx + dx + 1)
        elif shape[0] == "ring":
            _, cx, cy, r, thick, _c = shape
            dy = y - cy
            if abs(dy) < r:
                outer = int(math.sqrt(r * r - dy * dy))
                inner_r = r - thick
                inner = int(math.sqrt(inner_r * inner_r - dy * dy)) if abs(dy) < inner_r else -1
                if inner < 0:
                    yield max(0, cx - outer), min(w, cx + outer + 1)
                else:
                    yield max(0, cx - outer), min(w, cx - inner)
                    yield max(0, cx + inner + 1), min(w, cx + outer + 1)
        else:  # diagonal band where c <= x + y < c + width
            _, c, width, _c = shape
            yield max(0, c - y), min(w, c + width - y)

    shade_from = int(h * 0.6) if h > w or w > 1000 else h  # darken the bottom of posters and backdrops
    raw = bytearray()
    for y in range(h):
        row = bytearray(line[3 * y:3 * (y + w)])
        for shape in shapes:
            color = shape[-1]
            for x0, x1 in spans(shape, y):
                if x1 > x0:
                    row[3 * x0:3 * x1] = color * (x1 - x0)
        if y >= shade_from:
            row = row.translate(_SHADE[min(63, (y - shade_from) * 64 // max(1, h - shade_from))])
        raw += b"\x00"
        raw += row
    return _png(w, h, raw)


def png_for(name):
    with _IMAGE_LOCK:
        data = _IMAGE_CACHE.get(name)
    if data is None:
        data = make_png(name)
        with _IMAGE_LOCK:
            _IMAGE_CACHE[name] = data
    return data


def lzw_encode(pixels, min_code_size):
    """GIF-flavoured LZW: variable-width codes, packed least significant bit first."""
    clear, end = 1 << min_code_size, (1 << min_code_size) + 1
    out = bytearray()
    state = {"acc": 0, "bits": 0}

    def emit(code, width):
        state["acc"] |= code << state["bits"]
        state["bits"] += width
        while state["bits"] >= 8:
            out.append(state["acc"] & 0xFF)
            state["acc"] >>= 8
            state["bits"] -= 8

    width, next_code, table = min_code_size + 1, end + 1, {}
    emit(clear, width)
    since_clear = 0  # codes written since the last clear
    prefix = pixels[0]
    for px in pixels[1:]:
        key = (prefix << 8) | px
        code = table.get(key)
        if code is not None:
            prefix = code
            continue
        emit(prefix, width)
        since_clear += 1
        if next_code < 4096:
            table[key] = next_code
            next_code += 1
            if next_code > (1 << width) and width < 12:
                width += 1
        else:  # table full: start again
            emit(clear, width)
            table, next_code, width, since_clear = {}, end + 1, min_code_size + 1, 0
        prefix = px
    emit(prefix, width)
    # The decoder adds one more entry when it reads that last code, which may widen the next one.
    if since_clear > 0 and next_code == (1 << width) and width < 12:
        width += 1
    emit(end, width)
    if state["bits"]:
        out.append(state["acc"] & 0xFF)
    return bytes(out)


def make_gif():
    """96x96, 6 frames, looping forever: moving diagonal colour bands with a rainbow bar sweeping across."""
    size, frames = 96, 6
    palette = bytearray()
    for k in range(8):  # 0-7: background band colours
        r, g, b = colorsys.hsv_to_rgb(k / 8.0, 0.6, 0.55)
        palette += bytes((int(r * 255), int(g * 255), int(b * 255)))
    for k in range(8):  # 8-15: bright bar colours
        r, g, b = colorsys.hsv_to_rgb(k / 8.0, 0.9, 1.0)
        palette += bytes((int(r * 255), int(g * 255), int(b * 255)))

    out = bytearray(b"GIF89a")
    out += struct.pack("<HHBBB", size, size, 0xF3, 0, 0)  # global table of 16 colours
    out += palette
    out += b"\x21\xFF\x0BNETSCAPE2.0\x03\x01" + struct.pack("<H", 0) + b"\x00"  # loop forever
    for f in range(frames):
        pixels = bytearray(size * size)
        for y in range(size):
            for x in range(size):
                if f * 16 <= x < f * 16 + 16:
                    pixels[y * size + x] = 8 + ((y // 12 + f) % 8)
                else:
                    pixels[y * size + x] = ((x + y) // 24 + f) % 8
        out += b"\x21\xF9\x04" + struct.pack("<BHB", 0x04, 12, 0) + b"\x00"  # 120 ms, keep frame
        out += b"\x2C" + struct.pack("<HHHHB", 0, 0, size, size, 0)
        data = lzw_encode(pixels, 4)
        out.append(4)
        for i in range(0, len(data), 255):
            block = data[i:i + 255]
            out.append(len(block))
            out += block
        out.append(0)
    out.append(0x3B)
    return bytes(out)


_GIF = None


def gif_bytes():
    global _GIF
    if _GIF is None:
        _GIF = make_gif()
    return _GIF


# ---------------------------------------------------------------------------------------------
# Media files
# ---------------------------------------------------------------------------------------------

MEDIA_TYPES = {"ts": "video/mp2t", "mkv": "video/x-matroska", "mp4": "video/mp4", "m3u8": "application/vnd.apple.mpegurl"}
_TS_DURATION = None


def ts_duration():
    """Duration of sample.ts from its video timestamps (no ffprobe needed)."""
    global _TS_DURATION
    if _TS_DURATION is not None:
        return _TS_DURATION
    stamps = []
    try:
        with open(os.path.join(FIXTURES, "sample.ts"), "rb") as fh:
            data = fh.read()
        for off in range(0, len(data) - 187, 188):
            pkt = data[off:off + 188]
            if pkt[0] != 0x47 or not pkt[1] & 0x40:
                continue
            control, p = (pkt[3] >> 4) & 3, 4
            if control == 2:
                continue
            if control == 3:
                p += 1 + pkt[4]
            if p + 14 > 188 or pkt[p:p + 3] != b"\x00\x00\x01" or not 0xE0 <= pkt[p + 3] <= 0xEF:
                continue
            if pkt[p + 7] & 0x80:
                b = pkt[p + 9:p + 14]
                stamps.append(((b[0] >> 1) & 7) << 30 | b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | b[4] >> 1)
    except (OSError, IndexError):
        pass
    _TS_DURATION = (max(stamps) - min(stamps)) / 90000.0 + 0.04 if len(stamps) > 1 else 6.0
    return _TS_DURATION


def hls_playlist(host):
    d = ts_duration()
    return ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n"
            "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:%.3f,\n%s/hls/seg0.ts\n#EXT-X-DISCONTINUITY\n"
            "#EXTINF:%.3f,\n%s/hls/seg1.ts\n#EXT-X-ENDLIST\n" % (math.ceil(d), d, host, d, host)).encode()


# ---------------------------------------------------------------------------------------------
# Fake TMDB
# ---------------------------------------------------------------------------------------------


def synth_film(tmdb_id):
    """A believable film for any TMDB id that is not in the demo catalogue."""
    rng = seeded("tmdb-film", tmdb_id)
    year = rng.randint(1975, 2025)
    cast, roles, director = _cast("tmdb-film", tmdb_id)
    title = "%s %s" % (rng.choice(ADJ), rng.choice(NOUN))
    return {
        "kind": "movie", "title": title, "year": year, "genres": rng.sample(["Drama", "Thriller", "Comedy",
                                                                                "Action", "Mystery"], 2),
        "rating": round(rng.uniform(5.5, 8.5), 1), "minutes": rng.randint(85, 150), "tagline": "",
        "plot": "A %s about a %s that nobody saw coming." % (rng.choice(["drama", "thriller", "story"]),
                                                            rng.choice(NOUN).lower()),
        "tmdb_id": tmdb_id, "imdb_id": "tt%07d" % (8000000 + tmdb_id % 1000000),
        "poster": "film-t%d" % (tmdb_id % 997), "backdrop": "backdrop-t%d" % (tmdb_id % 199),
        "release_date": "%d-%02d-%02d" % (year, rng.randint(1, 12), rng.randint(1, 28)),
        "cast": cast, "roles": roles, "director": director,
    }


def synth_show(tmdb_id):
    rng = seeded("tmdb-show", tmdb_id)
    year = rng.randint(1990, 2025)
    cast, roles, director = _cast("tmdb-show", tmdb_id)
    seasons = [{"season": s, "episodes": [{"id": s * 100 + e, "air_date": "%d-01-01" % (year + s - 1)}
                                          for e in range(1, 9)]} for s in range(1, rng.randint(2, 4))]
    return {
        "kind": "tv", "title": "%s %s" % (rng.choice(ADJ), rng.choice(NOUN)), "year": year,
        "genres": ["Drama"], "rating": round(rng.uniform(6.0, 8.8), 1), "tagline": "",
        "plot": "A long-running series about a %s." % rng.choice(NOUN).lower(),
        "tmdb_id": tmdb_id, "imdb_id": "tt%07d" % (8500000 + tmdb_id % 1000000),
        "poster": "series-t%d" % (tmdb_id % 499), "backdrop": "backdrop-t%d" % (tmdb_id % 199),
        "first_air_date": "%d-%02d-01" % (year, rng.randint(1, 12)),
        "cast": cast, "roles": roles, "director": director, "seasons": seasons,
    }


def tmdb_film(tmdb_id):
    return FILM_BY_TMDB.get(tmdb_id) or synth_film(tmdb_id)


def tmdb_show(tmdb_id):
    return SERIES_BY_TMDB.get(tmdb_id) or synth_show(tmdb_id)


def genre_objects(genres):
    return [{"id": TMDB_GENRE_IDS.get(g, 18), "name": g} for g in genres]


def votes(item):
    return 800 + (item["tmdb_id"] * 37) % 24000


def tmdb_movie_result(f):
    return {
        "adult": False, "backdrop_path": "/%s.png" % f["backdrop"], "genre_ids": [TMDB_GENRE_IDS.get(g, 18) for g in f["genres"]],
        "id": f["tmdb_id"], "original_language": "en", "original_title": f["title"], "overview": f["plot"],
        "popularity": round(20 + (f["tmdb_id"] % 300) / 3.0, 3), "poster_path": "/%s.png" % f["poster"],
        "release_date": f["release_date"], "title": f["title"], "video": False,
        "vote_average": f["rating"], "vote_count": votes(f),
    }


def tmdb_tv_result(s):
    return {
        "adult": False, "backdrop_path": "/%s.png" % s["backdrop"], "genre_ids": [TMDB_GENRE_IDS.get(g, 18) for g in s["genres"]],
        "id": s["tmdb_id"], "origin_country": ["GB"], "original_language": "en", "original_name": s["title"],
        "overview": s["plot"], "popularity": round(20 + (s["tmdb_id"] % 300) / 3.0, 3),
        "poster_path": "/%s.png" % s["poster"], "first_air_date": s["first_air_date"], "name": s["title"],
        "vote_average": s["rating"], "vote_count": votes(s),
    }


def paged(results):
    return {"page": 1, "results": results, "total_pages": 1, "total_results": len(results)}


def tmdb_trending():
    picks = [tmdb_movie_result(f) for f in FILMS[:6]] + [tmdb_tv_result(s) for s in SERIES[:4]]
    order = [0, 6, 1, 2, 7, 3, 8, 4, 9, 5]  # mix films and shows
    results = []
    for k in order:
        item = dict(picks[k])
        item["media_type"] = "movie" if k < 6 else "tv"
        results.append(item)
    return {"page": 1, "results": results, "total_pages": 1000, "total_results": 20000}


def title_matches(query, title):
    q, t = normalize_title(query), normalize_title(title)
    return bool(q) and (q == t or (len(q) >= 3 and (q in t or t in q)))


def tmdb_search(query, year, show):
    items = SERIES if show else FILMS
    found = [x for x in items if title_matches(query, x["title"])]
    if year and year.isdigit():
        found = [x for x in found if x["year"] == int(year)]
    found.sort(key=lambda x: normalize_title(x["title"]) != normalize_title(query))
    return paged([tmdb_tv_result(x) if show else tmdb_movie_result(x) for x in found[:10]])


def person_stub(p, order):
    return {
        "adult": False, "gender": 1 + p % 2, "id": PERSON_BASE + p, "known_for_department": "Acting",
        "name": PEOPLE[p], "original_name": PEOPLE[p], "popularity": round(5 + p * 0.73, 3),
        "profile_path": "/person-%d.png" % (p + 1), "order": order,
    }


def tmdb_movie_details(f, append):
    out = {
        "adult": False, "backdrop_path": "/%s.png" % f["backdrop"], "belongs_to_collection": None,
        "budget": 20000000 + (f["tmdb_id"] % 50) * 1000000, "genres": genre_objects(f["genres"]), "homepage": "",
        "id": f["tmdb_id"], "imdb_id": f["imdb_id"], "origin_country": ["GB"], "original_language": "en",
        "original_title": f["title"], "overview": f["plot"], "popularity": round(20 + (f["tmdb_id"] % 300) / 3.0, 3),
        "poster_path": "/%s.png" % f["poster"],
        "production_companies": [{"id": 1, "logo_path": None, "name": "Mock Pictures", "origin_country": "GB"}],
        "production_countries": [{"iso_3166_1": "GB", "name": "United Kingdom"}],
        "release_date": f["release_date"], "revenue": 50000000 + (f["tmdb_id"] % 90) * 1000000,
        "runtime": f["minutes"], "spoken_languages": [{"english_name": "English", "iso_639_1": "en", "name": "English"}],
        "status": "Released", "tagline": f["tagline"], "title": f["title"], "video": False,
        "vote_average": f["rating"], "vote_count": votes(f),
    }
    if "credits" in append:
        cast = []
        for order, (p, role) in enumerate(zip(f["cast"], f["roles"])):
            entry = person_stub(p, order)
            entry.update({"cast_id": order + 1, "character": role, "credit_id": "c%d%02d" % (f["tmdb_id"], order)})
            cast.append(entry)
        director = person_stub(f["director"], 0)
        director.pop("order")
        director.update({"known_for_department": "Directing", "credit_id": "d%d" % f["tmdb_id"],
                         "department": "Directing", "job": "Director"})
        out["credits"] = {"cast": cast, "crew": [director]}
    if "external_ids" in append:
        out["external_ids"] = {"imdb_id": f["imdb_id"], "wikidata_id": None, "facebook_id": None,
                               "instagram_id": None, "twitter_id": None}
    if "videos" in append:
        out["videos"] = {"results": [{
            "iso_639_1": "en", "iso_3166_1": "US", "name": "Official Trailer", "key": "chudMock%03d" % (f["tmdb_id"] % 1000),
            "site": "YouTube", "size": 1080, "type": "Trailer", "official": True,
            "published_at": "%sT16:00:00.000Z" % f["release_date"], "id": "v%d" % f["tmdb_id"],
        }]}
    if "release_dates" in append:
        cert_gb = ["12A", "15", "PG", "18"][f["tmdb_id"] % 4]
        cert_us = {"12A": "PG-13", "15": "R", "PG": "PG", "18": "R"}[cert_gb]
        out["release_dates"] = {"results": [
            {"iso_3166_1": code, "release_dates": [{
                "certification": cert, "descriptors": [], "iso_639_1": "", "note": "",
                "release_date": "%sT00:00:00.000Z" % f["release_date"], "type": 3}]}
            for code, cert in (("GB", cert_gb), ("US", cert_us))]}
    return out


def tmdb_tv_details(s, append):
    episodes = sum(len(season["episodes"]) for season in s["seasons"])
    director = s["director"]
    out = {
        "adult": False, "backdrop_path": "/%s.png" % s["backdrop"],
        "created_by": [{"id": PERSON_BASE + director, "credit_id": "cb%d" % s["tmdb_id"], "name": PEOPLE[director],
                        "original_name": PEOPLE[director], "gender": 1 + director % 2,
                        "profile_path": "/person-%d.png" % (director + 1)}],
        "episode_run_time": [45], "first_air_date": s["first_air_date"], "genres": genre_objects(s["genres"]),
        "homepage": "", "id": s["tmdb_id"], "in_production": True, "languages": ["en"],
        "last_air_date": s["seasons"][-1]["episodes"][-1]["air_date"], "name": s["title"],
        "networks": [{"id": 1, "logo_path": None, "name": "Crown One", "origin_country": "GB"}],
        "number_of_episodes": episodes, "number_of_seasons": len(s["seasons"]), "origin_country": ["GB"],
        "original_language": "en", "original_name": s["title"], "overview": s["plot"],
        "popularity": round(20 + (s["tmdb_id"] % 300) / 3.0, 3), "poster_path": "/%s.png" % s["poster"],
        "seasons": [{"air_date": season["episodes"][0]["air_date"], "episode_count": len(season["episodes"]),
                     "id": s["tmdb_id"] * 10 + season["season"], "name": "Season %d" % season["season"],
                     "overview": "", "poster_path": "/%s.png" % s["poster"], "season_number": season["season"],
                     "vote_average": s["rating"]} for season in s["seasons"]],
        "status": "Returning Series", "tagline": s["tagline"], "type": "Scripted",
        "vote_average": s["rating"], "vote_count": votes(s),
    }
    if "aggregate_credits" in append:
        cast = []
        for order, (p, role) in enumerate(zip(s["cast"], s["roles"])):
            entry = person_stub(p, order)
            entry.update({"roles": [{"credit_id": "c%d%02d" % (s["tmdb_id"], order), "character": role,
                                     "episode_count": max(1, episodes - order)}],
                          "total_episode_count": max(1, episodes - order)})
            cast.append(entry)
        crew = person_stub(director, 0)
        crew.pop("order")
        crew.update({"known_for_department": "Directing", "department": "Directing", "total_episode_count": episodes,
                     "jobs": [{"credit_id": "d%d" % s["tmdb_id"], "job": "Director", "episode_count": episodes}]})
        out["aggregate_credits"] = {"cast": cast, "crew": [crew]}
    if "external_ids" in append:
        out["external_ids"] = {"imdb_id": s["imdb_id"], "tvdb_id": 400000 + s["tmdb_id"] % 100000,
                               "tvrage_id": None, "wikidata_id": None, "facebook_id": None,
                               "instagram_id": None, "twitter_id": None}
    if "videos" in append:
        out["videos"] = {"results": [{
            "iso_639_1": "en", "iso_3166_1": "US", "name": "Season 1 Trailer",
            "key": "chudMock%03d" % (s["tmdb_id"] % 1000), "site": "YouTube", "size": 1080, "type": "Trailer",
            "official": True, "published_at": "%sT16:00:00.000Z" % s["first_air_date"], "id": "v%d" % s["tmdb_id"],
        }]}
    if "content_ratings" in append:
        out["content_ratings"] = {"results": [
            {"descriptors": [], "iso_3166_1": "GB", "rating": "15"},
            {"descriptors": [], "iso_3166_1": "US", "rating": "TV-MA"},
        ]}
    return out


def tmdb_person(person_id, append):
    p = person_id - PERSON_BASE
    rng = seeded("person", person_id)
    if 0 <= p < len(PEOPLE):
        name, credits = PEOPLE[p], PERSON_CREDITS[p]
    else:
        name, credits = "%s %s" % (rng.choice(CHAR_FIRST), rng.choice(CHAR_LAST)), []
    place = BIRTHPLACES[person_id % len(BIRTHPLACES)]
    birthday = "%d-%02d-%02d" % (1955 + person_id % 45, person_id % 12 + 1, person_id % 27 + 1)
    imdb = "nm0000%03d" % (100 + (p if 0 <= p < 900 else person_id % 900))
    cast, crew = [], []
    for item, role, job in credits[:9]:
        base = tmdb_movie_result(item) if item["kind"] == "movie" else tmdb_tv_result(item)
        base["media_type"] = item["kind"]
        base["credit_id"] = "pc%d%d" % (person_id, item["tmdb_id"])
        if job:
            base.update({"department": "Directing", "job": job})
            crew.append(base)
        else:
            base["character"] = role
            if item["kind"] == "tv":
                base["episode_count"] = 8
            cast.append(base)
    k = 0
    while len(cast) < 15:  # top up with titles that are not in the catalogue
        tmdb_id = 990000 + (person_id % 1000) * 20 + k
        k += 1
        if k % 3:
            base = tmdb_movie_result(synth_film(tmdb_id))
            base["media_type"] = "movie"
        else:
            base = tmdb_tv_result(synth_show(tmdb_id))
            base["media_type"] = "tv"
            base["episode_count"] = 1 + k % 10
        base["character"] = "%s %s" % (rng.choice(CHAR_FIRST), rng.choice(CHAR_LAST))
        base["credit_id"] = "pc%d%d" % (person_id, tmdb_id)
        cast.append(base)
    titles = [c.get("title") or c.get("name") for c in cast[:3]]
    out = {
        "adult": False, "also_known_as": [],
        "biography": ("%s is an actor and producer from %s. After training at drama school, %s broke through "
                      "with %s and has since appeared in %s and %s.\n\nOff screen, %s supports young filmmakers "
                      "through a regional arts charity." % (name, place.split(",")[0], name.split()[0],
                                                          titles[0], titles[1], titles[2], name.split()[0])),
        "birthday": birthday, "deathday": None, "gender": 1 + person_id % 2, "homepage": None, "id": person_id,
        "imdb_id": imdb, "known_for_department": "Acting", "name": name, "place_of_birth": place,
        "popularity": round(5 + (person_id % 100) * 0.37, 3),
        "profile_path": "/person-%d.png" % ((p + 1) if 0 <= p < len(PEOPLE) else person_id % 97 + 100),
    }
    if "combined_credits" in append:
        out["combined_credits"] = {"cast": cast, "crew": crew, "id": person_id}
    if "external_ids" in append:
        out["external_ids"] = {"imdb_id": imdb, "facebook_id": None, "instagram_id": None, "tiktok_id": None,
                               "twitter_id": None, "wikidata_id": None, "youtube_id": None, "id": person_id}
    return out


# ---------------------------------------------------------------------------------------------
# Fake Trakt
# ---------------------------------------------------------------------------------------------

TRAKT_USERS = [("filmfan_88", "Sam Carter"), ("nightowl", "Night Owl"), ("popcorn_queen", "Priya"),
               ("reelthoughts", ""), ("couchcritic", "Jamie L."), ("bingewatcher", "Alex M."),
               ("northern_reels", "Chris"), ("sofa_cinema", "")]
TRAKT_COMMENTS = [
    "Gripping from start to finish. The last twenty minutes had me on the edge of the sofa.",
    "Beautifully shot, but the middle act drags a little. Still worth a watch.",
    "Not what I expected at all, in a good way. The lead is fantastic.",
    "Great soundtrack. I've had the theme stuck in my head all week.",
    "Solid 8/10. Would happily watch it again with friends.",
    "The twist at the end is incredible. I did not see the lighthouse scene coming at all.",
    "A proper old-school crowd-pleaser with a lot of heart.",
    "Some clunky dialogue, but the performances carry it.",
]
TRAKT_REVIEW = ("I went in knowing nothing and came out thinking about it for days. The pacing is patient without "
                "ever being slow, the cast are uniformly excellent and the final act ties the whole thing together "
                "in a way that rewards paying attention. The score deserves special mention too: it does a lot of "
                "the emotional heavy lifting without ever getting in the way. Easily one of my favourites of the "
                "year, and one I'll be recommending to anyone who will listen.")


def trakt_media(tmdb_id, show):
    item = tmdb_show(tmdb_id) if show else tmdb_film(tmdb_id)
    return {"title": item["title"], "year": item["year"],
            "ids": {"trakt": 5000 + tmdb_id % 100000, "slug": slugify("%s %d" % (item["title"], item["year"])),
                    "imdb": item["imdb_id"], "tmdb": tmdb_id}}


def trakt_ratings(key):
    rng = seeded("trakt-rating", key)
    total = rng.randint(800, 25000)
    distribution = {str(k): int(total * w) for k, w in zip(range(1, 11),
                                                            (0.01, 0.01, 0.02, 0.03, 0.06, 0.12, 0.2, 0.26, 0.17, 0.12))}
    return {"rating": round(rng.uniform(6.8, 8.6), 1), "votes": total, "distribution": distribution}


def trakt_comments(key, limit):
    rng = seeded("trakt-comments", key)
    texts = rng.sample(TRAKT_COMMENTS, 6)
    comments = []
    for k in range(6):
        user, name = TRAKT_USERS[(k + len(key)) % len(TRAKT_USERS)]
        created = datetime(2026, 5, 1, 12, 0, tzinfo=timezone.utc).timestamp() - k * 9 * 86400 - k * 3600
        review = k == 2
        comments.append({
            "id": 700000 + (zlib.crc32(key.encode()) % 10000) * 10 + k,
            "comment": TRAKT_REVIEW if review else texts[k],
            "spoiler": k == 3, "review": review, "parent_id": 0,
            "created_at": iso_z(created), "updated_at": iso_z(created + 600),
            "replies": (k * 3) % 5, "likes": 120 - k * 17,
            "user_stats": {"rating": [8, 7, 9, 10, 6, 8][k], "play_count": 1, "completed_count": 1},
            "user": {"username": user, "private": False, "name": name, "vip": k == 0, "vip_ep": False,
                     "ids": {"slug": user}},
        })
    return comments[:max(0, limit)]


# ---------------------------------------------------------------------------------------------
# Fake OpenSubtitles
# ---------------------------------------------------------------------------------------------


def opensubs_search(params):
    query = params.get("query", "") or "neon runner 2021"
    languages = [x for x in params.get("languages", "en").split(",") if x] or ["en"]
    words = [w for w in re.split(r"[^a-z0-9]+", query.lower()) if w]
    base = ".".join(w.capitalize() for w in words) or "Neon.Runner.2021"
    variants = [(".1080p.WEB", 1500, False, False), (".720p.BluRay", 820, True, False), (".2160p.WEB-DL", 310, False, True)]
    data = []
    for k, (suffix, downloads, hearing, forced) in enumerate(variants):
        file_id = 101 + k
        lang = languages[k % len(languages)]
        data.append({
            "id": str(k + 1), "type": "subtitle",
            "attributes": {
                "subtitle_id": str(k + 1), "language": lang, "download_count": downloads,
                "new_download_count": downloads // 10, "hearing_impaired": hearing, "hd": True, "fps": 23.976,
                "votes": 3 + k, "ratings": 8.0, "from_trusted": True, "foreign_parts_only": forced,
                "upload_date": "2026-03-0%dT10:00:00Z" % (k + 1), "ai_translated": False, "machine_translated": False,
                "release": base + suffix, "comments": "", "legacy_subtitle_id": 9000000 + k,
                "uploader": {"uploader_id": 42, "name": "mockuploader", "rank": "trusted"},
                "feature_details": {"feature_id": 1000 + k, "feature_type": "Movie", "title": query, "movie_name": query},
                "url": "https://www.opensubtitles.com/en/subtitles/mock-%d" % file_id, "related_links": [],
                "files": [{"file_id": file_id, "cd_number": 1,
                           "file_name": "%s.%s.srt" % (".".join(words[:3]) or "neon.runner", lang)}],
            },
        })
    return {"total_pages": 1, "total_count": len(data), "per_page": 60, "page": 1, "data": data}


def srt_for(sub_id):
    return ("1\n00:00:00,500 --> 00:00:02,400\nWhere do you think you're going?\n\n"
            "2\n00:00:02,800 --> 00:00:04,900\nSomewhere the lights never go out.\n\n"
            "3\n00:00:05,300 --> 00:00:07,200\nThen you'd better keep up.\n\n"
            "4\n00:00:07,600 --> 00:00:09,800\n<i>(rain hammering on the roof)</i>\n[subtitle %s]\n" % sub_id).encode("utf-8")


# ---------------------------------------------------------------------------------------------
# Fake Claude (Anthropic Messages API)
# ---------------------------------------------------------------------------------------------

CLAUDE_MODEL = "claude-sonnet-5-5"
CLAUDE_ANSWER = ("Try Neon Runner (2021) tonight: a rain-soaked chase through a megacity that never switches off, "
                 "rated 7.8 in your catalogue. If you'd rather laugh, Paper Moons (2017) is a quick, warm comedy.")
_COUNTER = {"tool": 0, "msg": 0, "issue": 0, "telegram": 0}
_COUNTER_LOCK = threading.Lock()


def next_count(name):
    with _COUNTER_LOCK:
        _COUNTER[name] += 1
        return _COUNTER[name]


def claude_reply(body):
    messages = body.get("messages") or []
    last = next((m for m in reversed(messages) if isinstance(m, dict) and m.get("role") == "user"), {})
    content = last.get("content")
    has_result = isinstance(content, list) and any(
        isinstance(block, dict) and block.get("type") == "tool_result" for block in content)
    if has_result:
        blocks, stop = [{"type": "text", "text": CLAUDE_ANSWER}], "end_turn"
    else:
        blocks = [{"type": "tool_use", "id": "toolu_%d" % next_count("tool"), "name": "search_catalog",
                   "input": {"query": "neon"}}]
        stop = "tool_use"
    return {
        "id": "msg_mock%06d" % next_count("msg"), "type": "message", "role": "assistant",
        "model": body.get("model") or CLAUDE_MODEL, "content": blocks, "stop_reason": stop, "stop_sequence": None,
        "usage": {"input_tokens": max(1, len(json.dumps(messages)) // 4), "output_tokens": 24 if has_result else 12,
                  "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0},
    }


def claude_events(message):
    """The same reply as server-sent events, for requests with "stream": true."""
    def event(name, data):
        return ("event: %s\ndata: %s\n\n" % (name, json.dumps(data))).encode("utf-8")

    start = dict(message, content=[], stop_reason=None, usage=dict(message["usage"], output_tokens=1))
    yield event("message_start", {"type": "message_start", "message": start})
    for index, block in enumerate(message["content"]):
        if block["type"] == "text":
            yield event("content_block_start", {"type": "content_block_start", "index": index,
                                                "content_block": {"type": "text", "text": ""}})
            words = block["text"].split(" ")
            for k in range(0, len(words), 6):
                piece = " ".join(words[k:k + 6]) + (" " if k + 6 < len(words) else "")
                yield event("content_block_delta", {"type": "content_block_delta", "index": index,
                                                    "delta": {"type": "text_delta", "text": piece}})
        else:
            yield event("content_block_start", {"type": "content_block_start", "index": index,
                                                "content_block": dict(block, input={})})
            yield event("content_block_delta", {"type": "content_block_delta", "index": index,
                                                "delta": {"type": "input_json_delta",
                                                          "partial_json": json.dumps(block["input"])}})
        yield event("content_block_stop", {"type": "content_block_stop", "index": index})
    yield event("message_delta", {"type": "message_delta",
                                  "delta": {"stop_reason": message["stop_reason"], "stop_sequence": None},
                                  "usage": {"output_tokens": message["usage"]["output_tokens"]}})
    yield event("message_stop", {"type": "message_stop"})


# ---------------------------------------------------------------------------------------------
# HTTP server
# ---------------------------------------------------------------------------------------------


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive; every response has a length or is chunked
    server_version = "ChudMock/1.0"
    timeout = 120

    # ---- plumbing ----

    def log_message(self, fmt, *args):
        if VERBOSE:
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def end_headers(self):
        self._headers_sent = True
        super().end_headers()

    # One handler serves every request on a keep-alive connection, so the body is reset each time
    # and always read before answering (otherwise it would be taken for the next request).
    def do_GET(self):
        self._body = None
        self.dispatch()

    def do_HEAD(self):
        self._body = None
        self.dispatch()

    def do_POST(self):
        self._body = None
        self.read_body()
        self.dispatch()

    do_PUT = do_POST
    do_DELETE = do_POST

    def read_body(self):
        if self._body is not None:
            return self._body
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = bytearray()
            while True:
                size = int((self.rfile.readline().split(b";")[0].strip() or b"0"), 16)
                if size == 0:
                    while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                        pass
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
            self._body = bytes(data)
        else:
            length = int(self.headers.get("Content-Length") or 0)
            self._body = self.rfile.read(length) if length > 0 else b""
        return self._body

    def body_params(self):
        """JSON object or form fields from the request body."""
        raw = self.read_body() if self.command in ("POST", "PUT", "DELETE") else b""
        if not raw:
            return {}
        try:
            value = json.loads(raw.decode("utf-8"))
            return value if isinstance(value, dict) else {"_": value}
        except ValueError:
            return {k: v[0] for k, v in parse_qs(raw.decode("utf-8", "replace"), keep_blank_values=True).items()}

    @property
    def host(self):
        return "http://" + (self.headers.get("Host") or "127.0.0.1:%d" % self.server.server_address[1])

    def send_bytes(self, body, ctype="application/json", status=200, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, obj, status=200, php=False, headers=None):
        text = php_json(obj) if php else json.dumps(obj)
        self.send_bytes(text.encode("utf-8"), "application/json; charset=utf-8", status, headers)

    def send_text(self, text, status=200, ctype="text/plain; charset=utf-8"):
        self.send_bytes(text.encode("utf-8"), ctype, status)

    def send_stream(self, chunks, ctype, delay=0.0):
        """Chunked response: the body is produced piece by piece and never held whole."""
        if delay and self.command != "HEAD":
            time.sleep(delay)
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        if self.command == "HEAD":
            return
        for chunk in chunks:
            if chunk:
                self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
        self.wfile.write(b"0\r\n\r\n")

    def serve_file(self, name, ctype):
        """A fixture file with HEAD and single byte-range support, so players can seek."""
        path = os.path.join(FIXTURES, name)
        try:
            size = os.path.getsize(path)
        except OSError:
            return self.send_text("missing fixture %s\n" % name, 404)
        start, end, status = 0, size - 1, 200
        header = (self.headers.get("Range") or "").strip()
        match = re.match(r"^bytes=(\d*)-(\d*)$", header)
        if match and (match.group(1) or match.group(2)):
            first, last = match.group(1), match.group(2)
            if first == "":  # the last N bytes
                start, end = max(0, size - int(last)), size - 1
                bad = int(last) == 0
            else:
                start = int(first)
                end = min(int(last), size - 1) if last else size - 1
                bad = start >= size or start > end
            if bad:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Last-Modified", self.date_time_string(int(os.path.getmtime(path))))
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as fh:
            fh.seek(start)
            left = end - start + 1
            while left > 0:
                piece = fh.read(min(64 * 1024, left))
                if not piece:
                    break
                self.wfile.write(piece)
                left -= len(piece)

    def not_found(self):
        self.send_json({"error": "not found", "path": self.path}, 404)

    # ---- routing ----

    def dispatch(self):
        self._headers_sent = False
        try:
            self.route()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            self.close_connection = True
        except Exception:
            traceback.print_exc()
            if self._headers_sent:
                self.close_connection = True
            else:
                self.send_json({"error": "mock server error", "detail": traceback.format_exc(limit=3)}, 500)

    def route(self):
        url = urlsplit(self.path)
        path = url.path
        seg = [unquote(s) for s in path.split("/")[1:]]
        query = {k: v[0] for k, v in parse_qs(url.query, keep_blank_values=True).items()}
        head = seg[0] if seg else ""

        if path == "/health":
            return self.send_text("ok")
        if path == "/":
            return self.send_text("CHUD STREAMS mock server. Endpoints are listed in Tests/README.md.\n")
        if path == "/player_api.php":
            params = dict(query)
            params.update({k: str(v) for k, v in self.body_params().items()})
            return self.xtream_api(params)
        if path == "/xmltv.php":
            if not valid_login(query.get("username", ""), query.get("password", "")):
                return self.send_text("Forbidden\n", 403)
            return self.send_bytes(cached_epg(self.host)[0], "application/xml; charset=utf-8")
        if path == "/get.php":
            user, password = query.get("username", ""), query.get("password", "")
            if not valid_login(user, password):
                return self.send_text("Forbidden\n", 403)
            ext = "m3u8" if query.get("output") in ("m3u8", "hls") else "ts"
            lines = account_m3u_lines(self.host, user, password, ext)
            if user == "big":
                return self.send_stream(_buffered(line.encode("utf-8") for line in lines), "audio/x-mpegurl")
            return self.send_bytes("".join(lines).encode("utf-8"), "audio/x-mpegurl")
        if path == "/playlist.m3u":
            return self.send_bytes(plain_m3u(self.host), "audio/x-mpegurl")
        if path == "/epg.xml":
            return self.send_bytes(cached_epg(self.host)[0], "application/xml; charset=utf-8")
        if path == "/epg.xml.gz":
            return self.send_bytes(cached_epg(self.host)[1], "application/gzip")
        if head in ("live", "movie", "series") and len(seg) == 4:
            return self.xtream_stream(head, seg[1], seg[2], seg[3])
        if head == "timeshift" and len(seg) == 6:
            if not valid_login(seg[1], seg[2]):
                return self.send_text("Forbidden\n", 403)
            if not seg[3].isdigit() or not re.match(r"^\d{4}-\d{2}-\d{2}:\d{2}-\d{2}$", seg[4]):
                return self.send_text("Bad timeshift address\n", 400)
            return self.serve_file("sample.ts", MEDIA_TYPES["ts"])
        if head == "hls" and len(seg) == 2 and re.match(r"^seg\d+\.ts$", seg[1]):
            return self.serve_file("sample.ts", MEDIA_TYPES["ts"])
        if head == "stream" and len(seg) == 2 and re.match(r"^\d+\.ts$", seg[1]):
            return self.serve_file("sample.ts", MEDIA_TYPES["ts"])
        if head == "vod" and len(seg) == 2 and re.match(r"^\d+\.(mkv|mp4)$", seg[1]):
            ext = seg[1].rsplit(".", 1)[1]
            return self.serve_file("sample." + ext, MEDIA_TYPES[ext])
        if head == "images" and len(seg) == 2:
            return self.image(seg[1])
        if head == "tmdbimg" and len(seg) == 3:
            return self.image(seg[2])
        if head == "tmdb" and len(seg) >= 2 and seg[1] == "3":
            return self.tmdb(seg[2:], query)
        if head == "trakt":
            return self.trakt(seg[1:], query)
        if head == "opensubs" and seg[1:3] == ["api", "v1"]:
            return self.opensubs(seg[3:], query)
        if head == "subs" and len(seg) == 2 and seg[1].endswith(".srt"):
            return self.send_bytes(srt_for(seg[1][:-4]), "application/x-subrip; charset=utf-8")
        if head == "claude" and len(seg) >= 2 and seg[1] == "v1":
            return self.claude(seg[2:], query)
        if head == "github":
            return self.github(seg[1:])
        if head == "issues" and len(seg) == 2:
            return self.send_text("<html><body><h1>Mock issue #%s</h1></body></html>" % xml_escape(seg[1]),
                                  ctype="text/html; charset=utf-8")
        if head == "telegram" and len(seg) == 3 and seg[1].startswith("bot"):
            return self.telegram(seg[2], query)
        if len(seg) == 3 and valid_login(seg[0], seg[1]):  # short live form: /user/pass/id
            return self.xtream_stream("live", seg[0], seg[1], seg[2])
        return self.not_found()

    # ---- Xtream ----

    def xtream_api(self, params):
        user, password = params.get("username", ""), params.get("password", "")
        if not valid_login(user, password):
            return self.send_json({"user_info": {"auth": 0}}, php=True)
        big = user == "big"
        host = self.host
        action = params.get("action", "")
        category = params.get("category_id") or None
        t = now()

        if not action:
            hostname, _, port = self.host[len("http://"):].rpartition(":")
            if not port.isdigit():
                hostname, port = self.host[len("http://"):], "80"
            return self.send_json({
                "user_info": {
                    "username": user, "password": password, "message": "Welcome to the CHUD STREAMS test server",
                    "auth": 1, "status": "Active", "exp_date": str(t + 30 * 86400), "is_trial": "0",
                    "active_cons": "1", "created_at": str(t - 200 * 86400), "max_connections": "2",
                    "allowed_output_formats": ["m3u8", "ts"],
                },
                "server_info": {
                    "url": hostname, "port": port, "https_port": "443", "server_protocol": "http",
                    "rtmp_port": "8880", "timezone": "Europe/London", "timestamp_now": t,
                    "time_now": local_str(t), "process": True,
                },
            }, php=True)

        if action == "get_live_categories":
            return self.send_json(category_list(BIG_LIVE_CATEGORIES if big else LIVE_CATEGORIES), php=True)
        if action == "get_vod_categories":
            return self.send_json(category_list(BIG_VOD_CATEGORIES if big else VOD_CATEGORIES), php=True)
        if action == "get_series_categories":
            return self.send_json(category_list(BIG_SERIES_CATEGORIES if big else SERIES_CATEGORIES), php=True)

        if action in ("get_live_streams", "get_vod_streams", "get_series"):
            if big:
                return self.big_list(action, category, host)
            if action == "get_live_streams":
                items = [live_entry(c, host) for c in CHANNELS if category in (None, c["category_id"])]
            elif action == "get_vod_streams":
                items = [vod_entry(f, host, t) for f in FILMS if category in (None, f["category_id"])]
            else:
                items = [series_entry(s, host, t) for s in SERIES if category in (None, s["category_id"])]
            return self.send_json(items, php=True)

        if action == "get_vod_info":
            film = film_for(_int(params.get("vod_id")))
            return self.send_json(vod_info(film, host, t) if film else {"info": [], "movie_data": []}, php=True)
        if action == "get_series_info":
            show = series_for(_int(params.get("series_id")))
            return self.send_json(series_info(show, host, t) if show else {"seasons": [], "info": [], "episodes": []},
                                  php=True)
        if action in ("get_short_epg", "get_simple_data_table"):
            channel = channel_for(_int(params.get("stream_id")))
            if channel is None:
                return self.send_json({"epg_listings": []}, php=True)
            programmes = schedule(channel)
            if action == "get_short_epg":
                limit = _int(params.get("limit")) or 4
                programmes = [p for p in programmes if p["stop"] > t][:limit]
            return self.send_json({"epg_listings": [xtream_listing(p, channel, t) for p in programmes]}, php=True)
        return self.send_json([], php=True)

    def big_list(self, action, category, host):
        """Provider-sized lists, generated item by item and streamed."""
        if action == "get_live_streams":
            base, count, maker = 1, 60, big_live_entries
        elif action == "get_vod_streams":
            base, count, maker = 101, 80, big_vod_entries
        else:
            base, count, maker = 201, 40, big_series_entries
        index = None
        if category is not None:
            index = _int(category) - base
            if not 0 <= index < count:
                return self.send_json([], php=True)
        # A busy panel takes a long time to start sending the whole film list.
        delay = BIG_VOD_DELAY if action == "get_vod_streams" and index is None else 0.0
        return self.send_stream(json_array_chunks(maker(host, index)), "application/json; charset=utf-8", delay)

    def xtream_stream(self, kind, user, password, filename):
        if not valid_login(user, password):
            return self.send_text("Forbidden\n", 403)
        stem, dot, ext = filename.rpartition(".")
        if not dot:
            stem, ext = filename, "ts"
        if not stem.isdigit():
            return self.not_found()
        ext = ext.lower()
        if kind == "live":
            if ext == "m3u8":
                return self.send_bytes(hls_playlist(self.host), MEDIA_TYPES["m3u8"])
            return self.serve_file("sample.ts", MEDIA_TYPES["ts"])
        if kind == "movie" and ext == "mp4":
            return self.serve_file("sample.mp4", MEDIA_TYPES["mp4"])
        return self.serve_file("sample.mkv", MEDIA_TYPES["mkv"])

    # ---- images ----

    def image(self, filename):
        stem, _, ext = filename.rpartition(".")
        if not re.match(r"^[A-Za-z0-9_.-]{1,80}$", stem or ""):
            return self.not_found()
        cache = {"Cache-Control": "public, max-age=86400"}
        if ext.lower() == "gif":
            return self.send_bytes(gif_bytes(), "image/gif", headers=cache)
        if ext.lower() not in ("png", "jpg", "jpeg", "webp"):
            return self.not_found()
        return self.send_bytes(png_for(stem), "image/png", headers=cache)

    # ---- TMDB ----

    def tmdb(self, parts, query):
        append = set(x.strip() for x in query.get("append_to_response", "").split(",") if x.strip())
        if len(parts) == 3 and parts[0] == "trending":
            return self.send_json(tmdb_trending())
        if parts == ["search", "movie"]:
            return self.send_json(tmdb_search(query.get("query", ""), query.get("year") or query.get(
                "primary_release_year"), False))
        if parts == ["search", "tv"]:
            return self.send_json(tmdb_search(query.get("query", ""), query.get("first_air_date_year") or query.get(
                "year"), True))
        if parts == ["search", "multi"]:
            movies = tmdb_search(query.get("query", ""), None, False)["results"]
            shows = tmdb_search(query.get("query", ""), None, True)["results"]
            return self.send_json(paged([dict(x, media_type="movie") for x in movies] +
                                        [dict(x, media_type="tv") for x in shows]))
        if parts == ["configuration"]:
            base = self.host + "/tmdbimg/"
            return self.send_json({"images": {"base_url": base, "secure_base_url": base,
                                              "poster_sizes": ["w185", "w342", "w500", "original"],
                                              "backdrop_sizes": ["w300", "w780", "w1280", "original"],
                                              "profile_sizes": ["w45", "w185", "original"]}})
        if len(parts) == 2 and parts[1].isdigit():
            item_id = int(parts[1])
            if parts[0] == "movie":
                return self.send_json(tmdb_movie_details(tmdb_film(item_id), append))
            if parts[0] == "tv":
                return self.send_json(tmdb_tv_details(tmdb_show(item_id), append))
            if parts[0] == "person":
                return self.send_json(tmdb_person(item_id, append))
        return self.send_json({"success": False, "status_code": 34,
                               "status_message": "The resource you requested could not be found."}, 404)

    # ---- Trakt ----

    def trakt(self, parts, query):
        if len(parts) == 3 and parts[:2] == ["search", "tmdb"] and parts[2].isdigit():
            show = query.get("type", "movie") == "show"
            key = "show" if show else "movie"
            return self.send_json([{"type": key, "score": 1000, key: trakt_media(int(parts[2]), show)}])
        if len(parts) >= 3 and parts[0] in ("movies", "shows"):
            rest = parts[2:]
            key = "/".join(parts[:2])
            if parts[0] == "shows" and len(rest) >= 4 and rest[0] == "seasons" and rest[2] == "episodes":
                key = "/".join(parts[:6])
                rest = rest[4:]
            if rest == ["ratings"]:
                return self.send_json(trakt_ratings(key))
            if rest and rest[0] == "comments":
                return self.send_json(trakt_comments(key, _int(query.get("limit")) or 10))
        return self.send_json({"error": "not found"}, 404)

    # ---- OpenSubtitles ----

    def opensubs(self, parts, query):
        if parts == ["subtitles"]:
            return self.send_json(opensubs_search(query))
        if parts == ["login"]:
            body = self.body_params()
            return self.send_json({"user": {"allowed_downloads": 100, "level": "Sub leecher", "user_id": 1,
                                            "ext_installed": False, "vip": False,
                                            "username": body.get("username", "mock")},
                                   "base_url": "api.opensubtitles.com", "token": "t", "status": 200})
        if parts == ["download"]:
            file_id = _int(self.body_params().get("file_id") or query.get("file_id")) or 101
            return self.send_json({"link": "%s/subs/%d.srt" % (self.host, file_id),
                                   "file_name": "chud.mock.%d.en.srt" % file_id, "requests": 1, "remaining": 99,
                                   "message": "Your quota will be renewed in 23 hours",
                                   "reset_time": "23 hours and 59 minutes",
                                   "reset_time_utc": iso_z(day_floor(now()) + 86400)})
        if parts == ["logout"]:
            return self.send_json({"message": "token successfully destroyed", "status": 200})
        return self.send_json({"message": "not found", "status": 404}, 404)

    # ---- Claude ----

    def claude(self, parts, query):
        if parts == ["models"] or (len(parts) == 2 and parts[0] == "models"):
            model = {"type": "model", "id": CLAUDE_MODEL, "display_name": "Claude Sonnet 5.5",
                     "created_at": "2026-01-01T00:00:00Z"}
            if len(parts) == 2:
                return self.send_json(dict(model, id=parts[1]))
            return self.send_json({"data": [model], "has_more": False, "first_id": CLAUDE_MODEL,
                                   "last_id": CLAUDE_MODEL})
        if parts == ["messages"]:
            if self.command != "POST":
                return self.send_json({"type": "error", "error": {"type": "invalid_request_error",
                                                                  "message": "Use POST"}}, 405)
            body = self.body_params()
            message = claude_reply(body)
            if body.get("stream"):
                return self.send_stream(claude_events(message), "text/event-stream; charset=utf-8")
            return self.send_json(message, headers={"request-id": "req_mock%06d" % _COUNTER["msg"]})
        return self.send_json({"type": "error", "error": {"type": "not_found_error", "message": "Not found"}}, 404)

    # ---- GitHub ----

    def github(self, parts):
        if len(parts) == 4 and parts[0] == "repos" and parts[3] == "issues":
            if self.command != "POST":
                return self.send_json([])
            body = self.body_params()
            number = next_count("issue")
            return self.send_json({
                "id": 900000 + number, "number": number, "html_url": "%s/issues/%d" % (self.host, number),
                "url": "%s/github/repos/%s/%s/issues/%d" % (self.host, parts[1], parts[2], number),
                "title": body.get("title", ""), "body": body.get("body", ""), "state": "open",
                "labels": [{"name": x} for x in body.get("labels", []) if isinstance(x, str)],
                "created_at": iso_z(now()).replace(".000", ""),
            }, 201)
        return self.send_json({"message": "Not Found", "documentation_url": "https://docs.github.com/rest"}, 404)

    # ---- Telegram ----

    def telegram(self, method, query):
        params = dict(query)
        params.update(self.body_params())
        bot = {"id": 777000111, "is_bot": True, "first_name": "CHUD Mock Bot", "username": "chud_mock_bot"}
        chat = {"id": 424242, "first_name": "Test", "type": "private"}
        if method == "getMe":
            return self.send_json({"ok": True, "result": bot})
        if method == "getUpdates":
            return self.send_json({"ok": True, "result": [{"update_id": 1, "message": {
                "message_id": 1, "from": {"id": 424242, "is_bot": False, "first_name": "Test"},
                "chat": chat, "date": now() - 60, "text": "/start"}}]})
        if method == "sendMessage":
            if not params.get("chat_id"):
                return self.send_json({"ok": False, "error_code": 400, "description": "Bad Request: chat_id is empty"},
                                      400)
            return self.send_json({"ok": True, "result": {
                "message_id": 100 + next_count("telegram"), "from": bot,
                "chat": dict(chat, id=_int(params.get("chat_id")) or params.get("chat_id")),
                "date": now(), "text": str(params.get("text", ""))}})
        return self.send_json({"ok": True, "result": True})


def _int(value):
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0


def _buffered(pieces, size=64 * 1024):
    buf, used = [], 0
    for piece in pieces:
        buf.append(piece)
        used += len(piece)
        if used >= size:
            yield b"".join(buf)
            buf, used = [], 0
    if buf:
        yield b"".join(buf)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 64

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError)):
            return  # clients hang up on streams all the time
        super().handle_error(request, client_address)


def main():
    global FIXTURES, VERBOSE, BIG_VOD_DELAY
    parser = argparse.ArgumentParser(description="CHUD STREAMS mock server (standard library only).")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="127.0.0.1", help="address to listen on (default 127.0.0.1)")
    parser.add_argument("--big", action="store_true",
                        help="announce the provider-sized big/big account (it is always available)")
    parser.add_argument("--fixtures", default=FIXTURES, help="folder with sample.mkv, sample.mp4 and sample.ts")
    parser.add_argument("--vod-delay", type=float, default=BIG_VOD_DELAY,
                        help="seconds before the big account's full film list starts (default 12)")
    parser.add_argument("--verbose", action="store_true", help="log every request")
    args = parser.parse_args()
    FIXTURES, VERBOSE, BIG_VOD_DELAY = os.path.abspath(args.fixtures), args.verbose, args.vod_delay

    missing = [n for n in ("sample.mkv", "sample.mp4", "sample.ts") if not os.path.isfile(os.path.join(FIXTURES, n))]
    if missing:
        sys.stderr.write("warning: missing fixtures in %s: %s\n" % (FIXTURES, ", ".join(missing)))
    server = Server((args.host, args.port), Handler)
    print("CHUD STREAMS mock server on http://%s:%d (fixtures: %s)" % (args.host, args.port, FIXTURES), flush=True)
    if args.big:
        print("big/big: %d channels, %d films (first byte after %gs), %d series"
              % (BIG_LIVE, BIG_VOD, BIG_VOD_DELAY, BIG_SERIES), flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
