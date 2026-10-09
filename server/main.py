"""Lifeline Mesh sync server (CLAUDE.md 7.6, SETUP.md 9).

Phones that find internet POST the alerts, check-ins, reports and acks they hold. Uploads are idempotent by message
ID: the same packet arriving from three phones is stored once (and we count how many phones sent it).

Run:  uvicorn main:app --host 0.0.0.0 --port 8000
"""

import html
import os
import sqlite3
import time
from contextlib import closing
from typing import Literal, Optional

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

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


COLUMNS = [
    "id", "type", "origin", "hops", "sentAt", "receivedAt", "lat", "lon", "accuracyM", "needs", "severity",
    "people", "note", "refId", "etaMinutes", "reportType", "name", "uploadedBy",
]


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


init()
app = FastAPI(title="Lifeline Mesh sync")


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
    return {"stored": stored, "duplicates": duplicates}


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
    """One page for judges: alerts (urgent first) with their reply status, then check-ins and reports."""
    with closing(db()) as conn:
        alerts = conn.execute("SELECT * FROM packets WHERE type = 'alert' ORDER BY severity DESC, sentAt DESC").fetchall()
        acks = {}
        for a in conn.execute("SELECT refId, etaMinutes, sentAt FROM packets WHERE type = 'ack' ORDER BY sentAt"):
            acks[a["refId"]] = a
        checkins = conn.execute("SELECT * FROM packets WHERE type = 'check_in' ORDER BY sentAt DESC").fetchall()
        reports = conn.execute("SELECT * FROM packets WHERE type = 'report' ORDER BY sentAt DESC").fetchall()
        total = conn.execute("SELECT COUNT(*), COUNT(DISTINCT uploadedBy) FROM packets").fetchone()

    def alert_row(r: sqlite3.Row) -> str:
        ack = acks.get(r["id"])
        status = f"Help is coming · ETA {ack['etaMinutes']} min" if ack else "Open"
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
<h2>Alerts ({len(alerts)})</h2>{alert_table}
<h2>Check-ins ({len(checkins)})</h2>{simple(checkins, [("Sent", lambda r: _t(r["sentAt"])), ("Name", lambda r: _e(r["name"])), ("Place", _place), ("Hops", lambda r: r["hops"])])}
<h2>Community reports ({len(reports)})</h2>{simple(reports, [("Sent", lambda r: _t(r["sentAt"])), ("Report", lambda r: _e(REPORT.get(r["reportType"], r["reportType"]))), ("Note", lambda r: _e(r["note"])), ("Place", _place)])}
</body></html>"""
