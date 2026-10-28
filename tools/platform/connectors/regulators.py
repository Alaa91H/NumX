"""Import regulator discovery records from the Wikipedia index.

The Wikipedia table is a discovery index, not an official register.  Each row
is marked ``DISCOVERY`` with low confidence and points at the candidate
regulator URL.  Official ownership and current authority still need review.
"""

from __future__ import annotations

import json
import re
from typing import Any, Iterable, Mapping

from .. import paths
from .base import ImportContext
from .common import provenance_row, stable_id


def load_regulators() -> dict[str, Any]:
    return json.loads(paths.REGULATOR_SEED.read_text(encoding="utf-8"))


class RegulatorConnector:
    connector_id = "wikipedia-regulator-discovery"
    connector_version = "1.0.0"
    source_id = "wikipedia-regulators"
    entity_type = "regulator_entry"
    target_table = "regulator_entries"
    conflict_columns = ("country_iso2", "regulator_name")

    def __init__(self, path=paths.REGULATOR_SEED) -> None:
        self.path = path

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        seen: set[tuple[str, str]] = set()
        for entry in payload.get("entries", []):
            iso2 = entry.get("iso2")
            if not iso2:
                continue
            for index, regulator in enumerate(entry.get("regulators", [])):
                name = re.sub(r"\s+", " ", regulator.get("name") or "").strip()
                if not name:
                    continue
                key = (iso2, name.lower())
                if key in seen:
                    continue
                seen.add(key)
                urls = [url for url in regulator.get("urls", []) if url]
                source_id = f"regulator-{iso2.lower()}" if index == 0 else f"regulator-{iso2.lower()}-{index + 1}"
                yield {
                    "iso2": iso2,
                    "name": name,
                    "urls": urls,
                    "source_id": source_id,
                    "discovery_url": payload.get("source_url"),
                    "revision": payload.get("source_revision"),
                }

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors = []
        if not re.fullmatch(r"[A-Z]{2}", str(record.get("iso2", ""))):
            errors.append("invalid ISO2")
        if not record.get("name"):
            errors.append("missing regulator name")
        if not record.get("source_id"):
            errors.append("missing regulator source id")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        url = record["urls"][0] if record["urls"] else None
        return {
            "regulator_id": f"regulator-{record['iso2'].lower()}-{stable_id(record['name'], length=12)}",
            "country_iso2": record["iso2"],
            "regulator_name": record["name"],
            "official_url": url,
            "source_id": record["source_id"],
            "discovery_source_id": self.source_id,
            "verification": "DISCOVERY",
            "confidence": "low",
            "checked_at": None,
            "notes": (
                "Discovered from the Wikipedia telecommunications-regulator table. "
                "The name and URL are candidates; official ownership and current "
                "authority were not verified."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="regulator_name",
            original_value=record["name"],
            normalized_value=row["regulator_name"],
            source_record_id=stable_id(record["iso2"], record["name"]),
            source_url=record.get("discovery_url"),
            source_id=self.source_id,
            verification="DISCOVERY",
            license_name="CC BY-SA 4.0",
            license_url="https://creativecommons.org/licenses/by-sa/4.0/",
            transformations="Whitespace normalized; candidate URL kept unchanged",
            source_updated_at=record.get("revision"),
        )
