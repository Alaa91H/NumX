"""Build the platform summary consumed by the API, docs, and the Android app."""

from __future__ import annotations

import json
from datetime import date
from typing import Any, Mapping

from . import db, paths

TOOL_VERSION = "1.0.0"
APP_SUMMARY_VERSION = 1

FIELD_SOURCES = {
    "numbering": "libphonenumber-metadata",
    "officialIndex": None,  # per-country ITU source
    "regulator": None,  # per-country regulator source
    "emergency": "numx-emergency-reference",
    "carrier": "libphonenumber-carrier",
}

CONFIDENCE = {
    "libphonenumber-metadata": ("medium", "Reference implementation metadata; not a national authority and does not prove line activity."),
    "libphonenumber-short": ("medium", "Reference short-code patterns; carrier and service variation is possible."),
    "libphonenumber-carrier": ("low", "Allocated-prefix reference only; mobile number portability can change the current carrier."),
    "numx-emergency-reference": ("medium", "Curated reference list derived from public sources; not confirmed with every national authority."),
    "itu-nnp-index": ("medium", "Official ITU index entry; linked documents were not parsed or redistributed."),
    "wikipedia-regulators": ("low", "Discovery index only; official ownership and current authority were not verified."),
    "wikidata-entitydata": ("low", "Community-curated public organization data; may be stale or refer to a switchboard."),
}


def _load_json(raw: str | None, default: Any) -> Any:
    try:
        return json.loads(raw or "")
    except (json.JSONDecodeError, TypeError):
        return default


def _source_map(connection: Any) -> dict[str, dict[str, Any]]:
    sources = {}
    for row in connection.execute("SELECT * FROM source_registry"):
        source = dict(row)
        source["data_categories"] = _load_json(source.pop("data_categories_json"), [])
        sources[source["source_id"]] = source
    return sources


def _coverage_map(connection: Any) -> dict[str, dict[str, dict[str, Any]]]:
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for row in connection.execute("SELECT * FROM coverage_matrix ORDER BY country_iso2, data_category"):
        item = dict(row)
        result.setdefault(item["country_iso2"], {})[item["data_category"]] = {
            "status": item["status"],
            "records": item["imported_record_count"],
            "provenance": item["provenance_coverage"],
            "freshnessDays": item["freshness_days"],
            "reasonAr": item["reason_ar"],
            "reasonEn": item["reason_en"],
        }
    return result


def _field_payload(
    source_id: str | None,
    sources: Mapping[str, Mapping[str, Any]],
    *,
    fallback_name: str = "",
) -> dict[str, Any]:
    source = sources.get(source_id or "")
    if source is None:
        return {
            "sourceId": source_id,
            "sourceName": fallback_name,
            "available": False,
            "confidence": "low",
            "confidenceReason": "No imported source is attached to this field.",
        }
    confidence, reason = CONFIDENCE.get(
        source["source_id"],
        ("medium" if source.get("verification_status") not in ("discovery_only", "unverified") else "low", ""),
    )
    if not reason:
        reason = source.get("known_limitations") or "Confidence follows the recorded verification status."
    return {
        "sourceId": source["source_id"],
        "sourceName": source["source_name"],
        "owner": source["source_owner"],
        "url": source["source_url"],
        "license": source["license_name"],
        "licenseUrl": source.get("license_url"),
        "redistribution": source["redistribution_allowed"],
        "verification": source["verification_status"],
        "importStatus": source["import_status"],
        "lastChecked": source.get("last_checked_at"),
        "sourceRelease": source.get("source_release_date"),
        "limitations": source.get("known_limitations") or "",
        "confidence": confidence,
        "confidenceReason": reason,
        "available": True,
    }


def _country_payload(
    entity: Mapping[str, Any],
    sources: Mapping[str, Mapping[str, Any]],
    coverage: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    iso2 = entity.get("iso2") or ""
    itu_id = f"itu-nnp-{iso2.lower()}" if iso2 else None
    regulator_id = f"regulator-{iso2.lower()}" if iso2 else None
    fields = {
        "numbering": _field_payload(FIELD_SOURCES["numbering"], sources),
        "officialIndex": _field_payload(itu_id, sources, fallback_name="ITU NNP index"),
        "regulator": _field_payload(regulator_id, sources, fallback_name="Regulator discovery"),
        "emergency": _field_payload(FIELD_SOURCES["emergency"], sources),
        "carrier": _field_payload(FIELD_SOURCES["carrier"], sources),
    }
    return {
        "entityId": entity["entity_id"],
        "iso2": iso2 or None,
        "nameEn": entity["name_en"],
        "nameAr": entity["name_ar"],
        "callingCode": entity.get("calling_code"),
        "coverage": coverage.get(iso2 or entity["entity_id"], {}),
        "fields": fields,
        "noteAr": entity.get("notes_ar") or "",
    }


def build_summary(connection: Any, *, include_sources: bool = True) -> dict[str, Any]:
    sources = _source_map(connection)
    coverage = _coverage_map(connection)
    entities = [dict(row) for row in connection.execute("SELECT * FROM countries_and_regions ORDER BY entity_id")]
    countries = [entity for entity in entities if entity.get("entity_kind") != "global_network"]
    networks = [entity for entity in entities if entity.get("entity_kind") == "global_network"]

    imported_records = sum(
        db.table_count(connection, table)
        for table in (
            "numbering_plans",
            "number_ranges",
            "phone_numbers",
            "directory_entries",
            "carrier_observations",
            "reputation_reports",
            "regulator_entries",
        )
    )
    job_rows = [dict(row) for row in connection.execute("SELECT * FROM import_jobs ORDER BY started_at")]
    rejected = sum(int(row["records_rejected"] or 0) for row in job_rows)
    status_counts: dict[str, int] = {}
    category_status: dict[str, dict[str, int]] = {}
    for row in connection.execute("SELECT data_category, status, COUNT(*) AS n FROM coverage_matrix GROUP BY data_category, status"):
        status_counts[row["status"]] = status_counts.get(row["status"], 0) + row["n"]
        category_status.setdefault(row["data_category"], {})[row["status"]] = row["n"]

    payload: dict[str, Any] = {
        "schemaVersion": db.SCHEMA_VERSION,
        "summaryVersion": APP_SUMMARY_VERSION,
        "toolVersion": TOOL_VERSION,
        "generatedAt": date.today().isoformat(),
        "counts": {
            "countriesAndTerritories": len(countries),
            "globalNetworks": len(networks),
            "sources": len(sources),
            "importedRecords": imported_records,
            "numberingPlans": db.table_count(connection, "numbering_plans"),
            "numberRanges": db.table_count(connection, "number_ranges"),
            "carrierObservations": db.table_count(connection, "carrier_observations"),
            "regulatorEntries": db.table_count(connection, "regulator_entries"),
            "directoryEntries": db.table_count(connection, "directory_entries"),
            "reputationReports": db.table_count(connection, "reputation_reports"),
            "provenanceRows": db.table_count(connection, "record_provenance"),
            "provenanceHistoryRows": db.table_count(connection, "provenance_history"),
            "importJobs": len(job_rows),
            "rejectedRecords": rejected,
        },
        "coverage": {
            "byStatus": status_counts,
            "byCategory": category_status,
        },
        "countries": {
            entity.get("iso2") or entity["entity_id"]: _country_payload(entity, sources, coverage)
            for entity in countries
        },
        "globalNetworks": {
            entity["entity_id"]: _country_payload(entity, sources, coverage)
            for entity in networks
        },
        "blockedSources": [
            "wikidata-sparql",
            "osm-overpass",
            "osm-bulk",
            "phoneblock",
            "fcc-opendata",
            "ftc-dnc",
        ],
        "limitations": [
            "Numbering-plan validity is separate from line activity.",
            "Allocated carrier is the original prefix, not the current carrier after portability.",
            "Regulator entries are discovery records; official ownership was not verified.",
            "No subscriber identity, activation state, or private contact data is included.",
            "Full independent local-language source research for 252 countries is not complete.",
            "Reputation reports are unavailable (PhoneBlock blocked/restricted).",
        ],
    }
    if include_sources:
        payload["sources"] = [
            {
                "sourceId": source["source_id"],
                "sourceName": source["source_name"],
                "owner": source["source_owner"],
                "countryIso2": source.get("country_iso2"),
                "category": source["source_category"],
                "dataCategories": source["data_categories"],
                "url": source["source_url"],
                "accessMethod": source["access_method"],
                "license": source["license_name"],
                "licenseUrl": source.get("license_url"),
                "redistribution": source["redistribution_allowed"],
                "commercialUse": source["commercial_use_allowed"],
                "official": bool(source["official_source"]),
                "verification": source["verification_status"],
                "legalReview": source["legal_review_status"],
                "importStatus": source["import_status"],
                "lastChecked": source.get("last_checked_at"),
                "lastHttpStatus": source.get("last_http_status"),
                "limitations": source.get("known_limitations") or "",
            }
            for source in sorted(sources.values(), key=lambda item: item["source_id"])
        ]
    return payload


def write_summary(connection: Any) -> dict[str, str]:
    paths.ensure_dirs()
    payload = build_summary(connection, include_sources=True)
    paths.SUMMARY_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")

    app_payload = build_summary(connection, include_sources=False)
    app_payload["metadataVersion"] = f"platform-{TOOL_VERSION}"
    app_payload["noteAr"] = (
        "ملخص تغطية ومصادر يُقرأ دون إنترنت. لا يحتوي على بيانات مشتركين "
        "ولا يثبت نشاط أي خط."
    )
    app_payload["noteEn"] = (
        "Offline coverage and source summary. It contains no subscriber data "
        "and does not prove that any line is active."
    )
    app_payload["blockedSources"] = [
        "wikidata-sparql",
        "osm-overpass",
        "osm-bulk",
        "phoneblock",
        "fcc-opendata",
        "ftc-dnc",
    ]
    app_payload["limitations"] = [
        "Numbering-plan validity is separate from line activity.",
        "Allocated carrier is the original prefix, not the current carrier after portability.",
        "Regulator entries are discovery records; official ownership was not verified.",
        "No subscriber identity, activation state, or private contact data is included.",
        "Full independent local-language source research for 252 countries is not complete.",
        "Reputation reports are unavailable (PhoneBlock blocked/restricted).",
    ]
    paths.APP_PLATFORM_SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    paths.APP_PLATFORM_SUMMARY.write_text(
        json.dumps(app_payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    return {"summary": str(paths.SUMMARY_PATH), "app_summary": str(paths.APP_PLATFORM_SUMMARY)}
