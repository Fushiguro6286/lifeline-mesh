"""Official disaster warnings (added 10 Oct 2026).

The server polls NDMA SACHET's public CAP RSS feeds (India's Common Alerting Protocol system; the feeds say "public
domain"). Each RSS item links to one CAP 1.2 message. Severe and Extreme warnings that are in force go out in
GET /api/feed; every online phone turns them into a Warning packet and passes it into the mesh, so phones with no
internet get them too.

Feeds are per state, not per district, so a phone gets every warning of the configured states and shows the official
headline, which names the districts. The per-warning polygon file is not used: it answers scripts with 403.

Settings (environment):
  LIFELINE_SACHET_FEEDS   comma-separated feed names, default "karnataka" (also "india", "kerala", ...); "off" = no polling
  LIFELINE_SACHET_EVERY   seconds between polls, default 300
"""

import os
import sqlite3
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET
from contextlib import closing
from datetime import datetime
from typing import Callable, Optional

FEED_URL = "https://sachet.ndma.gov.in/cap_public_website/rss/rss_{}.xml"
USER_AGENT = "LifelineMesh-sync/0.1 (hackathon demo server)"
CAP = "{urn:oasis:names:tc:emergency:cap:1.2}"
LEVEL = {"Minor": 0, "Moderate": 1, "Severe": 2, "Extreme": 3}
# Phones are alarmed only for warnings the issuing agency itself rated at least this severe.
MIN_LEVEL = 2
# A warning without an expiry time is treated as in force for this long after it was sent.
NO_EXPIRY_S = 24 * 3600
MAX_NEW_PER_POLL = 40

status = {"lastPollAt": None, "lastError": None, "lastNew": 0, "feeds": []}


def init(conn: sqlite3.Connection) -> None:
    conn.execute(
        """CREATE TABLE IF NOT EXISTS warnings (
            id TEXT PRIMARY KEY, feed TEXT, sender TEXT, sent INTEGER, status TEXT, msgType TEXT, refs TEXT,
            event TEXT, severity TEXT, urgency TEXT, certainty TEXT, headline TEXT, area TEXT, expires INTEGER,
            drill INTEGER NOT NULL DEFAULT 0, cancelled INTEGER NOT NULL DEFAULT 0, storedAt INTEGER NOT NULL)"""
    )
    conn.execute("CREATE TABLE IF NOT EXISTS warning_items (guid TEXT PRIMARY KEY, seenAt INTEGER NOT NULL)")


def _fetch(url: str, timeout: float = 20) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def parse_rss(data: bytes) -> list[tuple[str, str]]:
    """(guid, link) of every item."""
    out = []
    for item in ET.fromstring(data).iter("item"):
        guid = (item.findtext("guid") or "").strip()
        link = (item.findtext("link") or "").strip()
        if guid and link:
            out.append((guid, link))
    return out


def _epoch(s: Optional[str]) -> Optional[int]:
    if not s:
        return None
    try:
        return int(datetime.fromisoformat(s.strip()).timestamp())
    except ValueError:
        return None


def parse_cap(data: bytes) -> Optional[dict]:
    """The fields we use from one CAP 1.2 alert (first info block), or None if it is not CAP."""
    root = ET.fromstring(data)
    if root.tag != CAP + "alert":
        return None
    t = lambda el, name: (el.findtext(CAP + name) or "").strip()
    info = root.find(CAP + "info")
    if info is None:
        return None
    area = info.find(CAP + "area")
    return {
        "id": t(root, "identifier"),
        "sender": t(root, "sender"),
        "sent": _epoch(t(root, "sent")),
        "status": t(root, "status"),
        "msgType": t(root, "msgType"),
        "refs": t(root, "references"),
        "event": t(info, "event"),
        "severity": t(info, "severity"),
        "urgency": t(info, "urgency"),
        "certainty": t(info, "certainty"),
        "headline": t(info, "headline") or t(info, "description"),
        "area": t(area, "areaDesc") if area is not None else "",
        "expires": _epoch(t(info, "expires")),
    }


def referenced_ids(refs: str) -> list[str]:
    """CAP references are space-separated "sender,identifier,sent" triples."""
    return [p.split(",")[1] for p in refs.split() if p.count(",") >= 2]


def store(conn: sqlite3.Connection, w: dict, feed: str, now: int, drill: bool = False) -> bool:
    cur = conn.execute(
        """INSERT OR IGNORE INTO warnings (id, feed, sender, sent, status, msgType, refs, event, severity, urgency,
           certainty, headline, area, expires, drill, storedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (w["id"], feed, w["sender"], w["sent"], w["status"], w["msgType"], w["refs"], w["event"], w["severity"],
         w["urgency"], w["certainty"], w["headline"], w["area"], w["expires"], 1 if drill else 0, now),
    )
    if w["msgType"] == "Cancel":
        for ref in referenced_ids(w["refs"]):
            conn.execute("UPDATE warnings SET cancelled = 1 WHERE id = ?", (ref,))
    return cur.rowcount == 1


def active(conn: sqlite3.Connection, now: int) -> list[sqlite3.Row]:
    """Severe or Extreme, actual (or a drill), not cancelled, still in force: what phones are told about."""
    rows = conn.execute(
        "SELECT * FROM warnings WHERE status = 'Actual' AND msgType != 'Cancel' AND cancelled = 0 ORDER BY sent DESC"
    ).fetchall()
    out = []
    for r in rows:
        if LEVEL.get(r["severity"], 0) < MIN_LEVEL:
            continue
        until = r["expires"] or ((r["sent"] or 0) + NO_EXPIRY_S)
        if until > now:
            out.append(r)
    return out


def poll_once(connect: Callable[[], sqlite3.Connection], feeds: list[str], fetch=_fetch) -> int:
    """Reads each feed and fetches the CAP messages not seen before. Returns how many new warnings were stored."""
    new = 0
    now = int(time.time())
    for feed in feeds:
        items = parse_rss(fetch(FEED_URL.format(feed)))
        with closing(connect()) as conn, conn:
            seen = {r[0] for r in conn.execute("SELECT guid FROM warning_items")}
        todo = [(g, link) for g, link in items if g not in seen][:MAX_NEW_PER_POLL]
        for guid, link in todo:
            try:
                w = parse_cap(fetch(link))
            except (OSError, ET.ParseError):
                continue  # try this item again next time
            with closing(connect()) as conn, conn:
                if w and w["id"] and store(conn, w, feed, now):
                    new += 1
                conn.execute("INSERT OR IGNORE INTO warning_items VALUES (?, ?)", (guid, now))
    return new


def start_polling(connect: Callable[[], sqlite3.Connection]) -> None:
    feeds = [f.strip().lower() for f in os.environ.get("LIFELINE_SACHET_FEEDS", "karnataka").split(",") if f.strip()]
    if feeds == ["off"]:
        return
    every = max(60, int(os.environ.get("LIFELINE_SACHET_EVERY", "300")))
    status["feeds"] = feeds

    def loop() -> None:
        while True:
            try:
                status["lastNew"] = poll_once(connect, feeds)
                status["lastError"] = None
            except Exception as e:  # network down, feed changed: keep serving, show it on the dashboard
                status["lastError"] = f"{type(e).__name__}: {e}"
            status["lastPollAt"] = int(time.time())
            time.sleep(every)

    threading.Thread(target=loop, name="sachet-poll", daemon=True).start()
