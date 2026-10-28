"""Shared helpers for the platform connectors."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Iterable, Mapping

from .. import db


def stable_id(*parts: Any, length: int = 20) -> str:
    payload = "|".join("" if part is None else str(part) for part in parts)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:length]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def provenance_row(
    *,
    field_name: str,
    original_value: Any,
    normalized_value: Any,
    source_record_id: str,
    source_url: str | None,
    source_id: str | None = None,
    verification: str = "REFERENCE",
    confidence: str = "medium",
    license_name: str = "unknown",
    license_url: str | None = None,
    transformations: str = "",
    retrieved_at: str | None = None,
    source_updated_at: str | None = None,
) -> dict[str, Any]:
    row = {
        "field_name": field_name,
        "original_value": None if original_value is None else str(original_value),
        "normalized_value": None if normalized_value is None else str(normalized_value),
        "source_record_id": source_record_id,
        "source_url": source_url,
        "verification": verification,
        "license_name": license_name,
        "license_url": license_url,
        "transformations": transformations,
        "source_updated_at": source_updated_at,
        "last_verified_at": retrieved_at,
    }
    if source_id:
        row["source_id"] = source_id
    return row


def entity_iso_set(connection: Any) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT iso2 FROM countries_and_regions WHERE iso2 IS NOT NULL"
        )
    }


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def is_short_number(value: str) -> bool:
    return bool(re.fullmatch(r"\d{1,12}", value or ""))


def source_release_for(connection: Any, source_id: str) -> str | None:
    row = connection.execute(
        "SELECT release_id FROM source_releases WHERE source_id=? ORDER BY fetched_at DESC LIMIT 1",
        (source_id,),
    ).fetchone()
    return row[0] if row else None


def ensure_source(connection: Any, source_id: str, source_url: str) -> None:
    """Fail closed when a connector's source is not in the registry."""

    row = connection.execute(
        "SELECT 1 FROM source_registry WHERE source_id=?", (source_id,)
    ).fetchone()
    if row is None:
        raise RuntimeError(f"source is not registered: {source_id}")


def audit_import_summary(connection: Any, report: Mapping[str, Any]) -> None:
    db.audit(
        connection,
        "import.reported",
        entity_type="import_job",
        entity_id=str(report.get("job_id")),
        details={
            "connector_id": report.get("connector_id"),
            "status": report.get("status"),
            "read": report.get("read"),
            "added": report.get("added"),
            "updated": report.get("updated"),
            "rejected": report.get("rejected"),
        },
    )
