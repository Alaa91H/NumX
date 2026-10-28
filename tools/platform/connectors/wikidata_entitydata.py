"""Import a bounded sample of public organization records from Wikidata.

The connector reads a seed produced by ``discovery.py``.  Each seed row was
found through an allowed Wikipedia page and then fetched from the allowed
``Special:EntityData`` JSON endpoint.  Wikidata is community-curated: a phone
number may be old, generic, or belong to a switchboard.
"""

from __future__ import annotations

import json
import re
from typing import Any, Iterable, Mapping

from .. import paths
from ..normalize import parse_phone
from .base import ImportContext
from .common import provenance_row

WIKIDATA_SEED = paths.SEED_DIR / "wikidata_entities.json"


class WikidataDirectoryConnector:
    connector_id = "wikidata-entitydata-directory"
    connector_version = "1.0.0"
    source_id = "wikidata-entitydata"
    entity_type = "directory_entry"
    target_table = "directory_entries"
    conflict_columns = ("source_id", "source_record_id", "phone_number")

    def __init__(self, path=WIKIDATA_SEED) -> None:
        self.path = path

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        if not self.path.exists():
            return
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        wanted = context.countries
        for entry in payload.get("entries", []):
            iso2 = entry.get("country_iso2")
            if wanted and iso2 not in wanted:
                continue
            if not entry.get("phone"):
                continue
            yield entry

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors = []
        if not re.fullmatch(r"Q\d+", str(record.get("qid", ""))):
            errors.append("invalid Wikidata QID")
        if not record.get("name"):
            errors.append("missing organization name")
        if not re.fullmatch(r"[A-Z]{2}", str(record.get("country_iso2", ""))):
            errors.append("invalid country ISO2")
        if not re.fullmatch(r"[+0-9()\-\s./]{3,40}", str(record.get("phone", ""))):
            errors.append("invalid public phone format")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        normalized = parse_phone(record["phone"], record["country_iso2"])
        return {
            "country_iso2": record["country_iso2"],
            "organization_name": record["name"],
            "phone_number": record["phone"],
            "e164_number": normalized.e164,
            "address": record.get("address"),
            "website": record.get("website"),
            "category": record.get("category") or "business",
            "latitude": record.get("latitude"),
            "longitude": record.get("longitude"),
            "source_id": self.source_id,
            "source_record_id": record["qid"],
            "source_release_id": None,
            "source_updated_at": record.get("modified"),
            "imported_at": context.connection.execute("SELECT datetime('now')").fetchone()[0],
            "license_name": "CC0-1.0",
            "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
            "verification": "COMMUNITY_CURATED",
            "confidence": "low",
            "limitations": (
                "Community-curated public organization contact data. It may be "
                "stale, generic, or belong to a switchboard; it is not subscriber "
                "identity and does not prove that a line is active."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="organization_phone",
            original_value=record["phone"],
            normalized_value=row.get("e164_number") or row["phone_number"],
            source_record_id=record["qid"],
            source_url=record.get("entity_url"),
            verification="COMMUNITY_CURATED",
            license_name="CC0-1.0",
            license_url="https://creativecommons.org/publicdomain/zero/1.0/",
            transformations="Phone kept as published; E.164 only when libphonenumber validates it",
            source_updated_at=record.get("modified"),
        )
