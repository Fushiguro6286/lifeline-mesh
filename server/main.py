"""Lifeline Mesh sync server (CLAUDE.md 7.6, SETUP.md 9).

Phones that find internet POST the alerts, check-ins, reports and acks they hold. Uploads are idempotent by message
ID: the same packet arriving from three phones is stored once (and we count how many phones sent it).

Internet relay (Phase 10): every online phone polls GET /api/feed. A team phone's poll records where the team is;
each new alert is assigned to the nearest online team of the matching type (ambulance request or medical → ambulance,
fire → fire service, trapped → NDRF, road blocked → police; otherwise any team), and team phones receive the alerts
near them with the raw packet bytes, which they inject into their own mesh. Every phone receives replies to its own
alerts. Distances are straight-line (haversine), like on the phones.

Official warnings (official_warnings.py): the server polls NDMA SACHET's public CAP feeds; Severe and Extreme warnings
in force go to every phone in GET /api/feed, and phones pass them into the mesh. A drill warning (clearly labelled) can
be sent from the dashboard on this laptop for rehearsals.

Run:  uvicorn main:app --host 0.0.0.0 --port 8000
"""

import html
import math
import os
import sqlite3
import time
from contextlib import asynccontextmanager, closing
from typing import Literal, Optional
from urllib.parse import parse_qs

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, Field

import official_warnings as ow

DB_PATH = os.environ.get("LIFELINE_DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "lifeline.db"))

HEX16 = r"^[0-9a-f]{16}$"
HEX8 = r"^[0-9a-f]{8}$"


class PacketIn(BaseModel):
    id: str = Field(pattern=HEX16)
    type: Literal["alert", "check_in", "ack", "report"]
    origin: str = Field(pattern=HEX8)
    hops: int = Field(ge=0, le=255)
    sentAt: int  # ms, sender clock
    receivedAt: int  # ms, uploading phone's clock
    lat: Optional[float] = Field(default=None, ge=-90, le=90)
    lon: Optional[float] = Field(default=None, ge=-180, le=180)
    accuracyM: Optional[int] = None
    needs: list[str] = []
    severity: Optional[int] = Field(default=None, ge=0, le=3)
    people: Optional[int] = Field(default=None, ge=0, le=255)
    note: Optional[str] = Field(default=None, max_length=200)
    refId: Optional[str] = Field(default=None, pattern=HEX16)
    etaMinutes: Optional[int] = Field(default=None, ge=0, le=255)
    reportType: Optional[str] = None
    name: Optional[str] = Field(default=None, max_length=64)
    uploadedBy: str = Field(pattern=HEX8)
    # Phase 10: the packet exactly as sent (base64), so a team phone can inject it into its own mesh; and the
    # uploader's phone number, only on its own packets.
    raw: Optional[str] = Field(default=None, max_length=1500)
    contactPhone: Optional[str] = Field(default=None, max_length=16)


COLUMNS = [
    "id", "type", "origin", "hops", "sentAt", "receivedAt", "lat", "lon", "accuracyM", "needs", "severity",
    "people", "note", "refId", "etaMinutes", "reportType", "name", "uploadedBy", "raw", "contactPhone",
]

TEAM_NAMES = {3: "Rescue team", 4: "Fire service", 5: "Ambulance", 6: "Police", 7: "NDRF / SDRF"}
ONLINE_MS = 2 * 60_000      # a team is online if it polled in the last 2 minutes
ASSIGN_RADIUS_M = 50_000    # never assign an alert to a team further than this
FEED_RADIUS_M = 25_000      # team phones receive alerts within this distance (and alerts with no position)
FEED_WINDOW_MS = 6 * 3_600_000


def team_for(needs: list[str]) -> Optional[int]:
    """Same order as Teams.teamFor on the phone."""
    if "ambulance" in needs: return 5
    if "fire" in needs: return 4
    if "medical" in needs: return 5
    if "trapped" in needs: return 7
    if "road_blocked" in needs: return 6
    return None


def distance_m(lat1, lon1, lat2, lon2) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init() -> None:
    with closing(db()) as conn, conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS packets (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, origin TEXT NOT NULL, hops INTEGER, sentAt INTEGER,
                receivedAt INTEGER, lat REAL, lon REAL, accuracyM INTEGER, needs TEXT, severity INTEGER, people INTEGER,
                note TEXT, refId TEXT, etaMinutes INTEGER, reportType TEXT, name TEXT, uploadedBy TEXT,
                storedAt INTEGER NOT NULL, copies INTEGER NOT NULL DEFAULT 1)"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS packets_ref ON packets(refId)")
        have = {r[1] for r in conn.execute("PRAGMA table_info(packets)")}
        for col in ("raw", "contactPhone"):
            if col not in have:
                conn.execute(f"ALTER TABLE packets ADD COLUMN {col} TEXT")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS teams (
                origin TEXT PRIMARY KEY, role INTEGER NOT NULL, name TEXT, lat REAL, lon REAL, seenAt INTEGER NOT NULL)"""
        )
        conn.execute(
            """CREATE TABLE IF NOT EXISTS assignments (
                packetId TEXT PRIMARY KEY, teamOrigin TEXT NOT NULL, distanceM REAL, assignedAt INTEGER NOT NULL)"""
        )
        ow.init(conn)


def assign_open_alerts(conn: sqlite3.Connection, now: int) -> None:
    """Every recent alert without a team goes to the nearest online team of its type (else any team), if one is near."""
    teams = conn.execute("SELECT * FROM teams WHERE seenAt >= ? AND lat IS NOT NULL", (now - ONLINE_MS,)).fetchall()
    if not teams:
        return
    rows = conn.execute(
        """SELECT p.* FROM packets p LEFT JOIN assignments a ON a.packetId = p.id
           WHERE p.type = 'alert' AND a.packetId IS NULL AND p.storedAt >= ? AND p.lat IS NOT NULL""",
        (now - FEED_WINDOW_MS,),
    ).fetchall()
    for r in rows:
        want = team_for([n for n in (r["needs"] or "").split(",") if n])
        others = [t for t in teams if t["origin"] != r["origin"]]  # never send a team its own alert
        if not others:
            continue
        options = [t for t in others if want is None or t["role"] == want] or others
        best = min(options, key=lambda t: distance_m(r["lat"], r["lon"], t["lat"], t["lon"]))
        d = distance_m(r["lat"], r["lon"], best["lat"], best["lon"])
        if d <= ASSIGN_RADIUS_M:
            conn.execute("INSERT OR IGNORE INTO assignments VALUES (?, ?, ?, ?)", (r["id"], best["origin"], d, now))


init()


@asynccontextmanager
async def lifespan(_: FastAPI):
    ow.start_polling(db)
    yield


app = FastAPI(title="Lifeline Mesh sync", lifespan=lifespan)


def warning_json(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "event": r["event"], "severity": r["severity"], "headline": r["headline"], "area": r["area"],
            "sent": r["sent"], "expires": r["expires"] or (r["sent"] or 0) + ow.NO_EXPIRY_S, "drill": bool(r["drill"])}


@app.post("/api/packets")
def post_packets(packets: list[PacketIn]) -> dict:
    stored = duplicates = 0
    now = int(time.time() * 1000)
    with closing(db()) as conn, conn:
        for p in packets:
            row = p.model_dump()
            row["needs"] = ",".join(p.needs)
            cur = conn.execute(
                f"INSERT OR IGNORE INTO packets ({','.join(COLUMNS)}, storedAt) VALUES ({','.join('?' * len(COLUMNS))}, ?)",
                [row[c] for c in COLUMNS] + [now],
            )
            if cur.rowcount == 1:
                stored += 1
            else:
                duplicates += 1
                conn.execute("UPDATE packets SET copies = copies + 1 WHERE id = ?", (p.id,))
                if p.raw:  # an older upload without raw bytes: fill them in
                    conn.execute("UPDATE packets SET raw = COALESCE(raw, ?) WHERE id = ?", (p.raw, p.id))
        assign_open_alerts(conn, now)
    return {"stored": stored, "duplicates": duplicates}


@app.get("/api/feed")
def feed(origin: str, role: int = 1, lat: Optional[float] = None, lon: Optional[float] = None, name: str = "",
         since: int = 0) -> dict:
    """What an online phone should inject into its mesh: replies to its own alerts; for teams, nearby alerts."""
    now = int(time.time() * 1000)
    out = []
    with closing(db()) as conn, conn:
        if role >= 3:
            conn.execute(
                "INSERT INTO teams VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(origin) DO UPDATE SET "
                "role = excluded.role, name = excluded.name, lat = COALESCE(excluded.lat, teams.lat), "
                "lon = COALESCE(excluded.lon, teams.lon), seenAt = excluded.seenAt",
                (origin, role, name[:32], lat, lon, now),
            )
            assign_open_alerts(conn, now)
            assigned = {r[0] for r in conn.execute("SELECT packetId FROM assignments WHERE teamOrigin = ?", (origin,))}
            for r in conn.execute(
                "SELECT id, raw, lat, lon FROM packets WHERE type = 'alert' AND raw IS NOT NULL AND storedAt > ? AND storedAt >= ? AND origin != ?",
                (since, now - FEED_WINDOW_MS, origin),
            ):
                near = r["lat"] is None or lat is None or lon is None or distance_m(lat, lon, r["lat"], r["lon"]) <= FEED_RADIUS_M
                if near or r["id"] in assigned:
                    out.append({"id": r["id"], "raw": r["raw"], "assigned": r["id"] in assigned})
        for r in conn.execute(
            """SELECT k.id, k.raw FROM packets k JOIN packets a ON a.id = k.refId
               WHERE k.type = 'ack' AND k.raw IS NOT NULL AND a.origin = ? AND k.storedAt > ?""",
            (origin, since),
        ):
            out.append({"id": r["id"], "raw": r["raw"], "assigned": False})
        warnings = [warning_json(w) for w in ow.active(conn, now // 1000)]
    return {"now": now, "packets": out, "warnings": warnings}


@app.post("/api/warnings/drill")
async def drill(request: Request):
    """A rehearsal warning, labelled DRILL on every phone. Only from this laptop (the dashboard form)."""
    if request.client is None or request.client.host not in ("127.0.0.1", "::1"):
        raise HTTPException(403, "Drills can only be sent from the server laptop.")
    form = {k: v[0] for k, v in parse_qs((await request.body()).decode("utf-8")).items()}
    now = int(time.time())
    minutes = max(1, min(int(form.get("minutes", "30") or 30), 24 * 60))
    w = {"id": f"DRILL-{now}", "sender": "Lifeline Mesh drill", "sent": now, "status": "Actual", "msgType": "Alert",
         "refs": "", "event": (form.get("event") or "Flood")[:40], "severity": "Severe", "urgency": "Immediate",
         "certainty": "Likely", "headline": (form.get("headline") or "Drill: flood warning for this area.")[:300],
         "area": "Drill", "expires": now + minutes * 60}
    with closing(db()) as conn, conn:
        ow.store(conn, w, "drill", now, drill=True)
    return RedirectResponse("/", status_code=303)


@app.get("/api/packets")
def get_packets(limit: int = 500) -> list[dict]:
    with closing(db()) as conn:
        rows = conn.execute("SELECT * FROM packets ORDER BY storedAt DESC LIMIT ?", (max(1, min(limit, 5000)),)).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        d["needs"] = [n for n in (d["needs"] or "").split(",") if n]
        out.append(d)
    return out


SEVERITY = {0: "info", 1: "low", 2: "serious", 3: "urgent"}
REPORT = {"road_blocked": "Road blocked", "water_available": "Water available", "shelter_open": "Shelter open",
          "shelter_full": "Shelter full"}


def _t(ms: Optional[int]) -> str:
    return time.strftime("%H:%M:%S", time.localtime(ms / 1000)) if ms else "—"


def _place(r: sqlite3.Row) -> str:
    if r["lat"] is None or r["lon"] is None:
        return "unknown"
    acc = f" ±{r['accuracyM']} m" if r["accuracyM"] is not None else ""
    return f"{r['lat']:.5f}, {r['lon']:.5f}{acc}"


def _e(v) -> str:
    return html.escape("" if v is None else str(v))


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    """One page for judges: alerts (urgent first) with reply status and assigned team, teams online, check-ins, reports."""
    now_ms = int(time.time() * 1000)
    with closing(db()) as conn:
        alerts = conn.execute("SELECT * FROM packets WHERE type = 'alert' ORDER BY severity DESC, sentAt DESC").fetchall()
        acks = {}
        for a in conn.execute("SELECT refId, etaMinutes, sentAt FROM packets WHERE type = 'ack' ORDER BY sentAt"):
            acks[a["refId"]] = a
        checkins = conn.execute("SELECT * FROM packets WHERE type = 'check_in' ORDER BY sentAt DESC").fetchall()
        reports = conn.execute("SELECT * FROM packets WHERE type = 'report' ORDER BY sentAt DESC").fetchall()
        total = conn.execute("SELECT COUNT(*), COUNT(DISTINCT uploadedBy) FROM packets").fetchone()
        assigned = {r["packetId"]: r for r in conn.execute(
            "SELECT a.packetId, a.distanceM, t.role, t.name, t.origin FROM assignments a JOIN teams t ON t.origin = a.teamOrigin")}
        teams = conn.execute("SELECT * FROM teams ORDER BY seenAt DESC").fetchall()
        in_force = ow.active(conn, now_ms // 1000)
        recent = conn.execute("SELECT * FROM warnings ORDER BY storedAt DESC, sent DESC LIMIT 8").fetchall()

    def alert_row(r: sqlite3.Row) -> str:
        ack = acks.get(r["id"])
        status = f"Help is coming · ETA {ack['etaMinutes']} min" if ack else "Open"
        a = assigned.get(r["id"])
        if a:
            status += f" · sent to {TEAM_NAMES.get(a['role'], 'team')} {_e(a['name'] or a['origin'][-4:])} ({a['distanceM'] / 1000:.1f} km)"
        needs = ", ".join(n.replace("_", " ") for n in (r["needs"] or "").split(",") if n) or "—"
        sev = SEVERITY.get(r["severity"], "?")
        return (f"<tr class='sev{r['severity']}'><td>{_t(r['sentAt'])}</td><td>{_e(needs)}</td><td>{_e(r['people'])}</td>"
                f"<td>{sev}</td><td>{_e(r['note'])}</td><td>{_place(r)}</td><td>{r['hops']}</td><td>{r['copies']}</td>"
                f"<td class='{'ok' if ack else ''}'>{_e(status)}</td></tr>")

    def simple(rows, cols) -> str:
        if not rows:
            return "<p class='muted'>None yet.</p>"
        head = "".join(f"<th>{c}</th>" for c, _ in cols)
        body = "".join("<tr>" + "".join(f"<td>{f(r)}</td>" for _, f in cols) + "</tr>" for r in rows)
        return f"<table><tr>{head}</tr>{body}</table>"

    alert_table = ("<p class='muted'>No alerts uploaded yet.</p>" if not alerts else
                   "<table><tr><th>Sent</th><th>Needs</th><th>People</th><th>Severity</th><th>Note</th><th>Place</th>"
                   "<th>Hops</th><th>Phones that uploaded it</th><th>Status</th></tr>"
                   + "".join(alert_row(r) for r in alerts) + "</table>")
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="refresh" content="5">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Lifeline Mesh sync</title>
<style>
body{{background:#0f1417;color:#e8edf0;font:15px/1.45 system-ui,sans-serif;margin:24px}}
h1{{font-size:22px;margin:0 0 4px}} h2{{font-size:17px;margin:28px 0 8px}}
table{{border-collapse:collapse;width:100%}} th,td{{text-align:left;padding:6px 10px;border-bottom:1px solid #2a3439}}
th{{color:#9aa8b0;font-weight:600}} .muted{{color:#9aa8b0}} .ok{{color:#5fd3a0}}
tr.sev3 td:nth-child(4){{color:#ff6b5b;font-weight:700}} tr.sev2 td:nth-child(4){{color:#f2b84b}}
</style></head><body>
<h1>Lifeline Mesh · uploaded from the mesh</h1>
<p class="muted">{total[0]} packets from {total[1]} phones · refreshes every 5 s · each packet is stored once, however many phones upload it</p>
<h2>Official warnings sent to phones ({len(in_force)})</h2>
<p class="muted">NDMA SACHET feeds: {_e(", ".join(ow.status["feeds"]) or "off")} · last check {_t((ow.status["lastPollAt"] or 0) * 1000)}{(" · error: " + _e(ow.status["lastError"])) if ow.status["lastError"] else ""} · only Severe and Extreme warnings in force go to phones</p>
{simple(in_force, [("Event", lambda w: ("DRILL · " if w["drill"] else "") + _e(w["event"])), ("Severity", lambda w: _e(w["severity"])), ("Headline", lambda w: _e(w["headline"])), ("Until", lambda w: _t((w["expires"] or 0) * 1000))])}
<p class="muted">Latest received (any severity):</p>
{simple(recent, [("Event", lambda w: _e(w["event"])), ("Severity", lambda w: _e(w["severity"])), ("Area", lambda w: _e(w["area"])), ("Sent", lambda w: _t((w["sent"] or 0) * 1000))])}
<form method="post" action="/api/warnings/drill" style="margin-top:10px">
<input name="event" value="Flood" size="10"> <input name="headline" value="Drill: flood warning for this area. Move to higher ground." size="50">
<input name="minutes" value="30" size="3"> min <button>Send a drill warning to phones</button></form>
<h2>Alerts ({len(alerts)})</h2>{alert_table}
<h2>Teams online ({sum(1 for t in teams if now_ms - t["seenAt"] < ONLINE_MS)})</h2>{simple(teams, [("Team", lambda t: _e(TEAM_NAMES.get(t["role"], "team"))), ("Name", lambda t: _e(t["name"] or t["origin"][-4:])), ("Place", lambda t: "unknown" if t["lat"] is None else f"{t['lat']:.5f}, {t['lon']:.5f}"), ("Last seen", lambda t: _t(t["seenAt"])), ("Online", lambda t: "yes" if now_ms - t["seenAt"] < ONLINE_MS else "no")])}
<h2>Check-ins ({len(checkins)})</h2>{simple(checkins, [("Sent", lambda r: _t(r["sentAt"])), ("Name", lambda r: _e(r["name"])), ("Place", _place), ("Hops", lambda r: r["hops"])])}
<h2>Community reports ({len(reports)})</h2>{simple(reports, [("Sent", lambda r: _t(r["sentAt"])), ("Report", lambda r: _e(REPORT.get(r["reportType"], r["reportType"]))), ("Note", lambda r: _e(r["note"])), ("Place", _place)])}
</body></html>"""
