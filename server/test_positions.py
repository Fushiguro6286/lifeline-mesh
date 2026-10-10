"""Tests for the position relay (10 Oct 2026): python -m pytest server. No network."""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("LIFELINE_DB", os.path.join(tempfile.mkdtemp(), "test.db"))
os.environ["LIFELINE_SACHET_FEEDS"] = "off"

import main  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

client = TestClient(main.app)
SENDER, OTHER, TEAM = "0000a001", "0000a002", "0000b001"


def alert(origin, mid):
    return {"id": mid, "type": "alert", "origin": origin, "hops": 0, "sentAt": 1, "receivedAt": 1, "lat": 12.97,
            "lon": 77.59, "accuracyM": 30, "needs": ["trapped"], "severity": 3, "people": 2, "uploadedBy": origin}


def position(origin, acc=8, lat=12.9701):
    return {"origin": origin, "lat": lat, "lon": 77.5901, "accuracyM": acc, "raw": "cG9zaXRpb24="}


def feed(role=5, lat=12.97, lon=77.59, since=0):
    return client.get("/api/feed", params={"origin": TEAM, "role": role, "lat": lat, "lon": lon, "since": since}).json()


def test_team_gets_the_latest_position_of_a_phone_with_an_alert():
    assert client.post("/api/packets", json=[alert(SENDER, "00000000000000a1")]).status_code == 200
    assert client.post("/api/positions", json=position(SENDER, acc=40)).status_code == 200
    assert client.post("/api/positions", json=position(SENDER, acc=8)).status_code == 200
    got = [p for p in feed()["positions"] if p["id"] == SENDER]
    assert len(got) == 1  # only the newest is kept
    with main.closing(main.db()) as conn:
        assert conn.execute("SELECT accuracyM FROM positions WHERE origin = ?", (SENDER,)).fetchone()[0] == 8


def test_no_alert_far_away_or_not_a_team_gets_nothing():
    client.post("/api/positions", json=position(OTHER))  # never sent an alert
    assert all(p["id"] != OTHER for p in feed()["positions"])
    assert feed(lat=13.5, lon=78.5)["positions"] == []  # a team about 120 km away
    assert feed(role=1)["positions"] == []  # people in need never receive others' positions


def test_already_seen_positions_are_not_sent_again():
    client.post("/api/packets", json=[alert(SENDER, "00000000000000a3")])
    client.post("/api/positions", json=position(SENDER))
    first = feed()
    assert any(p["id"] == SENDER for p in first["positions"])
    assert all(p["id"] != SENDER for p in feed(since=first["now"])["positions"])


def test_bad_input_is_refused():
    assert client.post("/api/positions", json={**position(SENDER), "origin": "nothex!!"}).status_code == 422
    assert client.post("/api/positions", json={**position(SENDER), "lat": 95}).status_code == 422
