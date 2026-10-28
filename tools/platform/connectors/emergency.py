"""Import the NumX emergency/service reference list as short-code records.

These entries are reference data.  The connector does not claim that a number
was confirmed by a national authority, and it never assigns an E.164 value to a
short code.
"""

from __future__ import annotations

import json
import re
from typing import Any, Iterable, Mapping

from .. import paths
from .base import ImportContext
from .common import entity_iso_set, provenance_row, stable_id

EMERGENCY_CATEGORIES = {
    "EMERGENCY",
    "POLICE",
    "AMBULANCE",
    "FIRE",
    "MULTI",
    "GENERAL",
    "DISPATCH",
    "TRAFFIC",
    "FIRE_AMBULANCE",
}

ALLOWED_CATEGORIES = EMERGENCY_CATEGORIES | {
    "SHORT_CODE",
    "CARRIER",
    "TOLL_FREE",
    "STANDARD_RATE",
}


def load_entries(path=paths.APP_EMERGENCY) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return list(payload.get("entries", []))


class EmergencyConnector:
    connector_id = "numx-emergency-reference"
    connector_version = "1.0.0"
    source_id = "numx-emergency-reference"
    entity_type = "number_range"
    target_table = "number_ranges"
    conflict_columns = ("source_id", "source_record_id")

    def __init__(self, path=paths.APP_EMERGENCY) -> None:
        self.path = path

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        wanted = entity_iso_set(context.connection)
        for entry in load_entries(self.path):
            if entry.get("iso2") in wanted:
                yield entry

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors = []
        if not re.fullmatch(r"[A-Z]{2}", str(record.get("iso2", ""))):
            errors.append("invalid ISO2")
        number = str(record.get("number") or "")
        if not re.fullmatch(r"\d{1,12}", number):
            errors.append("number is not a short national code")
        if record.get("category") not in ALLOWED_CATEGORIES:
            errors.append(f"unknown category: {record.get('category')}")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        iso2 = record["iso2"]
        number = str(record["number"])
        source_record_id = (
            f"{iso2}:{record['category']}:{number}:"
            f"{stable_id(record.get('labelEn') or '')}"
        )
        is_emergency = record["category"] in EMERGENCY_CATEGORIES
        return {
            "plan_id": None,
            "country_iso2": iso2,
            "line_type": "SHORT_CODE",
            "service_type": record["category"],
            "prefix": number,
            "pattern": number,
            "range_start": None,
            "range_end": None,
            "example_number": number,
            "national_prefix": None,
            "source_id": self.source_id,
            "source_record_id": source_record_id,
            "source_release_id": context.source_release_id,
            "valid_from": None,
            "valid_to": None,
            "verification": record.get("verification") or "REFERENCE",
            "confidence": "medium" if is_emergency else "low",
            "notes": (
                "Emergency reference entry; not officially confirmed for every "
                "country. Verify locally before relying on it."
                if is_emergency
                else "Service/short-code reference entry; carrier and service may vary."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="short_code",
            original_value=record.get("number"),
            normalized_value=row["example_number"],
            source_record_id=row["source_record_id"],
            source_url=record.get("sourceUrl") or context.source_url,
            verification=record.get("verification") or "REFERENCE",
            license_name="Apache-2.0 AND ODbL-1.0",
            license_url="https://opendatacommons.org/licenses/odbl/1.0/",
            transformations="Digits preserved; no E.164 conversion; category kept as published",
            source_updated_at=record.get("checkedAt"),
        )
