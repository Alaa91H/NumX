"""Import libphonenumber national number-range patterns.

The XML is the pinned local file recorded in ``sources.lock.json``.  It is a
reference implementation, not a national numbering authority.  Patterns are
stored as published; the connector never expands a pattern into individual
subscriber numbers.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import Any, Iterable, Mapping

from .. import paths
from .base import ImportContext
from .common import entity_iso_set, provenance_row, stable_id

TAG_TO_LINE_TYPE = {
    "fixedLine": "FIXED_LINE",
    "mobile": "MOBILE",
    "tollFree": "TOLL_FREE",
    "premiumRate": "PREMIUM_RATE",
    "sharedCost": "SHARED_COST",
    "personalNumber": "PERSONAL_NUMBER",
    "voip": "VOIP",
    "pager": "PAGER",
    "uan": "UAN",
    "voicemail": "VOICEMAIL",
}


def _text(element: ET.Element | None) -> str:
    if element is None or element.text is None:
        return ""
    return re.sub(r"\s+", "", element.text)


def parse_metadata(path=paths.PHONE_METADATA) -> list[dict[str, Any]]:
    root = ET.parse(path).getroot()
    territories = root.find("territories")
    if territories is None:
        return []
    records: list[dict[str, Any]] = []
    for territory in territories.findall("territory"):
        iso2 = territory.get("id")
        if not iso2:
            continue
        national_prefix = territory.get("nationalPrefix")
        for child in territory:
            line_type = TAG_TO_LINE_TYPE.get(child.tag)
            if line_type is None:
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
                    "line_type": line_type,
                    "pattern": pattern,
                    "example_number": example or None,
                    "possible_lengths": lengths,
                    "national_prefix": national_prefix,
                    "territory": territory.get("countryCode"),
                    "original_xml": ET.tostring(child, encoding="unicode"),
                }
            )
    return records


class LibphoneNumberConnector:
    connector_id = "libphonenumber-ranges"
    connector_version = "1.0.0"
    source_id = "libphonenumber-metadata"
    entity_type = "number_range"
    target_table = "number_ranges"
    conflict_columns = ("source_id", "source_record_id")

    def __init__(self, path=paths.PHONE_METADATA) -> None:
        self.path = path

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        wanted = entity_iso_set(context.connection)
        seen: set[tuple[str, str, str]] = set()
        for record in parse_metadata(self.path):
            if record["iso2"] not in wanted:
                continue
            key = (record["iso2"], record["line_type"], record["pattern"])
            if key in seen:
                continue
            seen.add(key)
            yield record

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors: list[str] = []
        if not re.fullmatch(r"[A-Z]{2}", str(record.get("iso2", ""))):
            errors.append("invalid ISO2")
        if not record.get("pattern"):
            errors.append("missing national number pattern")
        if record.get("example_number") and not re.fullmatch(r"\d{2,15}", str(record["example_number"])):
            errors.append("invalid example number")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        iso2 = record["iso2"]
        source_record_id = f"{iso2}:{record['line_type']}:{stable_id(record['pattern'])}"
        example = record.get("example_number") or ""
        prefix = example[:5] if example else None
        return {
            "plan_id": f"libphonenumber-{iso2.lower()}",
            "country_iso2": iso2,
            "line_type": record["line_type"],
            "service_type": record["line_type"],
            "prefix": prefix,
            "pattern": record["pattern"],
            "range_start": None,
            "range_end": None,
            "example_number": example or None,
            "national_prefix": record.get("national_prefix"),
            "source_id": self.source_id,
            "source_record_id": source_record_id,
            "source_release_id": context.source_release_id,
            "valid_from": None,
            "valid_to": None,
            "verification": "REFERENCE",
            "confidence": "medium",
            "notes": (
                "libphonenumber reference pattern; may cover several ranges and "
                "does not prove that a line is active."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="range",
            original_value=record["pattern"],
            normalized_value=f"{record['line_type']}:{record['pattern']}",
            source_record_id=row["source_record_id"],
            source_url=context.source_url,
            verification="REFERENCE",
            license_name="Apache-2.0",
            license_url="https://github.com/google/libphonenumber/blob/master/LICENSE",
            transformations="XML nationalNumberPattern whitespace removed; no range expansion",
            retrieved_at=context.connection.execute(
                "SELECT fetched_at FROM source_releases WHERE release_id=?",
                (context.source_release_id,),
            ).fetchone()[0]
            if context.source_release_id
            else None,
        )
