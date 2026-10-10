"""Live weather and a forecast danger check for the SOS screen (added 10 Oct 2026).

Phones that poll GET /api/feed send a coarse position (rounded to 0.1 degree, about 11 km). For each such cell the
server asks Open-Meteo (free, no key, non-commercial use) for current conditions and the next 24 hours, at most every
10 minutes per cell, and returns a short summary. A fixed, deterministic check turns dangerous forecasts into warnings
(sent to phones like official ones, but flagged "forecast": phones say it is a forecast check, not an official warning):

  very heavy rain      24 h rain >= 115.6 mm (IMD "very heavy"); >= 204.5 mm "extremely heavy" -> Extreme
  strong winds         gusts >= 89 km/h (IMD severe cyclonic storm winds); >= 118 km/h -> Extreme
  thunderstorm + hail  WMO weather code 96 or 99 in the next 24 h
  heat wave            temperature >= 45 C (IMD heat wave); >= 47 C -> Extreme

No model is involved in any decision here.
"""

import json
import time
import urllib.request
from datetime import datetime, timezone
from typing import Callable, Optional

URL = ("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
       "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m"
       "&hourly=precipitation,precipitation_probability,wind_gusts_10m,temperature_2m,weather_code"
       "&forecast_hours=24&timezone=auto")
CACHE_S = 600
USER_AGENT = "LifelineMesh-sync/0.1 (hackathon demo server)"

# WMO weather interpretation codes (Open-Meteo docs).
CONDITIONS = {
    0: "Clear sky", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Fog",
    51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 56: "Freezing drizzle", 57: "Freezing drizzle",
    61: "Light rain", 63: "Rain", 65: "Heavy rain", 66: "Freezing rain", 67: "Freezing rain",
    71: "Light snow", 73: "Snow", 75: "Heavy snow", 77: "Snow grains", 80: "Light showers", 81: "Showers",
    82: "Violent showers", 85: "Snow showers", 86: "Heavy snow showers", 95: "Thunderstorm",
    96: "Thunderstorm with hail", 99: "Thunderstorm with heavy hail",
}

_cache: dict[tuple[float, float], tuple[float, dict]] = {}


def cell(lat: float, lon: float) -> tuple[float, float]:
    return round(lat, 1), round(lon, 1)


def _fetch(lat: float, lon: float) -> dict:
    req = urllib.request.Request(URL.format(lat=lat, lon=lon), headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read().decode("utf-8"))


def summarize(data: dict, now: Optional[int] = None) -> dict:
    """The numbers the phone shows, from one Open-Meteo answer."""
    cur = data.get("current") or {}
    hourly = data.get("hourly") or {}
    vals = lambda name: [v for v in (hourly.get(name) or []) if v is not None]
    code = int(cur.get("weather_code") or 0)
    codes24 = [int(c) for c in vals("weather_code")]
    return {
        "temperatureC": cur.get("temperature_2m"),
        "feelsLikeC": cur.get("apparent_temperature"),
        "humidity": cur.get("relative_humidity_2m"),
        "rainNowMm": cur.get("precipitation"),
        "windKmh": cur.get("wind_speed_10m"),
        "gustKmh": cur.get("wind_gusts_10m"),
        "code": code,
        "condition": CONDITIONS.get(code, "Unknown"),
        "rain24hMm": round(sum(vals("precipitation")), 1),
        "rainChanceMax": max(vals("precipitation_probability"), default=None),
        "gustMax24hKmh": max(vals("wind_gusts_10m"), default=None),
        "tempMax24hC": max(vals("temperature_2m"), default=None),
        "hail24h": any(c in (96, 99) for c in codes24 + [code]),
        "observedAt": cur.get("time"),
        "fetchedAt": now or int(time.time()),
        "source": "Open-Meteo",
    }


def dangers(s: dict, c: tuple[float, float], now: Optional[int] = None) -> list[dict]:
    """Forecast warnings for one cell, in the same shape as official ones (flag "forecast")."""
    now = now or int(time.time())
    day = datetime.fromtimestamp(now, timezone.utc).strftime("%Y%m%d")
    out = []

    def add(kind: str, event: str, extreme: bool, headline: str) -> None:
        out.append({"id": f"FORECAST-{c[0]}_{c[1]}-{kind}-{day}", "event": event, "severity": "Extreme" if extreme else "Severe",
                    "headline": headline, "area": f"around {c[0]}, {c[1]}", "sent": now, "expires": now + 6 * 3600,
                    "drill": False, "forecast": True})

    rain = s.get("rain24hMm") or 0
    if rain >= 115.6:
        add("rain", "Very heavy rain", rain >= 204.5,
            f"Forecast: {rain:g} mm of rain in the next 24 hours (very heavy rain). Flooding is possible.")
    gust = s.get("gustMax24hKmh") or 0
    if gust >= 89:
        add("wind", "Strong winds", gust >= 118, f"Forecast: wind gusts up to {gust:g} km/h in the next 24 hours.")
    if s.get("hail24h"):
        add("hail", "Thunderstorm with hail", False, "Forecast: thunderstorm with hail in the next 24 hours.")
    temp = s.get("tempMax24hC") or 0
    if temp >= 45:
        add("heat", "Heat wave", temp >= 47, f"Forecast: temperatures up to {temp:g} °C in the next 24 hours (heat wave).")
    return out


def for_position(lat: float, lon: float, fetch: Optional[Callable[[float, float], dict]] = None) -> tuple[Optional[dict], list[dict]]:
    """(summary, forecast warnings) for the cell around a position; cached per cell. Raises on network errors."""
    fetch = fetch or _fetch
    c = cell(lat, lon)
    hit = _cache.get(c)
    now = time.time()
    if hit is None or now - hit[0] > CACHE_S:
        s = summarize(fetch(*c), int(now))
        _cache[c] = (now, s)
    else:
        s = hit[1]
    return s, dangers(s, c)
