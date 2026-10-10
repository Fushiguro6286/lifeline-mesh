"""Tests for official warnings: python -m pytest server (or python test_warnings.py). No network."""
import os
import sqlite3
import sys
import tempfile
import time
from contextlib import closing

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["LIFELINE_DB"] = os.path.join(tempfile.mkdtemp(), "test.db")
os.environ["LIFELINE_SACHET_FEEDS"] = "off"

import official_warnings as ow  # noqa: E402

RSS = b"""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Karnataka</title>
<item><title>Flood</title><link>https://example/cap?identifier=1</link><guid isPermaLink="false">1</guid></item>
<item><title>Thunderstorm</title><link>https://example/cap?identifier=2</link><guid isPermaLink="false">2</guid></item>
</channel></rss>"""


def cap(ident, event="Flood", severity="Severe", msg_type="Alert", refs="", expires_in=3600, status="Actual"):
    now = time.time()
    iso = lambda t: time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(t))
    return f"""<cap:alert xmlns:cap="urn:oasis:names:tc:emergency:cap:1.2">
<cap:identifier>{ident}</cap:identifier><cap:sender>Karnataka-SNDMC</cap:sender><cap:sent>{iso(now)}</cap:sent>
<cap:status>{status}</cap:status><cap:msgType>{msg_type}</cap:msgType><cap:scope>Public</cap:scope>
<cap:references>{refs}</cap:references>
<cap:info><cap:language>en-IN</cap:language><cap:category>Met</cap:category><cap:event>{event}</cap:event>
<cap:urgency>Immediate</cap:urgency><cap:severity>{severity}</cap:severity><cap:certainty>Likely</cap:certainty>
<cap:expires>{iso(now + expires_in)}</cap:expires>
<cap:headline>{event} warning for Kodagu and Udupi districts. Source : IMD Bengaluru</cap:headline>
<cap:area><cap:areaDesc>2 districts of Karnataka</cap:areaDesc></cap:area></cap:info></cap:alert>""".encode()


def fresh_db():
    path = os.path.join(tempfile.mkdtemp(), "w.db")
    connect = lambda: (lambda c: (setattr(c, "row_factory", sqlite3.Row), c)[1])(sqlite3.connect(path))
    with closing(connect()) as conn, conn:
        ow.init(conn)
    return connect


def test_parse_cap_fields():
    w = ow.parse_cap(cap("IN-1_1"))
    assert w["id"] == "IN-1_1" and w["event"] == "Flood" and w["severity"] == "Severe"
    assert w["area"] == "2 districts of Karnataka" and "Kodagu" in w["headline"]
    assert w["expires"] > w["sent"]
    assert ow.parse_cap(b"<rss/>") is None


def test_poll_stores_new_items_once_and_only_severe_in_force_reach_phones():
    connect = fresh_db()
    pages = {"https://sachet.ndma.gov.in/cap_public_website/rss/rss_karnataka.xml": RSS,
             "https://example/cap?identifier=1": cap("IN-1_1"),
             "https://example/cap?identifier=2": cap("IN-2_1", event="Thunderstorm", severity="Moderate")}
    fetched = []
    fetch = lambda url: (fetched.append(url), pages[url])[1]
    assert ow.poll_once(connect, ["karnataka"], fetch) == 2
    assert ow.poll_once(connect, ["karnataka"], fetch) == 0  # items already seen: no CAP fetched again
    assert sum(1 for u in fetched if "identifier" in u) == 2
    with closing(connect()) as conn:
        assert [r["id"] for r in ow.active(conn, int(time.time()))] == ["IN-1_1"]


def test_expired_and_cancelled_warnings_are_not_sent():
    connect = fresh_db()
    now = int(time.time())
    with closing(connect()) as conn, conn:
        ow.store(conn, ow.parse_cap(cap("IN-3_1", expires_in=-60)), "karnataka", now)
        ow.store(conn, ow.parse_cap(cap("IN-4_1")), "karnataka", now)
        assert [r["id"] for r in ow.active(conn, now)] == ["IN-4_1"]
        ow.store(conn, ow.parse_cap(cap("IN-4_2", msg_type="Cancel", refs="Karnataka-SNDMC,IN-4_1,2026-10-10T10:00:00+05:30")), "karnataka", now)
        assert ow.active(conn, now) == []


def test_feed_carries_warnings_and_drills_only_from_the_laptop():
    import main
    from fastapi.testclient import TestClient
    c = TestClient(main.app)
    now = int(time.time())
    with closing(main.db()) as conn, conn:
        ow.store(conn, ow.parse_cap(cap("IN-9_1", event="Tsunami", severity="Extreme")), "india", now)
    f = c.get("/api/feed", params={"origin": "0000abcd", "role": 1}).json()
    tsunami = [w for w in f["warnings"] if w["id"] == "IN-9_1"]
    assert tsunami and tsunami[0]["event"] == "Tsunami" and tsunami[0]["drill"] is False and tsunami[0]["expires"] > now
    # The test client is not 127.0.0.1: refused.
    assert c.post("/api/warnings/drill", content="event=Flood").status_code == 403


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn(); print("ok", name)
