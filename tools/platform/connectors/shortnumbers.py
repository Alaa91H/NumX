"""Import libphonenumber short-code and service-number patterns.

Short codes are not E.164 numbers.  They keep their national form, including
leading zeros, and are never padded or prefixed to look international.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import Any, Iterable, Mapping

from .. import paths
from .base import ImportContext
from .common import entity_iso_set, provenance_row, stable_id

TAG_TO_SERVICE = {
    "shortCode": "SHORT_CODE",
    "tollFree": "TOLL_FREE",
    "premiumRate": "PREMIUM_RATE",
    "sharedCost": "SHARED_COST",
    "standardRate": "STANDARD_RATE",
    "carrier": "CARRIER",
    "smsShortCode": "SMS_SHORT_CODE",
    "emergency": "EMERGENCY",
}


def _text(element: ET.Element | None) -> str:
    if element is None or element.text is None:
        return ""
    return re.sub(r"\s+", "", element.text)


def parse_short_metadata(path=paths.SHORT_METADATA) -> list[dict[str, Any]]:
    root = ET.parse(path).getroot()
    territories = root.find("territories")
    if territories is None:
        return []
    records: list[dict[str, Any]] = []
    for territory in territories.findall("territory"):
        iso2 = territory.get("id")
        if not iso2:
            continue
        for child in territory:
            service = TAG_TO_SERVICE.get(child.tag)
            if service is None:
                continue
            pattern = _text(child.find("nationalNumberPattern"))
            example = _text(child.find("exampleNumber"))
            if not pattern:
                continue
            possible = child.find("possibleLengths")
            lengths = possible.get("national") if possible is not None else None
            records.append(
                {
                    "iso2": iso2,
                    "service_type": service,
                    "pattern": pattern,
                    "example_number": example or None,
                    "possible_lengths": lengths,
                }
            )
    return records


class ShortNumberConnector:
    connector_id = "libphonenumber-short-numbers"
    connector_version = "1.0.0"
    source_id = "libphonenumber-short"
    entity_type = "number_range"
    target_table = "number_ranges"
    conflict_columns = ("source_id", "source_record_id")

    def __init__(self, path=paths.SHORT_METADATA) -> None:
        self.path = path

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        wanted = entity_iso_set(context.connection)
        seen: set[tuple[str, str, str]] = set()
        for record in parse_short_metadata(self.path):
            if record["iso2"] not in wanted:
                continue
            key = (record["iso2"], record["service_type"], record["pattern"])
            if key in seen:
                continue
            seen.add(key)
            yield record

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors = []
        if not re.fullmatch(r"[A-Z]{2}", str(record.get("iso2", ""))):
            errors.append("invalid ISO2")
        if not record.get("pattern"):
            errors.append("missing pattern")
        example = str(record.get("example_number") or "")
        if example and not re.fullmatch(r"\d{1,12}", example):
            errors.append("short-code example is not a short national number")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        iso2 = record["iso2"]
        source_record_id = f"{iso2}:{record['service_type']}:{stable_id(record['pattern'])}"
        example = record.get("example_number") or ""
        return {
            "plan_id": f"libphonenumber-{iso2.lower()}",
            "country_iso2": iso2,
            "line_type": "SHORT_CODE",
            "service_type": record["service_type"],
            "prefix": example[:4] if example else None,
            "pattern": record["pattern"],
            "range_start": None,
            "range_end": None,
            "example_number": example or None,
            "national_prefix": None,
            "source_id": self.source_id,
            "source_record_id": source_record_id,
            "source_release_id": context.source_release_id,
            "valid_from": None,
            "valid_to": None,
            "verification": "REFERENCE",
            "confidence": "medium",
            "notes": (
                "Short-code pattern. It is not an E.164 number and may vary by "
                "carrier and service."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="short_code_range",
            original_value=record["pattern"],
            normalized_value=f"{record['service_type']}:{record['pattern']}",
            source_record_id=row["source_record_id"],
            source_url=context.source_url,
            verification="REFERENCE",
            license_name="Apache-2.0",
            license_url="https://github.com/google/libphonenumber/blob/master/LICENSE",
            transformations="XML whitespace removed; leading zeros preserved; no E.164 conversion",
        )
