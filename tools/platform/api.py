"""Optional read-only HTTP API over the platform SQLite database.

The Android app does not depend on this service: offline lookup remains the
default.  The API exists for a shared deployment and for inspecting the
catalog, coverage, provenance, and import status.  It exposes only data that
is present in the database and never claims that a line is active.
"""

from __future__ import annotations

import argparse
import json
import re
import threading
import time
import urllib.parse
from collections import defaultdict, deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Mapping

from . import coverage, db, normalize, paths, summary

MAX_BODY = 1_000_000
DEFAULT_PAGE_SIZE = 50
MAX_PAGE_SIZE = 500


class RateLimiter:
    def __init__(self, limit: int = 120, window_seconds: int = 60) -> None:
        self.limit = limit
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            bucket = self._hits[key]
            while bucket and now - bucket[0] > self.window:
                bucket.popleft()
            if len(bucket) >= self.limit:
                return False
            bucket.append(now)
            return True


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        self.status = status
        self.code = code
        self.message = message
        super().__init__(message)


def _json_safe(value: Any) -> Any:
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if isinstance(value, Mapping):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return str(value)


def _country_payload(connection: Any, iso2: str) -> dict[str, Any]:
    entity = connection.execute(
        "SELECT * FROM countries_and_regions WHERE iso2=? OR entity_id=?",
        (iso2.upper(), iso2.upper()),
    ).fetchone()
    if entity is None:
        raise ApiError(404, "country_not_found", f"No catalog entry for {iso2}")
    entity = dict(entity)
    sources = summary._source_map(connection)
    coverage_map = summary._coverage_map(connection)
    return summary._country_payload(entity, sources, coverage_map)


def _source_payload(row: Mapping[str, Any]) -> dict[str, Any]:
    item = dict(row)
    item["dataCategories"] = summary._load_json(item.pop("data_categories_json", "[]"), [])
    return item


def _page(rows: list[Any], query: Mapping[str, list[str]]) -> tuple[list[Any], dict[str, Any]]:
    try:
        limit = int((query.get("limit") or [DEFAULT_PAGE_SIZE])[0])
        offset = int((query.get("offset") or [0])[0])
    except ValueError as exc:
        raise ApiError(400, "invalid_pagination", "limit and offset must be integers") from exc
    if limit < 1 or limit > MAX_PAGE_SIZE:
        raise ApiError(400, "invalid_pagination", f"limit must be between 1 and {MAX_PAGE_SIZE}")
    if offset < 0:
        raise ApiError(400, "invalid_pagination", "offset must be >= 0")
    return rows[offset : offset + limit], {
        "limit": limit,
        "offset": offset,
        "total": len(rows),
        "hasMore": offset + limit < len(rows),
    }


def _lookup_payload(connection: Any, number: str, region: str | None) -> dict[str, Any]:
    if not number or len(number) > 32 or not normalize.validate_number_input(number):
        raise ApiError(400, "invalid_number", "number must be 1-32 characters of digits, +, spaces, or punctuation")
    if region and not re.fullmatch(r"[A-Za-z]{2}", region):
        raise ApiError(400, "invalid_region", "region must be a two-letter ISO code")
    normalized = normalize.parse_phone(number, region.upper() if region else None)
    iso2 = normalized.country_iso2
    country = _country_payload(connection, iso2) if iso2 else None
    carrier = None
    if normalized.e164:
        digits = normalized.e164.lstrip("+")
        rows = connection.execute(
            "SELECT * FROM carrier_observations WHERE ? LIKE range_prefix || '%' ORDER BY LENGTH(range_prefix) DESC LIMIT 1",
            (digits,),
        ).fetchall()
        if rows:
            row = dict(rows[0])
            carrier = {
                "carrierName": row["carrier_name"],
                "observationType": row["observation_type"],
                "rangePrefix": row["range_prefix"],
                "confidence": row["confidence"],
                "limitations": row["limitations"],
                "sourceId": row["source_id"],
                "notCurrentCarrier": True,
            }
    return {
        "input": normalized.original,
        "validNumberingPlan": normalized.valid,
        "possible": normalized.possible,
        "e164": normalized.e164,
        "national": normalized.national,
        "international": normalized.international,
        "countryIso2": iso2,
        "callingCode": normalized.calling_code,
        "numberType": normalized.number_type,
        "country": country,
        "allocatedCarrier": carrier,
        "portedCarrier": None,
        "lineActive": None,
        "lineActiveNote": "No permitted source in this platform proves that a line is active.",
        "error": normalized.error,
        "limitations": [
            "A valid numbering-plan match does not prove that a line exists, is assigned, or is active.",
            "The carrier value is an allocated-prefix observation and can be stale after mobile number portability.",
        ],
    }


def _reputation_payload(connection: Any, number: str, region: str | None) -> dict[str, Any]:
    normalized = normalize.parse_phone(number, region.upper() if region else None)
    e164 = normalized.e164
    rows = []
    if e164:
        rows = [
            dict(row)
            for row in connection.execute(
                "SELECT * FROM reputation_reports WHERE e164_number=? ORDER BY imported_at DESC LIMIT 100",
                (e164,),
            )
        ]
    return {
        "e164": e164,
        "reports": rows,
        "available": bool(rows),
        "reason": (
            "No permitted reputation source was imported for this number. "
            "Blocked or restricted sources are catalogued but never queried at runtime."
            if not rows
            else ""
        ),
        "limitations": [
            "A report is not a criminal finding or proof of fraud.",
            "Source license and retention terms must be respected before redistribution.",
        ],
    }


def route(connection: Any, path: str, query: Mapping[str, list[str]]) -> tuple[int, Any]:
    parts = [part for part in path.split("/") if part]
    if parts[:2] != ["api", "v1"]:
        raise ApiError(404, "not_found", "Use /api/v1/...")

    if parts == ["api", "v1", "health"]:
        return 200, {
            "status": "ok",
            "schemaVersion": db.SCHEMA_VERSION,
            "counts": {
                "countries": db.table_count(connection, "countries_and_regions"),
                "sources": db.table_count(connection, "source_registry"),
                "importJobs": db.table_count(connection, "import_jobs"),
            },
        }

    if parts == ["api", "v1", "countries"]:
        rows = [
            {
                "entityId": row["entity_id"],
                "iso2": row["iso2"],
                "nameEn": row["name_en"],
                "nameAr": row["name_ar"],
                "callingCode": row["calling_code"],
                "entityKind": row["entity_kind"],
            }
            for row in connection.execute("SELECT * FROM countries_and_regions ORDER BY entity_id")
        ]
        page, meta = _page(rows, query)
        return 200, {"items": page, "pagination": meta}

    if len(parts) >= 4 and parts[:3] == ["api", "v1", "countries"]:
        code = parts[3].upper()
        if len(parts) == 4:
            return 200, _country_payload(connection, code)
        if len(parts) == 5 and parts[4] == "sources":
            entity = connection.execute(
                "SELECT * FROM countries_and_regions WHERE iso2=? OR entity_id=?", (code, code)
            ).fetchone()
            if entity is None:
                raise ApiError(404, "country_not_found", f"No catalog entry for {code}")
            sources = []
            for row in connection.execute("SELECT * FROM source_registry ORDER BY source_id"):
                item = dict(row)
                if (
                    item.get("country_iso2") == code
                    or item.get("entity_id") == entity["entity_id"]
                    or item.get("geographic_scope") == "global"
                ):
                    sources.append(_source_payload(item))
            return 200, {"countryIso2": code, "items": sources}
        raise ApiError(404, "not_found", "Unknown country endpoint")

    if parts == ["api", "v1", "phone", "lookup"]:
        return 200, _lookup_payload(
            connection,
            (query.get("number") or [""])[0],
            (query.get("region") or [None])[0],
        )
    if parts == ["api", "v1", "phone", "metadata"]:
        payload = _lookup_payload(
            connection,
            (query.get("number") or [""])[0],
            (query.get("region") or [None])[0],
        )
        return 200, {
            key: payload[key]
            for key in (
                "validNumberingPlan",
                "possible",
                "e164",
                "national",
                "international",
                "countryIso2",
                "callingCode",
                "numberType",
                "limitations",
            )
        }
    if parts == ["api", "v1", "phone", "carrier"]:
        payload = _lookup_payload(
            connection,
            (query.get("number") or [""])[0],
            (query.get("region") or [None])[0],
        )
        return 200, {
            "e164": payload["e164"],
            "allocatedCarrier": payload["allocatedCarrier"],
            "portedCarrier": None,
            "note": "Allocated prefix only; not the current carrier after portability.",
        }
    if parts == ["api", "v1", "phone", "reputation"]:
        return 200, _reputation_payload(
            connection,
            (query.get("number") or [""])[0],
            (query.get("region") or [None])[0],
        )

    if parts == ["api", "v1", "data-sources"]:
        category = (query.get("category") or [None])[0]
        import_status = (query.get("importStatus") or [None])[0]
        rows = []
        for row in connection.execute("SELECT * FROM source_registry ORDER BY source_id"):
            item = _source_payload(row)
            if category and category not in item["dataCategories"]:
                continue
            if import_status and item["import_status"] != import_status:
                continue
            rows.append(item)
        page, meta = _page(rows, query)
        return 200, {"items": page, "pagination": meta}

    if len(parts) >= 4 and parts[:3] == ["api", "v1", "data-sources"]:
        source_id = parts[3]
        row = connection.execute(
            "SELECT * FROM source_registry WHERE source_id=?", (source_id,)
        ).fetchone()
        if row is None:
            raise ApiError(404, "source_not_found", f"No source {source_id}")
        item = _source_payload(row)
        if len(parts) == 4:
            return 200, item
        if len(parts) == 5 and parts[4] == "status":
            jobs = [
                dict(job)
                for job in connection.execute(
                    "SELECT * FROM import_jobs WHERE source_id=? ORDER BY started_at DESC LIMIT 50",
                    (source_id,),
                )
            ]
            releases = [
                dict(release)
                for release in connection.execute(
                    "SELECT * FROM source_releases WHERE source_id=? ORDER BY fetched_at DESC LIMIT 50",
                    (source_id,),
                )
            ]
            return 200, {"source": item, "importJobs": jobs, "releases": releases}
        raise ApiError(404, "not_found", "Unknown data-source endpoint")

    if parts == ["api", "v1", "coverage"]:
        iso2 = (query.get("country") or [None])[0]
        category = (query.get("category") or [None])[0]
        sql = "SELECT * FROM coverage_matrix WHERE 1=1"
        values: list[Any] = []
        if iso2:
            sql += " AND country_iso2=?"
            values.append(iso2.upper())
        if category:
            sql += " AND data_category=?"
            values.append(category)
        sql += " ORDER BY country_iso2, data_category"
        rows = [dict(row) for row in connection.execute(sql, values)]
        page, meta = _page(rows, query)
        return 200, {"items": page, "pagination": meta}

    if parts == ["api", "v1", "imports"]:
        rows = [dict(row) for row in connection.execute("SELECT * FROM import_jobs ORDER BY started_at DESC")]
        page, meta = _page(rows, query)
        return 200, {"items": page, "pagination": meta}

    raise ApiError(404, "not_found", "Unknown endpoint")


class Handler(BaseHTTPRequestHandler):
    server_version = "NumXPlatformAPI/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        # Keep request logs free of full phone numbers.
        return

    def _send(self, status: int, payload: Any) -> None:
        body = json.dumps(_json_safe(payload), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        limiter: RateLimiter = self.server.rate_limiter  # type: ignore[attr-defined]
        client = self.client_address[0]
        if not limiter.allow(client):
            self._send(429, {"error": {"code": "rate_limited", "message": "Too many requests"}})
            return
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        connection = db.connect(self.server.db_path)  # type: ignore[attr-defined]
        try:
            status, payload = route(connection, parsed.path, query)
        except ApiError as exc:
            status, payload = exc.status, {"error": {"code": exc.code, "message": exc.message}}
        except Exception as exc:  # pragma: no cover - defensive
            status, payload = 500, {
                "error": {
                    "code": "internal_error",
                    "message": "The request could not be completed.",
                    "detail": type(exc).__name__,
                }
            }
        finally:
            connection.close()
        self._send(status, payload)

    def do_POST(self) -> None:  # noqa: N802
        self._send(405, {"error": {"code": "method_not_allowed", "message": "Read-only API"}})

    def do_PUT(self) -> None:  # noqa: N802
        self._send(405, {"error": {"code": "method_not_allowed", "message": "Read-only API"}})

    def do_DELETE(self) -> None:  # noqa: N802
        self._send(405, {"error": {"code": "method_not_allowed", "message": "Read-only API"}})


def serve(host: str, port: int, db_path: str) -> None:
    server = ThreadingHTTPServer((host, port), Handler)
    server.db_path = db_path  # type: ignore[attr-defined]
    server.rate_limiter = RateLimiter()  # type: ignore[attr-defined]
    print(f"NumX platform API listening on http://{host}:{port} (database: {db_path})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only NumX platform API")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default=str(paths.DB_PATH))
    args = parser.parse_args(argv)
    serve(args.host, args.port, args.db)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
