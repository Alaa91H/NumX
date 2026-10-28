"""Compute the coverage matrix from sources and imported records.

Coverage is deliberately conservative.  A row can be ``imported`` only when
records exist in the database for that entity/category.  A reachable index
page, a discovered regulator URL, or a metadata-only ITU document never counts
as imported coverage.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping

from . import db

EMERGENCY_SERVICES = (
    "EMERGENCY",
    "POLICE",
    "AMBULANCE",
    "FIRE",
    "MULTI",
    "GENERAL",
    "DISPATCH",
    "TRAFFIC",
    "FIRE_AMBULANCE",
)

CATEGORIES: dict[str, dict[str, str]] = {
    "numbering_plan": {"ar": "خطة الترقيم", "en": "Numbering plan"},
    "fixed_line": {"ar": "الهاتف الثابت", "en": "Fixed line"},
    "mobile": {"ar": "الهاتف المحمول", "en": "Mobile"},
    "short_codes_emergency": {"ar": "أرقام الطوارئ القصيرة", "en": "Emergency short codes"},
    "services": {"ar": "أرقام الخدمات", "en": "Service numbers"},
    "allocated_carrier": {"ar": "المشغل المخصص للنطاق", "en": "Allocated carrier"},
    "regulator": {"ar": "الجهة التنظيمية", "en": "Regulator"},
    "business_directory": {"ar": "دليل الأعمال", "en": "Business directory"},
    "government_directory": {"ar": "الدليل الحكومي", "en": "Government directory"},
    "reputation": {"ar": "السمعة والبلاغات", "en": "Reputation reports"},
    "satellite_networks": {"ar": "الشبكات الساتلية", "en": "Satellite networks"},
}

CATEGORY_ORDER = list(CATEGORIES)

BLOCKED_IMPORT_STATUSES = {
    "blocked_by_robots",
    "temporarily_unavailable",
    "blocked_license",
    "blocked_authentication",
}

UNAVAILABLE_LEGAL_STATUSES = {"prohibited"}


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _load_categories(raw: str | None) -> list[str]:
    try:
        value = json.loads(raw or "[]")
    except json.JSONDecodeError:
        return []
    return [item for item in value if isinstance(item, str)]


def _entity_sources(
    entity: Mapping[str, Any], sources: Iterable[Mapping[str, Any]]
) -> dict[str, list[Mapping[str, Any]]]:
    """Return applicable sources grouped by data category."""

    iso2 = entity.get("iso2")
    entity_id = entity["entity_id"]
    is_global = entity.get("entity_kind") == "global_network"
    grouped: dict[str, list[Mapping[str, Any]]] = {category: [] for category in CATEGORIES}
    for source in sources:
        categories = _load_categories(source.get("data_categories_json"))
        applies = False
        if is_global:
            applies = source.get("entity_id") == entity_id
        else:
            if source.get("country_iso2") == iso2 or source.get("entity_id") == entity_id:
                applies = True
            elif source.get("geographic_scope") == "global":
                applies = True
        if not applies:
            continue
        for category in categories:
            if category in grouped:
                grouped[category].append(source)
    return grouped


def _source_available(source: Mapping[str, Any]) -> bool:
    if source.get("import_status") in BLOCKED_IMPORT_STATUSES:
        return False
    if source.get("legal_review_status") in UNAVAILABLE_LEGAL_STATUSES:
        return False
    return True


def _source_blocked(source: Mapping[str, Any]) -> bool:
    return (
        source.get("import_status") in BLOCKED_IMPORT_STATUSES
        or source.get("legal_review_status") in UNAVAILABLE_LEGAL_STATUSES
    )


def _range_where(category: str) -> tuple[str, list[Any]]:
    if category == "fixed_line":
        return "line_type='FIXED_LINE'", []
    if category == "mobile":
        return "line_type='MOBILE'", []
    if category == "short_codes_emergency":
        placeholders = ",".join("?" for _ in EMERGENCY_SERVICES)
        return f"line_type='SHORT_CODE' AND service_type IN ({placeholders})", list(EMERGENCY_SERVICES)
    if category == "services":
        placeholders = ",".join("?" for _ in EMERGENCY_SERVICES)
        return f"line_type='SHORT_CODE' AND service_type NOT IN ({placeholders})", list(EMERGENCY_SERVICES)
    raise KeyError(category)


def _count_with_provenance(
    connection: Any,
    *,
    table: str,
    id_column: str,
    entity_type: str,
    where: str,
    values: list[Any],
) -> tuple[int, int, str | None]:
    total = int(
        connection.execute(
            f"SELECT COUNT(*) FROM {table} WHERE {where}", values
        ).fetchone()[0]
    )
    if total == 0:
        return 0, 0, None
    with_provenance = int(
        connection.execute(
            f"""
            SELECT COUNT(*) FROM {table} t
            WHERE {where}
              AND EXISTS(
                SELECT 1 FROM record_provenance p
                WHERE p.entity_type=?
                  AND p.entity_id=CAST(t.{id_column} AS TEXT)
              )
            """,
            [*values, entity_type],
        ).fetchone()[0]
    )
    latest = connection.execute(
        f"""
        SELECT MAX(p.retrieved_at) FROM {table} t
        JOIN record_provenance p
          ON p.entity_type=? AND p.entity_id=CAST(t.{id_column} AS TEXT)
        WHERE {where}
        """,
        [entity_type, *values],
    ).fetchone()[0]
    return total, with_provenance, latest


def record_counts(
    connection: Any, entity: Mapping[str, Any]
) -> dict[str, tuple[int, int, str | None]]:
    """Return ``{category: (records, records_with_provenance, latest)}``."""

    iso2 = entity.get("iso2")
    entity_id = entity["entity_id"]
    counts: dict[str, tuple[int, int, str | None]] = {}

    if entity.get("entity_kind") == "global_network":
        plan_where = "country_iso2=?"
        plan_values: list[Any] = [entity_id]
    else:
        plan_where = "country_iso2=?"
        plan_values = [iso2]
    counts["numbering_plan"] = _count_with_provenance(
        connection,
        table="numbering_plans",
        id_column="plan_id",
        entity_type="numbering_plan",
        where=plan_where,
        values=plan_values,
    )
    for category in ("fixed_line", "mobile", "short_codes_emergency", "services"):
        where, values = _range_where(category)
        counts[category] = _count_with_provenance(
            connection,
            table="number_ranges",
            id_column="range_id",
            entity_type="number_range",
            where=f"country_iso2=? AND {where}",
            values=[iso2, *values],
        )
    counts["allocated_carrier"] = _count_with_provenance(
        connection,
        table="carrier_observations",
        id_column="observation_id",
        entity_type="carrier_observation",
        where="country_iso2=?",
        values=[iso2],
    )
    counts["regulator"] = _count_with_provenance(
        connection,
        table="regulator_entries",
        id_column="regulator_id",
        entity_type="regulator_entry",
        where="country_iso2=?",
        values=[iso2],
    )
    counts["business_directory"] = _count_with_provenance(
        connection,
        table="directory_entries",
        id_column="entry_id",
        entity_type="directory_entry",
        where="country_iso2=? AND category='business'",
        values=[iso2],
    )
    counts["government_directory"] = _count_with_provenance(
        connection,
        table="directory_entries",
        id_column="entry_id",
        entity_type="directory_entry",
        where="country_iso2=? AND category='government'",
        values=[iso2],
    )
    counts["reputation"] = _count_with_provenance(
        connection,
        table="reputation_reports",
        id_column="report_id",
        entity_type="reputation_report",
        where="country_iso2=?",
        values=[iso2],
    )
    counts["satellite_networks"] = (0, 0, None)
    return counts


def _days_since(value: str | None) -> int | None:
    if not value:
        return None
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return max(0, (datetime.now(timezone.utc) - moment).days)


def _reason(
    category: str,
    status: str,
    *,
    records: int,
    provenance_ratio: float | None,
    sources: list[Mapping[str, Any]],
    blocked: int,
) -> tuple[str, str]:
    label_ar = CATEGORIES[category]["ar"]
    label_en = CATEGORIES[category]["en"]
    names = ", ".join(sorted({source["source_name"] for source in sources}))[:240]
    if status == "imported":
        ratio = "" if provenance_ratio is None else f"{provenance_ratio * 100:.1f}%"
        return (
            f"استُوردت {records} سجلات في فئة {label_ar} من: {names}. "
            f"تغطية الأصل {ratio}. البيانات مرجعية ولا تثبت نشاط أي خط.",
            f"Imported {records} {label_en.lower()} records from: {names}. "
            f"Provenance coverage {ratio}. Reference data does not prove line activity.",
        )
    if status == "partial":
        ratio = "" if provenance_ratio is None else f"{provenance_ratio * 100:.1f}%"
        return (
            f"استُوردت {records} سجلات في فئة {label_ar}، لكن تغطية الأصل {ratio} أقل من الحد المطلوب.",
            f"Imported {records} {label_en.lower()} records, but provenance coverage {ratio} is below the required threshold.",
        )
    if status == "metadata_only":
        return (
            f"سُجلت بيانات وصفية ورابط مصدر لفئة {label_ar}، دون استيراد سجلات على مستوى النطاق.",
            f"Source metadata and a URL are recorded for {label_en.lower()}, but no range-level records were imported.",
        )
    if status == "source_identified":
        return (
            f"وُجد مصدر مرشح لفئة {label_ar} ({names})، لكن لم تُستورد منه سجلات لهذا الكيان.",
            f"A candidate source exists for {label_en.lower()} ({names}), but no records were imported for this entity.",
        )
    if status == "blocked":
        return (
            f"كل المصادر المكتشفة لفئة {label_ar} محجوبة أو غير متاحة ({blocked} مصدر)، ولم يُستورد منها شيء.",
            f"All discovered {label_en.lower()} sources are blocked or unavailable ({blocked} source(s)); nothing was imported.",
        )
    return (
        f"لم يُعثر على مصدر مسموح لفئة {label_ar} لهذا الكيان.",
        f"No permitted source was identified for {label_en.lower()} for this entity.",
    )


def compute_coverage(connection: Any) -> list[dict[str, Any]]:
    entities = [dict(row) for row in connection.execute("SELECT * FROM countries_and_regions ORDER BY entity_id")]
    sources = [dict(row) for row in connection.execute("SELECT * FROM source_registry")]
    rows: list[dict[str, Any]] = []
    for entity in entities:
        grouped = _entity_sources(entity, sources)
        counts = record_counts(connection, entity)
        for category in CATEGORY_ORDER:
            applicable = grouped.get(category, [])
            available = [source for source in applicable if _source_available(source)]
            blocked = [source for source in applicable if _source_blocked(source)]
            records, with_provenance, latest = counts.get(category, (0, 0, None))
            ratio = (with_provenance / records) if records else None
            if records > 0:
                status = "imported" if ratio is None or ratio >= 0.90 else "partial"
            elif available:
                if all(source.get("import_status") == "metadata_only" for source in available):
                    status = "metadata_only"
                else:
                    status = "source_identified"
            elif blocked:
                status = "blocked"
            else:
                status = "missing"
            reason_ar, reason_en = _reason(
                category,
                status,
                records=records,
                provenance_ratio=ratio,
                sources=available or blocked,
                blocked=len(blocked),
            )
            last_verified = latest
            if last_verified is None and applicable:
                checked = [source.get("last_checked_at") for source in applicable if source.get("last_checked_at")]
                last_verified = max(checked) if checked else None
            rows.append(
                {
                    "country_iso2": entity.get("iso2") or entity["entity_id"],
                    "data_category": category,
                    "status": status,
                    "source_count": len(applicable),
                    "imported_record_count": records,
                    "provenance_coverage": ratio,
                    "freshness_days": _days_since(last_verified),
                    "reason_ar": reason_ar,
                    "reason_en": reason_en,
                    "last_verified_at": last_verified,
                }
            )
    return rows


def write_coverage(connection: Any) -> dict[str, Any]:
    rows = compute_coverage(connection)
    with connection:
        connection.execute("DELETE FROM coverage_matrix")
        for row in rows:
            db.upsert(connection, "coverage_matrix", row, ("country_iso2", "data_category"))
        db.audit(
            connection,
            "coverage.computed",
            details={"rows": len(rows)},
        )
    summary: dict[str, dict[str, int]] = {}
    for row in rows:
        summary.setdefault(row["data_category"], {})
        summary[row["data_category"]][row["status"]] = (
            summary[row["data_category"]].get(row["status"], 0) + 1
        )
    return {"rows": len(rows), "by_category": summary}


def coverage_for_iso(connection: Any, iso2: str) -> list[dict[str, Any]]:
    return [
        dict(row)
        for row in connection.execute(
            "SELECT * FROM coverage_matrix WHERE country_iso2=? ORDER BY data_category",
            (iso2.upper(),),
        )
    ]
