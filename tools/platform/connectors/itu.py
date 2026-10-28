"""Import ITU National Numbering Plan index metadata.

Only the index metadata and URLs are imported.  The linked Word/PDF documents
are not downloaded or redistributed by this connector.
"""

from __future__ import annotations

import json
import re
from typing import Any, Iterable, Mapping

from .. import paths
from .base import ImportContext
from .common import provenance_row, stable_id


def load_itu() -> dict[str, Any]:
    return json.loads(paths.ITU_SEED.read_text(encoding="utf-8"))


class ItuConnector:
    connector_id = "itu-nnp-metadata"
    connector_version = "1.0.0"
    source_id = "itu-nnp-index"
    entity_type = "numbering_plan"
    target_table = "numbering_plans"
    conflict_columns = ("plan_id",)

    def __init__(self, path=paths.ITU_SEED) -> None:
        self.path = path

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        for entry in payload.get("entries", []):
            yield {
                "kind": "country",
                "iso2": entry["iso2"],
                "name": entry["itu_name"],
                "url": entry["url"],
                "plan_id": f"itu-{entry['iso2'].lower()}",
                "source_id": f"itu-nnp-{entry['iso2'].lower()}",
            }
        for entry in payload.get("global_networks", []):
            slug = re.sub(r"[^a-z0-9]+", "-", entry["entity_id"].split(":", 1)[1].lower()).strip("-")
            yield {
                "kind": "global",
                "entity_id": entry["entity_id"],
                "name": entry["name_en"],
                "url": entry["itu_url"],
                "plan_id": f"itu-global-{slug}",
                "source_id": f"itu-global-{slug}",
            }

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors = []
        if record.get("kind") == "country" and not re.fullmatch(r"[A-Z]{2}", str(record.get("iso2", ""))):
            errors.append("invalid ISO2")
        if not record.get("name"):
            errors.append("missing plan name")
        if not str(record.get("url", "")).startswith("https://www.itu.int/"):
            errors.append("unexpected ITU URL")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        return {
            "plan_id": record["plan_id"],
            "country_iso2": record.get("iso2") or record["entity_id"],
            "plan_name": f"ITU NNP index entry: {record['name']}",
            "version": None,
            "effective_date": None,
            "source_id": record["source_id"],
            "source_release_id": None,
            "notes": (
                "ITU index metadata only. The linked document was not parsed or "
                "redistributed; the entry does not prove that any line is active."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="numbering_plan_index",
            original_value=record["url"],
            normalized_value=row["plan_id"],
            source_record_id=stable_id(record.get("iso2") or record.get("entity_id")),
            source_url=record["url"],
            source_id=record["source_id"],
            verification="OFFICIAL_INDEX",
            license_name="ITU terms of use",
            license_url="https://www.itu.int/en/copyright/Pages/default.aspx",
            transformations="Index URL and name recorded; no document download",
            retrieved_at=None,
        )
