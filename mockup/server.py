#!/usr/bin/env python3
"""Serves the home screen mockup on http://localhost:8765/, answers Ask AI through Gemini and fetches the map tiles.

The keys live in config.properties (gemini_key=, maps_key=; git-ignored) and never reach the browser or the log: the
page only learns their last 4 characters. Only localhost can connect.
"""
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")
PORT = int(os.environ.get("PORT", "8765"))
# What the page may load: the mockup itself, the app's symbols and the font.
SERVED = ("/mockup/", "/app/assets/symbols/", "/docs/assets/fonts/")
API = "https://generativelanguage.googleapis.com/v1beta/models/"
DEFAULTS = {"gemini_model": "gemini-flash-latest", "gemini_grounding": "true", "maps_provider": "google"}
# Raster tiles at 512 px (sharp at 2x), a day and a night style each. maps_style_day / maps_style_night override them
# (MapTiler and Mapbox only).
MAPS = {
    "google": {"credit": "Google Maps"},   # Map Tiles API: a session per style, see google_session

    "maptiler": {"url": "https://api.maptiler.com/maps/{style}/256/{z}/{x}/{y}@2x.png?key={key}",
                 "day": "streets-v2", "night": "streets-v2-dark",
                 "credit": "© MapTiler © OpenStreetMap contributors"},
    "mapbox": {"url": "https://api.mapbox.com/styles/v1/mapbox/{style}/tiles/256/{z}/{x}/{y}@2x?access_token={key}",
               "day": "streets-v12", "night": "dark-v11",
               "credit": "© Mapbox © OpenStreetMap contributors"},
}
TILE = re.compile(r"^/api/tile/(\d{1,2})/(\d{1,7})/(\d{1,7})$")
GOOGLE = "https://tile.googleapis.com/"
sessions, sessions_lock = {}, threading.Lock()
WEATHER = "https://weather.googleapis.com/v1/"
# Google's Weather API: 10,000 free calls a month, 3 per refresh. One refresh an hour: about 2,200 a month.
WEATHER_TTL = 60 * 60
weather_cache = {"at": 0, "key": None, "data": None}
SYSTEM = ("You answer questions asked on a living-room TV in Etobicoke, Ontario, Canada. Be brief and readable from the "
          "couch: a one-sentence answer first, then at most 4 short bullet points. Use Celsius and metric units. "
          "Plain text with '- ' bullets only, no markdown headings, tables or bold.")


def read_config():
    values = dict(DEFAULTS)
    try:
        with open(CONFIG, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k.strip()] = v.strip()
    except FileNotFoundError:
        pass
    return values


def write_config(changes):
    """Changes the given keys in config.properties in place, keeping the rest of the file."""
    lines = []
    if os.path.exists(CONFIG):
        with open(CONFIG, encoding="utf-8") as f:
            lines = f.read().splitlines()
    left = dict(changes)
    for i, line in enumerate(lines):
        k = line.split("=", 1)[0].strip()
        if "=" in line and not line.lstrip().startswith("#") and k in left:
            lines[i] = k + "=" + left.pop(k)
    if left and not any(l.startswith("# Ask AI") for l in lines):
        lines += ["", "# Ask AI (Gemini). The key is secret: it stays in this file, which git ignores."]
    lines += [k + "=" + v for k, v in left.items()]
    with open(CONFIG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    os.chmod(CONFIG, 0o600)


def hint(key):
    return ("•" * 8 + key[-4:]) if len(key) >= 4 else ""


def maps_provider(cfg):
    name = cfg.get("maps_provider", "").strip().lower()
    return name if name in MAPS else "maptiler"


def public_settings(cfg):
    key, maps_key = cfg.get("gemini_key", ""), cfg.get("maps_key", "")
    provider = maps_provider(cfg)
    return {
        "hasKey": bool(key),
        "keyHint": hint(key),
        "model": cfg.get("gemini_model") or DEFAULTS["gemini_model"],
        "grounding": cfg.get("gemini_grounding", "true") != "false",
        "maps": {"provider": provider, "hasKey": bool(maps_key), "keyHint": hint(maps_key),
                 "credit": MAPS[provider]["credit"]},
        "postal": cfg.get("postal_code", ""),
    }


def fetch(url, body=None, headers=None):
    """GET (or POST JSON): (bytes, headers) or (None, error message). Errors never include the URL: it has the key."""
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                 headers=dict({"User-Agent": "FreeMyTV-mockup", "Content-Type": "application/json"}, **(headers or {})))
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.read(), r.headers
    except urllib.error.HTTPError as e:
        try:
            msg = json.load(e).get("error", {}).get("message") or e.reason
        except Exception:
            msg = e.reason
        return None, "%d: %s" % (e.code, msg)
    except Exception as e:
        return None, type(e).__name__


def google_session(cfg, fresh=False):
    """A Map Tiles session token for Google's default roadmap (valid about two weeks; kept until an hour before)."""
    key = cfg["maps_key"]
    with sessions_lock:
        token, expiry = sessions.get(key, (None, 0))
        if token and not fresh and time.time() < expiry - 3600:
            return token, None
        raw, info = fetch(GOOGLE + "v1/createSession?key=" + urllib.parse.quote(cfg["maps_key"]),
                          {"mapType": "roadmap", "language": "en-CA", "region": "CA", "scale": "scaleFactor2x",
                           "highDpi": True})
        if raw is None:
            return None, "Google said " + info
        s = json.loads(raw)
        sessions[key] = (s["session"], float(s.get("expiry", time.time() + 86400)))
        return s["session"], None


CA_POSTAL = re.compile(r"^[A-Za-z]\d[A-Za-z] ?\d[A-Za-z]\d$")
US_ZIP = re.compile(r"^\d{5}(-\d{4})?$")
home_cache = {"key": None, "data": None}


def place_name(components):
    """The name people use: Etobicoke rather than Toronto, Sitka, Brooklyn (the most local named area)."""
    for kind in ("sublocality_level_1", "sublocality", "locality", "postal_town", "administrative_area_level_3"):
        for c in components:
            # "Old Toronto" is Google's name for the pre-1998 city: people say Toronto (the locality, next).
            if kind in c["types"] and not c["long_name"].startswith("Old "):
                return c["long_name"]
    return ""


def time_zone(lat, lon):
    """The IANA zone for a place, from Open-Meteo (keyless, so no extra Google API to turn on)."""
    raw, _ = fetch("https://api.open-meteo.com/v1/forecast?" + urllib.parse.urlencode(
        {"latitude": lat, "longitude": lon, "timezone": "auto", "forecast_days": 1}))
    return json.loads(raw).get("timezone") if raw else None


def home(cfg):
    """
    Home: from postal_code through Google's Geocoding API when there is one (looked up once per code, and written
    back as latitude/longitude/city so build.sh gives the TV the same place), else latitude/longitude as set.
    """
    postal = cfg.get("postal_code", "").strip().upper()
    cache_key = (postal, cfg.get("latitude"), cfg.get("longitude"))
    if home_cache["key"] == cache_key:
        return home_cache["data"]
    place = {"lat": float(cfg.get("latitude") or 43.6205), "lon": float(cfg.get("longitude") or -79.5132),
             "city": cfg.get("city", ""), "country": "", "postal": postal, "from": "latitude/longitude"}
    if postal and cfg.get("maps_key"):
        country = "CA" if CA_POSTAL.match(postal) else "US" if US_ZIP.match(postal) else ""
        comps = "postal_code:" + postal + ("|country:" + country if country else "")
        raw, info = fetch("https://maps.googleapis.com/maps/api/geocode/json?" +
                          urllib.parse.urlencode({"components": comps, "key": cfg["maps_key"]}))
        d = json.loads(raw) if raw else {"status": "ERROR", "error_message": info}
        if d.get("status") == "OK":
            r = d["results"][0]
            loc = r["geometry"]["location"]
            place.update(lat=round(loc["lat"], 4), lon=round(loc["lng"], 4), city=place_name(r["address_components"]),
                         country=next((c["short_name"] for c in r["address_components"] if "country" in c["types"]), ""),
                         **{"from": "postal code"})
            write_config({"latitude": str(place["lat"]), "longitude": str(place["lon"]), "city": place["city"]})
            cache_key = (postal, str(place["lat"]), str(place["lon"]))
        else:
            place["error"] = "Google Geocoding said %s: %s" % (d.get("status"), d.get("error_message", "no match for " + postal))
            sys.stderr.write("home: %s\n" % place["error"])
    if not place["country"]:   # without a geocoded country: Canada's latitudes and longitudes, roughly, else US
        place["country"] = "CA" if place["lat"] > 41.6 and not (place["lat"] < 49 and place["lon"] < -95) and place["lon"] > -141 \
            and not (place["lon"] < -130 and place["lat"] < 60) else "US"
    place["tz"] = time_zone(place["lat"], place["lon"]) or "America/Toronto"
    home_cache.update(key=cache_key, data=place)
    return place


ROUTE_FIELDS = "routes.duration,routes.staticDuration,routes.distanceMeters,routes.description,routes.polyline.encodedPolyline"
# Places Text Search with names, addresses and locations bills as Text Search Pro: 5,000 free a month.
PLACE_FIELDS = "places.id,places.displayName,places.shortFormattedAddress,places.formattedAddress,places.location"


def search_places(cfg, query):
    """Google Maps places matching what was said or typed, nearest home first (biased within 50 km)."""
    h = home(cfg)
    body = {"textQuery": query, "pageSize": 6, "languageCode": "en",
            "locationBias": {"circle": {"center": {"latitude": h["lat"], "longitude": h["lon"]}, "radius": 50000.0}}}
    raw, info = fetch("https://places.googleapis.com/v1/places:searchText", body,
                      {"X-Goog-Api-Key": cfg["maps_key"], "X-Goog-FieldMask": PLACE_FIELDS})
    if raw is None:
        return None, "Google Places said " + info
    return [{"id": p["id"], "name": p.get("displayName", {}).get("text", ""),
             "address": p.get("shortFormattedAddress") or p.get("formattedAddress", ""),
             "lat": p["location"]["latitude"], "lon": p["location"]["longitude"]}
            for p in json.loads(raw).get("places", []) if "location" in p], None


def route_to(cfg, place_id):
    """Driving from home to a place now, with traffic (Routes Pro, 5,000 free a month): time, time without traffic,
    distance, main road and the path."""
    h = home(cfg)
    body = {"origin": {"location": {"latLng": {"latitude": h["lat"], "longitude": h["lon"]}}},
            "destination": {"placeId": place_id}, "travelMode": "DRIVE", "routingPreference": "TRAFFIC_AWARE",
            "units": "METRIC", "languageCode": "en-CA"}
    raw, info = fetch("https://routes.googleapis.com/directions/v2:computeRoutes", body,
                      {"X-Goog-Api-Key": cfg["maps_key"], "X-Goog-FieldMask": ROUTE_FIELDS})
    if raw is None:
        return None, "Google Routes said " + info
    routes = json.loads(raw).get("routes") or []
    if not routes:
        return None, "Google has no driving route from home to there."
    r = routes[0]
    secs = lambda v: int(float(str(v).rstrip("s") or 0))
    return {"duration": secs(r.get("duration", "0s")), "static": secs(r.get("staticDuration", r.get("duration", "0s"))),
            "meters": r.get("distanceMeters", 0), "via": r.get("description", ""),
            "polyline": r.get("polyline", {}).get("encodedPolyline", ""),
            "home": {k: h[k] for k in ("lat", "lon", "city")}}, None


def tile(cfg, z, x, y, night):
    """One map tile: (bytes, content type, cache header) or (None, error message, None)."""
    provider = maps_provider(cfg)
    if provider == "google":
        for fresh in (False, True):   # an expired or revoked session gets one retry with a new one
            token, err = google_session(cfg, fresh)
            if err:
                return None, err, None
            raw, info = fetch(GOOGLE + "v1/2dtiles/%s/%s/%s?session=%s&key=%s" % (z, x, y, token, urllib.parse.quote(cfg["maps_key"])))
            if raw is not None:
                # Google's terms: cache only as their headers allow, so their Cache-Control goes through untouched.
                return raw, info.get("Content-Type", "image/png"), info.get("Cache-Control", "no-store")
        return None, "Google said " + info, None
    m = MAPS[provider]
    style = cfg.get("maps_style_night" if night else "maps_style_day") or m["night" if night else "day"]
    raw, info = fetch(m["url"].format(style=style, z=z, x=x, y=y, key=urllib.parse.quote(cfg["maps_key"])))
    if raw is None:
        return None, "%s said %s" % (provider, info), None
    return raw, info.get("Content-Type", "image/png"), info.get("Cache-Control", "max-age=86400")


def attribution(cfg, q):
    """Google's copyright line for what's on screen (their terms require showing it with the tiles)."""
    token, err = google_session(cfg)
    if err:
        return None, err
    args = {k: q.get(k, [""])[0] for k in ("zoom", "north", "south", "east", "west")}
    raw, info = fetch(GOOGLE + "tile/v1/viewport?session=%s&key=%s&%s" % (token, urllib.parse.quote(cfg["maps_key"]), urllib.parse.urlencode(args)))
    if raw is None:
        return None, "Google said " + info
    return json.loads(raw).get("copyright", ""), None


def weather_symbol(kind, day):
    """Google's WeatherCondition type as one of the app's symbols (assets/symbols), like Symbols.weather."""
    if kind in ("CLEAR", "MOSTLY_CLEAR"):
        return "sunny" if day else "clear_night"
    if kind == "PARTLY_CLOUDY":
        return "partly_cloudy_day" if day else "partly_cloudy_night"
    if "THUNDER" in kind:
        return "thunderstorm"
    if "SNOW" in kind:
        return "snowflake"
    if "RAIN" in kind or "SHOWER" in kind or "HAIL" in kind:
        return "rainy"
    return "cloud"   # MOSTLY_CLOUDY, CLOUDY, WINDY


def google_weather(cfg):
    """Now, the next 6 hours, today's high/low and the sun times in the shape the page draws (local Etobicoke times)."""
    h = home(cfg)
    q = urllib.parse.urlencode({"key": cfg["maps_key"], "location.latitude": h["lat"], "location.longitude": h["lon"],
                                "unitsSystem": "METRIC"})
    urls = [WEATHER + "currentConditions:lookup?" + q,
            WEATHER + "forecast/hours:lookup?hours=7&pageSize=7&" + q,
            WEATHER + "forecast/days:lookup?days=2&pageSize=2&" + q]
    with ThreadPoolExecutor(3) as pool:   # the three at once: the card waits for the slowest, not the sum
        results = list(pool.map(fetch, urls))
    for raw, info in results:
        if raw is None:
            return None, "Google Weather said " + info
    now, hours, days = (json.loads(raw) for raw, _ in results)
    tz = ZoneInfo((days.get("timeZone") or {}).get("id") or now.get("timeZone", {}).get("id") or "America/Toronto")
    # Google's times are UTC with up to 9 decimals ("…T11:38:12.123456789Z"); minutes are all the card needs.
    local = lambda iso: (datetime.strptime(iso[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=ZoneInfo("UTC"))
                         .astimezone(tz).strftime("%Y-%m-%dT%H:%M"))
    cond = now["weatherCondition"]
    day, today = now.get("isDaytime", True), days["forecastDays"][0]
    after = [h for h in hours["forecastHours"] if local(h["interval"]["startTime"]) > local(now["currentTime"])][:6]
    return {
        "source": "Google Weather",
        "temp": now["temperature"]["degrees"],
        "symbol": weather_symbol(cond.get("type", ""), day),
        "condition": cond.get("description", {}).get("text", ""),
        "day": day,
        "max": today["maxTemperature"]["degrees"], "min": today["minTemperature"]["degrees"],
        "sunrise": local(today["sunEvents"]["sunriseTime"]), "sunset": local(today["sunEvents"]["sunsetTime"]),
        "sunriseNext": local(days["forecastDays"][1]["sunEvents"]["sunriseTime"]),
        "hours": [{"t": local(h["interval"]["startTime"]), "temp": h["temperature"]["degrees"],
                   "symbol": weather_symbol(h["weatherCondition"].get("type", ""), h.get("isDaytime", True)),
                   "pop": h.get("precipitation", {}).get("probability", {}).get("percent", 0)} for h in after],
    }, None


def gemini(path, cfg, body=None, timeout=30):
    req = urllib.request.Request(API + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"x-goog-api-key": cfg["gemini_key"], "Content-Type": "application/json"},
                                 method="GET" if body is None else "POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r), None
    except urllib.error.HTTPError as e:
        try:
            msg = json.load(e).get("error", {}).get("message") or e.reason
        except Exception:
            msg = e.reason
        return None, "Gemini said (%d): %s" % (e.code, msg)
    except Exception as e:
        return None, "Couldn't reach Gemini: %s" % type(e).__name__


def answer(data):
    """Text and sources from a generateContent response."""
    cand = (data.get("candidates") or [{}])[0]
    text = "".join(p.get("text", "") for p in (cand.get("content") or {}).get("parts", []) if not p.get("thought"))
    sources, seen = [], set()
    for ch in (cand.get("groundingMetadata") or {}).get("groundingChunks", []):
        web = ch.get("web") or {}
        if web.get("uri") and web.get("title") not in seen:
            seen.add(web.get("title"))
            sources.append({"title": web.get("title") or web["uri"], "uri": web["uri"]})
    if not text:
        reason = cand.get("finishReason") or (data.get("promptFeedback") or {}).get("blockReason") or "no answer"
        return None, "Gemini returned no text (%s)." % reason
    return {"text": text.strip(), "sources": sources[:5], "model": data.get("modelVersion", "")}, None


def ask(question, cfg):
    s = public_settings(cfg)
    body = {"systemInstruction": {"parts": [{"text": SYSTEM}]},
            "contents": [{"role": "user", "parts": [{"text": question}]}]}
    if s["grounding"]:
        body["tools"] = [{"google_search": {}}]
    data, err = gemini(s["model"] + ":generateContent", cfg, body)
    return (None, err) if err else answer(data)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, fmt, *args):
        # Paths only (no bodies, so no key and no questions in the terminal); tiles are too many to list.
        if not self.path.startswith("/api/tile/"):
            sys.stderr.write("%s %s\n" % (self.command, self.path.split("?")[0]))

    def end_headers(self):
        if not self.path.startswith(("/api/", "/app/assets/", "/docs/assets/")):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def send_json(self, obj, code=200):
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/mockup"):
            self.send_response(302)
            self.send_header("Location", "/mockup/")
            self.end_headers()
        elif path == "/api/settings":
            self.send_json(public_settings(read_config()))
        elif TILE.match(path):
            cfg = read_config()
            if not cfg.get("maps_key"):
                return self.send_json({"error": "no_maps_key"}, 404)
            z, x, y = TILE.match(path).groups()
            data, info, cache = tile(cfg, z, x, y, "night" in self.path)
            if data is None:
                sys.stderr.write("tile %s/%s/%s: %s\n" % (z, x, y, info))
                return self.send_json({"error": info}, 502)
            self.send_response(200)
            self.send_header("Content-Type", info)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", cache)
            self.end_headers()
            self.wfile.write(data)
        elif path == "/api/weather":
            cfg = read_config()
            if not cfg.get("maps_key"):
                return self.send_json({"error": "no_maps_key"})   # expected, not a failure: the page uses Open-Meteo
            c, h = weather_cache, home(cfg)
            if c["data"] is None or c["key"] != (cfg["maps_key"], h["lat"], h["lon"]) or time.time() - c["at"] > WEATHER_TTL:
                try:
                    data, err = google_weather(cfg)
                except (KeyError, IndexError, ValueError) as e:
                    data, err = None, "Google Weather sent an answer without %s" % e
                if err:
                    sys.stderr.write("weather: %s\n" % err)
                    return self.send_json({"error": err}, 502)
                c.update(at=time.time(), key=(cfg["maps_key"], h["lat"], h["lon"]), data=data)
            self.send_json(dict(c["data"], at=int(c["at"] * 1000)))
        elif path == "/api/home":
            self.send_json(home(read_config()))
        elif path in ("/api/places", "/api/route"):
            cfg = read_config()
            if not cfg.get("maps_key"):
                return self.send_json({"error": "Add a Google maps_key in config.properties."}, 400)
            q = urllib.parse.parse_qs(self.path.partition("?")[2])
            if path == "/api/places":
                text = q.get("q", [""])[0].strip()[:200]
                result, err = search_places(cfg, text) if text else (None, "Say or type a place first.")
                return self.send_json({"places": result} if err is None else {"error": err}, 200 if err is None else 502)
            result, err = route_to(cfg, q.get("place", [""])[0])
            self.send_json(result if err is None else {"error": err}, 200 if err is None else 502)
        elif path == "/api/maps/attribution":
            cfg = read_config()
            if not cfg.get("maps_key") or maps_provider(cfg) != "google":
                return self.send_json({"copyright": ""})
            q = urllib.parse.parse_qs(self.path.partition("?")[2])
            text, err = attribution(cfg, q)
            self.send_json({"copyright": text} if err is None else {"error": err}, 200 if err is None else 502)
        elif path.startswith(SERVED) and ".." not in path:
            super().do_GET()
        else:
            self.send_error(404)

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            return self.send_json({"error": "Bad request."}, 400)
        cfg = read_config()
        path = self.path.split("?")[0]
        if path == "/api/settings":
            changes = {}
            if isinstance(body.get("key"), str) and body["key"].strip():
                changes["gemini_key"] = body["key"].strip()
            if isinstance(body.get("model"), str) and body["model"].strip():
                changes["gemini_model"] = body["model"].strip()
            if isinstance(body.get("grounding"), bool):
                changes["gemini_grounding"] = "true" if body["grounding"] else "false"
            if isinstance(body.get("postal"), str):
                p = body["postal"].strip().upper().replace(" ", "")
                if not (CA_POSTAL.match(p) or US_ZIP.match(p)):
                    return self.send_json({"error": "That isn't a postal code (A1A 1A1) or ZIP code (12345)."}, 400)
                changes["postal_code"] = p[:3] + " " + p[3:] if CA_POSTAL.match(p) else p
            if changes:
                write_config(changes)
            return self.send_json(public_settings(read_config()))
        if not cfg.get("gemini_key"):
            return self.send_json({"error": "no_key"}, 400)
        if path == "/api/ask":
            q = str(body.get("q", "")).strip()[:2000]
            if not q:
                return self.send_json({"error": "Ask something first."}, 400)
            result, err = ask(q, cfg)
            return self.send_json(result if result else {"error": err}, 200 if result else 502)
        if path == "/api/test":
            model = public_settings(cfg)["model"]
            info, err = gemini(model, cfg, timeout=15)
            if err:
                return self.send_json({"ok": False, "message": err})
            result, err = ask("Reply with the single word: ready", cfg)
            if err:
                return self.send_json({"ok": False, "message": err})
            resolved = result["model"] or info.get("name", model)
            return self.send_json({"ok": True, "message": "Connected. %s answers as %s." % (model, resolved)})
        self.send_error(404)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("Mockup: http://localhost:%d/" % PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
