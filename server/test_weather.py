"""Tests for live weather and the forecast danger check: python -m pytest server. No network."""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("LIFELINE_DB", os.path.join(tempfile.mkdtemp(), "test.db"))
os.environ["LIFELINE_SACHET_FEEDS"] = "off"

import weather  # noqa: E402


def answer(rain=0.0, gust=30.0, temp=30.0, code=2, hourly_code=2):
    return {
        "current": {"time": "2026-10-10T13:15", "temperature_2m": temp, "apparent_temperature": temp + 1, "relative_humidity_2m": 60,
                    "precipitation": 0.0, "weather_code": code, "wind_speed_10m": 10.0, "wind_gusts_10m": gust},
        "hourly": {"time": [f"t{i}" for i in range(24)], "precipitation": [rain / 24] * 24, "precipitation_probability": [40] * 24,
                   "wind_gusts_10m": [gust] * 24, "temperature_2m": [temp] * 24, "weather_code": [hourly_code] * 24},
    }


def test_summary_numbers():
    s = weather.summarize(answer(rain=12.0), now=100)
    assert s["condition"] == "Partly cloudy" and s["temperatureC"] == 30.0 and s["rain24hMm"] == 12.0
    assert s["gustMax24hKmh"] == 30.0 and s["rainChanceMax"] == 40 and s["hail24h"] is False and s["source"] == "Open-Meteo"


def test_calm_weather_has_no_warnings():
    assert weather.dangers(weather.summarize(answer(rain=60.0, gust=70.0, temp=40.0)), (12.9, 77.6), now=1_791_600_000) == []


def test_each_danger_and_its_level():
    c = (12.9, 77.6)
    rain = weather.dangers(weather.summarize(answer(rain=120.0)), c, now=1_791_600_000)
    assert [(w["event"], w["severity"], w["forecast"]) for w in rain] == [("Very heavy rain", "Severe", True)]
    assert "120 mm" in rain[0]["headline"] and rain[0]["expires"] == 1_791_600_000 + 6 * 3600
    assert weather.dangers(weather.summarize(answer(rain=210.0)), c)[0]["severity"] == "Extreme"
    assert weather.dangers(weather.summarize(answer(gust=95.0)), c)[0]["event"] == "Strong winds"
    assert weather.dangers(weather.summarize(answer(gust=120.0)), c)[0]["severity"] == "Extreme"
    assert weather.dangers(weather.summarize(answer(hourly_code=96)), c)[0]["event"] == "Thunderstorm with hail"
    assert weather.dangers(weather.summarize(answer(temp=46.0)), c)[0]["event"] == "Heat wave"


def test_same_cell_same_day_same_id_and_cache():
    a = weather.dangers(weather.summarize(answer(rain=150.0)), (12.9, 77.6), now=1_791_600_000)[0]["id"]
    b = weather.dangers(weather.summarize(answer(rain=160.0)), (12.9, 77.6), now=1_791_600_000 + 3600)[0]["id"]
    assert a == b  # one warning per cell, kind and day: phones and the mesh see one packet
    calls = []
    fetch = lambda lat, lon: (calls.append((lat, lon)), answer())[1]
    weather._cache.clear()
    weather.for_position(12.9716, 77.5946, fetch)
    weather.for_position(12.9690, 77.6010, fetch)  # same 0.1-degree cell: cached
    assert calls == [(13.0, 77.6)]


def test_feed_returns_weather_and_forecast_warnings():
    import main
    from fastapi.testclient import TestClient
    weather._cache.clear()
    weather._fetch = lambda lat, lon: answer(rain=130.0)
    f = TestClient(main.app).get("/api/feed", params={"origin": "0000abcd", "role": 1, "wlat": 12.97, "wlon": 77.59}).json()
    assert f["weather"]["rain24hMm"] == 130.0
    assert any(w.get("forecast") and w["event"] == "Very heavy rain" for w in f["warnings"])
    # Without a position: no weather, nothing breaks.
    assert "weather" not in TestClient(main.app).get("/api/feed", params={"origin": "0000abcd"}).json()


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn(); print("ok", name)
