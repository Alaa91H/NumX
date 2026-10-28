"""Bounded link checking for catalogued sources.

Only URLs already present in ``source_registry`` are checked.  The checker
respects robots.txt, throttles per host, records the HTTP status and error, and
never changes a source from ``blocked`` to ``imported`` just because a page is
reachable.
"""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping

from . import db, http, paths


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _candidate_urls(source: Mapping[str, Any]) -> list[tuple[str, str]]:
    urls: list[tuple[str, str]] = [("source_url", source["source_url"])]
    download = source.get("download_url")
    if download and download != source["source_url"]:
        urls.append(("download_url", download))
    return urls


def check_one(client: http.HttpClient, url: str) -> dict[str, Any]:
    result = client.fetch(
        url,
        method="GET",
        max_bytes=64 * 1024,
        use_cache=False,
        check_robots=True,
        accept="text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5",
    )
    if result.error == "robots_disallowed":
        outcome = "robots_disallowed"
    elif result.ok:
        outcome = "reachable"
    elif result.error and result.status is None:
        outcome = "network_error"
    else:
        outcome = f"http_{result.status}"
    return {
        "url": url,
        "finalUrl": result.final_url,
        "outcome": outcome,
        "httpStatus": result.status,
        "contentType": result.content_type,
        "contentLength": result.content_length,
        "sha256": result.sha256 or None,
        "elapsedMs": result.elapsed_ms,
        "robotsChecked": result.robots_checked,
        "robotsAllowed": result.robots_allowed,
        "truncated": result.truncated,
        "error": result.error,
        "checkedAt": result.fetched_at,
    }


def check_sources(
    connection: Any,
    *,
    limit: int | None = None,
    source_ids: Iterable[str] | None = None,
    workers: int = 4,
) -> dict[str, Any]:
    query = "SELECT * FROM source_registry ORDER BY source_id"
    sources = [dict(row) for row in connection.execute(query)]
    if source_ids:
        wanted = set(source_ids)
        sources = [source for source in sources if source["source_id"] in wanted]
    if limit is not None:
        sources = sources[:limit]

    client = http.HttpClient(
        cache_dir=paths.IMPORT_DIR / "http-cache",
        timeout=15.0,
        retries=1,
        min_interval=0.35,
    )
    tasks: list[tuple[dict[str, Any], str, str]] = []
    for source in sources:
        for field, url in _candidate_urls(source):
            tasks.append((source, field, url))

    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {
            pool.submit(check_one, client, url): (source, field, url)
            for source, field, url in tasks
        }
        for future in as_completed(futures):
            source, field, url = futures[future]
            try:
                result = future.result()
            except Exception as exc:  # pragma: no cover - defensive
                result = {
                    "url": url,
                    "finalUrl": url,
                    "outcome": "network_error",
                    "httpStatus": None,
                    "contentType": "",
                    "contentLength": None,
                    "sha256": None,
                    "elapsedMs": 0,
                    "robotsChecked": False,
                    "robotsAllowed": None,
                    "truncated": False,
                    "error": f"{type(exc).__name__}: {exc}",
                    "checkedAt": utc_now(),
                }
            result["sourceId"] = source["source_id"]
            result["sourceName"] = source["source_name"]
            result["field"] = field
            results.append(result)

    results.sort(key=lambda item: (item["sourceId"], item["field"]))
    by_source: dict[str, list[dict[str, Any]]] = {}
    for result in results:
        by_source.setdefault(result["sourceId"], []).append(result)

    with connection:
        for source in sources:
            checks = by_source.get(source["source_id"], [])
            primary = next((item for item in checks if item["field"] == "source_url"), None)
            if primary is None:
                continue
            status = primary.get("httpStatus")
            outcome = primary["outcome"]
            technical = outcome
            last_error = primary.get("error")
            last_success = source.get("last_successful_access_at")
            if primary["outcome"] == "reachable":
                last_success = primary["checkedAt"]
                technical = "reachable"
            elif primary["outcome"] == "robots_disallowed":
                technical = "blocked_by_robots"
            connection.execute(
                """
                UPDATE source_registry SET
                    last_checked_at=?, last_successful_access_at=?, last_http_status=?,
                    last_error=?, technical_review_status=?, updated_at=?
                WHERE source_id=?
                """,
                (
                    primary["checkedAt"],
                    last_success,
                    status,
                    last_error,
                    technical,
                    utc_now(),
                    source["source_id"],
                ),
            )
        db.audit(
            connection,
            "linkcheck.completed",
            details={
                "sources": len(sources),
                "urls": len(results),
                "reachable": sum(1 for item in results if item["outcome"] == "reachable"),
            },
        )

    payload = {
        "generatedAt": utc_now(),
        "sourceCount": len(sources),
        "urlCount": len(results),
        "outcomes": _counts(item["outcome"] for item in results),
        "results": results,
    }
    paths.ensure_dirs()
    output = paths.CATALOG_DIR / "link-check-results.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"output": str(output), **{key: payload[key] for key in ("sourceCount", "urlCount", "outcomes")}}


def _counts(values: Iterable[str]) -> dict[str, int]:
    result: dict[str, int] = {}
    for value in values:
        result[value] = result.get(value, 0) + 1
    return result
