#!/usr/bin/env python3
"""Tomorrowland ticket watcher.

Polls the official Tomorrowland pages and pushes a notification to your phone
when something relevant changes -- above all when the mandatory pre-registration
for the Belgium edition is announced or opens.

This tool only watches and notifies. It never logs in, never queues, never buys.

Usage:
    ./watcher.py --once            # one poll cycle (what the systemd timer runs)
    ./watcher.py --loop            # run continuously, sleeping between cycles
    ./watcher.py --test-notify     # send a test push to verify your setup
    ./watcher.py --check-sources   # verify every configured URL is reachable
    ./watcher.py --show-state      # dump what the watcher currently remembers
    ./watcher.py --add-date "Vorregistrierung öffnet" 2026-12-08T15:00
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import random
import re
import sys
import time
import unicodedata
from datetime import datetime, timedelta
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo

import requests

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_CONFIG = BASE_DIR / "config.json"
STATE_FILE = BASE_DIR / "state.json"
SNAPSHOT_DIR = BASE_DIR / "snapshots"

log = logging.getLogger("watcher")


# --------------------------------------------------------------------------
# config & state
# --------------------------------------------------------------------------


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        sys.exit(
            f"Keine Konfiguration unter {path}.\n"
            f"Kopiere config.example.json nach config.json und trag deine "
            f"Notification-Daten ein."
        )
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def save_config(path: Path, cfg: dict[str, Any]) -> None:
    tmp = path.with_suffix(".json.tmp")
    with tmp.open("w", encoding="utf-8") as fh:
        json.dump(cfg, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    tmp.replace(path)


class State:
    """Everything the watcher remembers between runs."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.data: dict[str, Any] = {
            "sources": {},        # url -> {hash, last_change, failures}
            "seen_alerts": {},    # alert key -> iso timestamp
            "sent_reminders": [], # "<label>@<offset>" markers
            "last_heartbeat": None,
        }
        if path.exists():
            with path.open(encoding="utf-8") as fh:
                self.data.update(json.load(fh))

    def save(self) -> None:
        tmp = self.path.with_suffix(".json.tmp")
        with tmp.open("w", encoding="utf-8") as fh:
            json.dump(self.data, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        tmp.replace(self.path)

    def source(self, url: str) -> dict[str, Any]:
        return self.data["sources"].setdefault(
            url, {"hash": None, "last_change": None, "failures": 0}
        )


# --------------------------------------------------------------------------
# notifications
# --------------------------------------------------------------------------


class Notifier:
    """Fan-out to every configured push channel."""

    def __init__(self, cfg: dict[str, Any], dry_run: bool = False) -> None:
        self.cfg = cfg.get("notify", {})
        self.dry_run = dry_run

    def send(
        self,
        title: str,
        message: str,
        priority: str = "default",
        url: str | None = None,
        tags: Iterable[str] = (),
    ) -> None:
        log.info("PUSH [%s] %s | %s", priority, title, message.replace("\n", " / ")[:160])
        if self.dry_run:
            return
        self._ntfy(title, message, priority, url, list(tags))
        self._telegram(title, message, url)

    def _ntfy(self, title, message, priority, url, tags) -> None:
        conf = self.cfg.get("ntfy", {})
        if not conf.get("enabled"):
            return
        topic = conf.get("topic", "")
        if not topic or "CHANGE-ME" in topic:
            log.error("ntfy ist aktiviert, aber kein Topic gesetzt.")
            return
        payload: dict[str, Any] = {
            "topic": topic,
            "title": title,
            "message": message,
            "priority": {"min": 1, "low": 2, "default": 3, "high": 4, "urgent": 5}.get(
                priority, 3
            ),
        }
        if tags:
            payload["tags"] = tags
        if url:
            payload["click"] = url
        headers = {}
        if conf.get("token"):
            headers["Authorization"] = f"Bearer {conf['token']}"
        try:
            resp = requests.post(
                conf.get("server", "https://ntfy.sh"),
                json=payload,
                headers=headers,
                timeout=20,
            )
            resp.raise_for_status()
        except requests.RequestException as exc:
            log.error("ntfy-Push fehlgeschlagen: %s", exc)

    def _telegram(self, title, message, url) -> None:
        conf = self.cfg.get("telegram", {})
        if not conf.get("enabled"):
            return
        token, chat_id = conf.get("bot_token"), conf.get("chat_id")
        if not token or not chat_id:
            log.error("Telegram ist aktiviert, aber Token oder Chat-ID fehlt.")
            return
        text = f"*{_md_escape(title)}*\n{_md_escape(message)}"
        if url:
            text += f"\n{url}"
        try:
            resp = requests.post(
                f"https://api.telegram.org/bot{token}/sendMessage",
                json={
                    "chat_id": chat_id,
                    "text": text,
                    "parse_mode": "MarkdownV2",
                    "disable_web_page_preview": True,
                },
                timeout=20,
            )
            resp.raise_for_status()
        except requests.RequestException as exc:
            log.error("Telegram-Push fehlgeschlagen: %s", exc)


def _md_escape(text: str) -> str:
    for ch in r"_*[]()~`>#+-=|{}.!":
        text = text.replace(ch, "\\" + ch)
    return text


# --------------------------------------------------------------------------
# fetching & text extraction
# --------------------------------------------------------------------------


class _TextExtractor(HTMLParser):
    """Turn HTML into plain text, dropping script/style/noscript noise."""

    SKIP = {"script", "style", "noscript", "svg", "head"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._depth = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._depth += 1

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._depth:
            self._depth -= 1

    def handle_data(self, data):
        if not self._depth and data.strip():
            self.parts.append(data.strip())


def html_to_text(html: str) -> str:
    parser = _TextExtractor()
    try:
        parser.feed(html)
    except Exception as exc:  # malformed markup should not kill the run
        log.warning("HTML konnte nur teilweise geparst werden: %s", exc)
    text = "\n".join(parser.parts)
    text = unicodedata.normalize("NFKC", text)
    # Collapse the volatile bits that would otherwise look like a change on
    # every poll: cache-busting ids, timestamps, view counters.
    text = re.sub(r"\b[0-9a-f]{16,}\b", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    return "\n".join(line.strip() for line in text.splitlines() if line.strip())


def fetch(url: str, user_agent: str, timeout: int = 30, retries: int = 3) -> str:
    headers = {
        "User-Agent": user_agent,
        "Accept-Language": "en,de;q=0.8",
        "Accept": "text/html,application/xhtml+xml",
    }
    last: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            resp = requests.get(url, headers=headers, timeout=timeout)
            resp.raise_for_status()
            return resp.text
        except requests.RequestException as exc:
            last = exc
            if attempt < retries:
                delay = 2**attempt
                log.warning("%s: Versuch %d fehlgeschlagen (%s), warte %ds", url, attempt, exc, delay)
                time.sleep(delay)
    raise RuntimeError(f"{url} nach {retries} Versuchen nicht erreichbar: {last}")


# --------------------------------------------------------------------------
# signal detection
# --------------------------------------------------------------------------

MONTHS = (
    "january|february|march|april|may|june|july|august|september|october|november|december|"
    "januar|februar|märz|maerz|mai|juni|juli|oktober|dezember"
)
DATE_PATTERNS = [
    re.compile(rf"\b\d{{1,2}}(?:st|nd|rd|th|\.)?\s+(?:{MONTHS})\b\s*\d{{0,4}}", re.I),
    re.compile(rf"\b(?:{MONTHS})\s+\d{{1,2}}(?:st|nd|rd|th)?\b,?\s*\d{{0,4}}", re.I),
    re.compile(r"\b\d{1,2}[./]\d{1,2}[./]\d{2,4}\b"),
    re.compile(r"\b\d{1,2}[:.]\d{2}\s*(?:CET|CEST|UTC|GMT|Uhr)\b", re.I),
]


def find_dates(line: str) -> list[str]:
    hits: list[str] = []
    for pattern in DATE_PATTERNS:
        hits.extend(m.group(0).strip() for m in pattern.finditer(line))
    return sorted(set(hits))


def scan_for_signals(text: str, terms: dict[str, list[str]]) -> list[dict[str, Any]]:
    """Find lines that mention pre-registration or a ticket sale for our year."""
    prereg = [t.lower() for t in terms.get("prereg", [])]
    sale = [t.lower() for t in terms.get("sale", [])]
    years = [t.lower() for t in terms.get("year", [])]

    signals: list[dict[str, Any]] = []
    for line in text.splitlines():
        low = line.lower()
        if len(line) > 400:  # nav blobs and cookie banners, not announcements
            continue
        has_prereg = any(t in low for t in prereg)
        has_sale = any(t in low for t in sale)
        if not (has_prereg or has_sale):
            continue
        # A year mention makes it far more likely to be the real announcement,
        # but a bare "pre-registration opens on ..." line counts too.
        dates = find_dates(line)
        has_year = any(y in low for y in years)
        if not has_year and not dates:
            continue
        signals.append(
            {
                "kind": "prereg" if has_prereg else "sale",
                "line": line,
                "dates": dates,
                "has_year": has_year,
            }
        )
    return signals


def signal_key(source_name: str, signal: dict[str, Any]) -> str:
    normalized = re.sub(r"\s+", " ", signal["line"].lower()).strip()
    digest = hashlib.sha256(f"{source_name}|{normalized}".encode()).hexdigest()[:16]
    return f"{signal['kind']}:{digest}"


def diff_excerpt(old: str, new: str, max_lines: int = 6) -> str:
    """Lines that are new since the last snapshot, trimmed for a push message."""
    previous = set(old.splitlines())
    added = [ln for ln in new.splitlines() if ln not in previous and len(ln) > 12]
    if not added:
        return "(Änderung im Seitenaufbau, kein neuer Text)"
    shown = added[:max_lines]
    text = "\n".join(f"• {ln[:160]}" for ln in shown)
    if len(added) > max_lines:
        text += f"\n… und {len(added) - max_lines} weitere Zeilen"
    return text


# --------------------------------------------------------------------------
# poll cycle
# --------------------------------------------------------------------------


def snapshot_path(url: str) -> Path:
    return SNAPSHOT_DIR / (hashlib.sha256(url.encode()).hexdigest()[:16] + ".txt")


def check_source(
    source: dict[str, Any],
    cfg: dict[str, Any],
    state: State,
    notifier: Notifier,
    now: datetime,
) -> None:
    name, url = source["name"], source["url"]
    entry = state.source(url)

    try:
        html = fetch(url, cfg["user_agent"], cfg.get("timeout_seconds", 30))
    except RuntimeError as exc:
        entry["failures"] += 1
        log.error("%s", exc)
        threshold = cfg.get("failure_alert_after", 3)
        # Silent failure is the real danger here: a watcher that quietly stopped
        # working is worse than no watcher at all.
        if entry["failures"] == threshold or entry["failures"] % (threshold * 8) == 0:
            notifier.send(
                "⚠️ Tomorrowland-Watcher: Seite nicht erreichbar",
                f"{name} seit {entry['failures']} Versuchen nicht abrufbar.\n"
                f"Bitte prüfen, ob die URL noch stimmt:\n{url}",
                priority="high",
                url=url,
                tags=["warning"],
            )
        return

    if entry["failures"] >= cfg.get("failure_alert_after", 3):
        notifier.send(
            "✅ Tomorrowland-Watcher: wieder online",
            f"{name} ist wieder erreichbar.",
            priority="low",
            tags=["white_check_mark"],
        )
    entry["failures"] = 0

    text = html_to_text(html)
    digest = hashlib.sha256(text.encode()).hexdigest()
    snap = snapshot_path(url)
    previous_text = snap.read_text(encoding="utf-8") if snap.exists() else ""

    # 1. Keyword signals -- the alerts that actually matter.
    for signal in scan_for_signals(text, cfg.get("watch_terms", {})):
        key = signal_key(name, signal)
        if key in state.data["seen_alerts"]:
            continue
        state.data["seen_alerts"][key] = now.isoformat()
        dates = ", ".join(signal["dates"]) if signal["dates"] else "kein Datum erkannt"
        label = "VORREGISTRIERUNG" if signal["kind"] == "prereg" else "TICKETVERKAUF"
        notifier.send(
            f"🎟️ {label} – neue Info auf {name}",
            f"{signal['line'][:400]}\n\n"
            f"Erkannte Daten: {dates}\n\n"
            f"Jetzt prüfen und Termin eintragen:\n"
            f"./watcher.py --add-date \"Vorregistrierung öffnet\" JJJJ-MM-TTTHH:MM",
            priority="urgent",
            url=url,
            tags=["rotating_light", "tickets"],
        )

    # 2. Generic page change -- lower signal, still worth knowing.
    if entry["hash"] and entry["hash"] != digest:
        notifier.send(
            f"📄 Änderung auf {name}",
            diff_excerpt(previous_text, text),
            priority="default",
            url=url,
            tags=["eyes"],
        )
        entry["last_change"] = now.isoformat()
    elif not entry["hash"]:
        log.info("%s: Erstaufnahme gespeichert (%d Zeichen)", name, len(text))

    entry["hash"] = digest
    SNAPSHOT_DIR.mkdir(exist_ok=True)
    snap.write_text(text, encoding="utf-8")


def check_key_dates(
    cfg: dict[str, Any], state: State, notifier: Notifier, now: datetime, tz: ZoneInfo
) -> None:
    """Countdown pushes for dates you already know (or just learned)."""
    offsets = sorted(cfg.get("reminder_offsets_minutes", []), reverse=True)
    for item in cfg.get("key_dates", []):
        label, raw = item.get("label", "Termin"), item.get("at")
        if not raw:
            continue
        try:
            when = datetime.fromisoformat(raw)
        except ValueError:
            log.error("Ungültiges Datum in key_dates: %r", raw)
            continue
        if when.tzinfo is None:
            when = when.replace(tzinfo=tz)
        # Gespeichert wird nur der Offset (+02:00); fuer die Anzeige wollen wir
        # den Zonennamen (CET/CEST) zurueck.
        when = when.astimezone(tz)
        remaining = when - now
        if remaining.total_seconds() <= 0:
            continue
        for offset in offsets:
            marker = f"{label}@{offset}"
            if marker in state.data["sent_reminders"]:
                continue
            if remaining <= timedelta(minutes=offset):
                state.data["sent_reminders"].append(marker)
                notifier.send(
                    f"⏰ {label} – {_human_delta(remaining)}",
                    f"{label}\n{when.strftime('%a %d.%m.%Y um %H:%M %Z')}\n\n"
                    f"Checkliste: Account verifiziert, Ausweisdaten aller 4 Personen "
                    f"bereit, Kreditkarte hinterlegt und 3-D-Secure getestet.",
                    priority="urgent" if offset <= 120 else "high",
                    url=cfg.get("primary_url"),
                    tags=["alarm_clock"],
                )
                break


def _human_delta(delta: timedelta) -> str:
    minutes = int(delta.total_seconds() // 60)
    if minutes < 90:
        return f"in {minutes} Minuten"
    hours = minutes // 60
    if hours < 48:
        return f"in {hours} Stunden"
    return f"in {hours // 24} Tagen"


def heartbeat(
    cfg: dict[str, Any], state: State, notifier: Notifier, now: datetime
) -> None:
    hours = cfg.get("heartbeat_hours", 0)
    if not hours:
        return
    last = state.data.get("last_heartbeat")
    if last and now - datetime.fromisoformat(last) < timedelta(hours=hours):
        return
    state.data["last_heartbeat"] = now.isoformat()
    sources = [s for s in cfg["sources"] if s.get("enabled", True)]
    notifier.send(
        "💚 Watcher läuft",
        f"{len(sources)} Seiten werden überwacht, keine Auffälligkeiten.\n"
        f"Letzter Check: {now.strftime('%d.%m.%Y %H:%M')}",
        priority="min",
        tags=["green_heart"],
    )


def run_once(cfg: dict[str, Any], state: State, notifier: Notifier) -> None:
    tz = ZoneInfo(cfg.get("timezone", "Europe/Berlin"))
    now = datetime.now(tz)
    for source in cfg["sources"]:
        if not source.get("enabled", True):
            continue
        check_source(source, cfg, state, notifier, now)
        time.sleep(random.uniform(1.5, 4.0))  # be a polite visitor, not a hammer
    check_key_dates(cfg, state, notifier, now, tz)
    heartbeat(cfg, state, notifier, now)
    state.save()


# --------------------------------------------------------------------------
# auxiliary commands
# --------------------------------------------------------------------------


def check_sources(cfg: dict[str, Any]) -> int:
    problems = 0
    for source in cfg["sources"]:
        url = source["url"]
        try:
            resp = requests.get(
                url, headers={"User-Agent": cfg["user_agent"]}, timeout=30
            )
            size = len(html_to_text(resp.text))
            status = "OK " if resp.ok and size > 500 else "PRÜFEN"
            if status != "OK ":
                problems += 1
            print(f"{status:7} {resp.status_code}  {size:7d} Zeichen  {source['name']}: {url}")
        except requests.RequestException as exc:
            problems += 1
            print(f"FEHLER        -  {source['name']}: {url}\n              {exc}")
    return problems


def add_date(config_path: Path, cfg: dict[str, Any], label: str, iso: str) -> None:
    tz = ZoneInfo(cfg.get("timezone", "Europe/Berlin"))
    try:
        parsed = datetime.fromisoformat(iso)
    except ValueError:
        sys.exit(f"Datum {iso!r} nicht lesbar. Format: 2026-12-08T15:00")
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=tz)
    cfg.setdefault("key_dates", [])
    cfg["key_dates"] = [d for d in cfg["key_dates"] if d.get("label") != label]
    cfg["key_dates"].append({"label": label, "at": parsed.isoformat()})
    save_config(config_path, cfg)
    print(f"Eingetragen: {label} -> {parsed.strftime('%a %d.%m.%Y %H:%M %Z')}")
    print("Erinnerungen kommen bei:", ", ".join(
        _human_delta(timedelta(minutes=m))
        for m in sorted(cfg.get("reminder_offsets_minutes", []), reverse=True)
    ))


# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description="Tomorrowland pre-registration watcher")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--once", action="store_true", help="ein Durchlauf (Standard)")
    parser.add_argument("--loop", action="store_true", help="dauerhaft laufen")
    parser.add_argument("--dry-run", action="store_true", help="nichts pushen, nur loggen")
    parser.add_argument("--test-notify", action="store_true", help="Test-Push senden")
    parser.add_argument("--check-sources", action="store_true", help="URLs prüfen")
    parser.add_argument("--show-state", action="store_true", help="Zustand anzeigen")
    parser.add_argument("--reset-alerts", action="store_true", help="gesehene Alerts vergessen")
    parser.add_argument("--add-date", nargs=2, metavar=("LABEL", "ZEITPUNKT"))
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    cfg = load_config(args.config)
    state = State(STATE_FILE)
    notifier = Notifier(cfg, dry_run=args.dry_run)

    if args.add_date:
        add_date(args.config, cfg, *args.add_date)
        return 0

    if args.show_state:
        print(json.dumps(state.data, indent=2, ensure_ascii=False))
        return 0

    if args.reset_alerts:
        state.data["seen_alerts"] = {}
        state.data["sent_reminders"] = []
        state.save()
        print("Alert-Historie geleert.")
        return 0

    if args.check_sources:
        return 1 if check_sources(cfg) else 0

    if args.test_notify:
        notifier.send(
            "🔔 Tomorrowland-Watcher eingerichtet",
            "Wenn du das hier auf dem Handy siehst, funktioniert die "
            "Benachrichtigung. Ab jetzt meldet sich der Pi, sobald sich auf den "
            "Tomorrowland-Seiten etwas zur Vorregistrierung tut.",
            priority="high",
            url=cfg.get("primary_url"),
            tags=["bell"],
        )
        return 0

    if args.loop:
        interval = cfg.get("poll_interval_minutes", 30) * 60
        log.info("Loop-Modus, Intervall %d Minuten", interval // 60)
        while True:
            try:
                run_once(cfg, state, notifier)
            except Exception:
                log.exception("Durchlauf fehlgeschlagen, versuche es beim nächsten Mal")
            time.sleep(interval + random.uniform(0, 120))

    run_once(cfg, state, notifier)
    return 0


if __name__ == "__main__":
    sys.exit(main())
