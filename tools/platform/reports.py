"""Generate one source-research report per country, territory, and network."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Iterable, Mapping

from . import db, paths
from .discovery import CATEGORY_TEMPLATES, WIKIDATA_COUNTRIES, _category_url

COUNTRY_NAMES = {iso2: name for iso2, name in WIKIDATA_COUNTRIES}


def _slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")


def _load_json(path: Path) -> Any:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def _link_results() -> dict[str, list[dict[str, Any]]]:
    payload = _load_json(paths.CATALOG_DIR / "link-check-results.json")
    grouped: dict[str, list[dict[str, Any]]] = {}
    for item in payload.get("results", []):
        grouped.setdefault(item["sourceId"], []).append(item)
    return grouped


def _source_categories(source: Mapping[str, Any]) -> list[str]:
    try:
        return json.loads(source.get("data_categories_json") or "[]")
    except json.JSONDecodeError:
        return []


def _applies(source: Mapping[str, Any], entity: Mapping[str, Any]) -> bool:
    if entity.get("entity_kind") == "global_network":
        return source.get("entity_id") == entity["entity_id"]
    return (
        source.get("country_iso2") == entity.get("iso2")
        or source.get("entity_id") == entity["entity_id"]
        or source.get("geographic_scope") == "global"
    )


def _report(
    entity: Mapping[str, Any],
    sources: list[Mapping[str, Any]],
    coverage: list[Mapping[str, Any]],
    links: Mapping[str, list[dict[str, Any]]],
    counts: Mapping[str, int],
) -> str:
    iso2 = entity.get("iso2")
    name_en = entity["name_en"]
    name_ar = entity["name_ar"]
    title = f"{name_en} ({iso2})" if iso2 else name_en
    lines = [
        f"# {title}",
        "",
        f"- الاسم بالعربية: {name_ar}",
        f"- رمز الاتصال: {entity.get('calling_code') or '—'}",
        f"- نوع الكيان: `{entity.get('entity_kind')}`",
        f"- مرجع الإدراج: {('ITU National Numbering Plans index' if entity.get('itu_nnp_url') else 'NumX app metadata')}",
        f"- رابط ITU: {entity.get('itu_nnp_url') or '—'}",
        f"- الجهة التنظيمية (اكتشاف): {entity.get('regulator_name') or '—'}",
        f"- رابط الجهة (اكتشاف): {entity.get('regulator_url') or '—'}",
        "",
        "> هذا التقرير يسجل ما تم اكتشافه واختباره فعلاً. وجود رابط أو إدخال "
        "فهرس لا يثبت أن الخطة حديثة أو أن الموقع مملوك للجهة الرسمية، ولا "
        "يثبت نشاط أي خط هاتفي.",
        "",
        "## الاستعلامات والطلبات المنفذة",
        "",
        "- طلب فهرس ITU National Numbering Plans: <https://www.itu.int/ITU-T/inr/nnp/index.html>",
        "- طلب جدول Wikipedia لاكتشاف الجهات التنظيمية: <https://en.wikipedia.org/wiki/List_of_telecommunications_regulatory_bodies>",
    ]
    if iso2 in COUNTRY_NAMES:
        for template, category in CATEGORY_TEMPLATES:
            lines.append(
                f"- طلب صفحة تصنيف Wikipedia ({category}): <{_category_url(template.format(name=COUNTRY_NAMES[iso2]))}>"
            )
        lines.append(
            "- طلب Wikidata EntityData للكيانات التي عُثر على معرفها في الصفحات المسموحة."
        )
    else:
        lines.append(
            "- لم يُجرَ بحث Wikidata مؤسسي لهذا الكيان؛ العينة المؤسسية محدودة بدول مختارة."
        )
    lines.extend(
        [
            "",
            "## المصادر المكتشفة",
            "",
            "| المصدر | الفئة | الرابط | الوصول | الترخيص | التحقق | حالة الاستيراد | آخر فحص |",
            "|---|---|---|---|---|---|---|---|",
        ]
    )
    if not sources:
        lines.append("| — | — | — | — | — | — | — | — |")
    for source in sorted(sources, key=lambda item: item["source_id"]):
        lines.append(
            "| {name} (`{sid}`) | {category} | {url} | {access} | {license} | {verification} | {import_status} | {checked} |".format(
                name=source["source_name"].replace("|", "/"),
                sid=source["source_id"],
                category=source["source_category"],
                url=source["source_url"],
                access=source["access_method"],
                license=source["license_name"],
                verification=source["verification_status"],
                import_status=source["import_status"],
                checked=source.get("last_checked_at") or "—",
            )
        )

    lines.extend(
        [
            "",
            "## روابط تم اختبارها",
            "",
            "| الرابط | الحقل | النتيجة | HTTP | وقت الفحص |",
            "|---|---|---|---|---|",
        ]
    )
    checked_any = False
    for source in sources:
        for result in links.get(source["source_id"], []):
            checked_any = True
            lines.append(
                f"| {result['url']} | {result['field']} | {result['outcome']} | {result.get('httpStatus') or '—'} | {result['checkedAt']} |"
            )
    if not checked_any:
        lines.append("| — | — | لم يُشغّل فحص الروابط بعد | — | — |")

    lines.extend(
        [
            "",
            "## التغطية المستوردة",
            "",
            "| الفئة | الحالة | السجلات | تغطية الأصل | العمر (أيام) | المبرر |",
            "|---|---|---|---|---|---|",
        ]
    )
    for row in coverage:
        lines.append(
            "| {cat} | {status} | {records} | {prov} | {age} | {reason} |".format(
                cat=row["data_category"],
                status=row["status"],
                records=row["imported_record_count"],
                prov="—" if row["provenance_coverage"] is None else f"{row['provenance_coverage'] * 100:.1f}%",
                age="—" if row["freshness_days"] is None else row["freshness_days"],
                reason=(row["reason_ar"] or "").replace("|", "/"),
            )
        )

    lines.extend(
        [
            "",
            "## أعداد السجلات المرتبطة",
            "",
            f"- خطط الترقيم: {counts.get('numbering_plans', 0)}",
            f"- نطاقات الأرقام: {counts.get('number_ranges', 0)}",
            f"- ملاحظات المشغل المخصص: {counts.get('carrier_observations', 0)}",
            f"- سجلات الجهات التنظيمية: {counts.get('regulator_entries', 0)}",
            f"- سجلات الأدلة المؤسسية: {counts.get('directory_entries', 0)}",
            f"- بلاغات السمعة: {counts.get('reputation_reports', 0)}",
            "",
            "## الفجوات والقيود",
            "",
            "- لم يُنزَّل محتوى وثيقة ITU الكاملة ولم يُحلَّل؛ سُجلت البيانات الوصفية والرابط فقط.",
            "- أسماء الجهات التنظيمية مأخوذة من فهرس اكتشافي، ولم تُتحقق الملكية أو الصلاحية الحالية.",
            "- بيانات libphonenumber مرجعية وليست قراراً رسمياً، وقد تتأخر عن تغييرات الجهة الوطنية.",
            "- أرقام الطوارئ والخدمات مرجعية، ولم تُؤكد رسمياً لكل دولة؛ يجب التحقق محلياً.",
            "- المشغل المخصص ليس المشغل الحالي بعد نقل الرقم (MNP).",
            "- لا توجد بيانات مشتركين أو حالة نشاط خط أو هوية خاصة في هذا السجل.",
            "",
        ]
    )
    if entity.get("notes_ar"):
        lines.extend(["## ملاحظة الكيان", "", entity["notes_ar"], ""])
    return "\n".join(lines)


def _entity_counts(connection: Any, entity: Mapping[str, Any]) -> dict[str, int]:
    iso2 = entity.get("iso2")
    entity_id = entity["entity_id"]
    plan_key = entity_id if entity.get("entity_kind") == "global_network" else iso2
    result = {
        "numbering_plans": connection.execute(
            "SELECT COUNT(*) FROM numbering_plans WHERE country_iso2=?", (plan_key,)
        ).fetchone()[0],
        "number_ranges": connection.execute(
            "SELECT COUNT(*) FROM number_ranges WHERE country_iso2=?", (iso2,)
        ).fetchone()[0],
        "carrier_observations": connection.execute(
            "SELECT COUNT(*) FROM carrier_observations WHERE country_iso2=?", (iso2,)
        ).fetchone()[0],
        "regulator_entries": connection.execute(
            "SELECT COUNT(*) FROM regulator_entries WHERE country_iso2=?", (iso2,)
        ).fetchone()[0],
        "directory_entries": connection.execute(
            "SELECT COUNT(*) FROM directory_entries WHERE country_iso2=?", (iso2,)
        ).fetchone()[0],
        "reputation_reports": connection.execute(
            "SELECT COUNT(*) FROM reputation_reports WHERE country_iso2=?", (iso2,)
        ).fetchone()[0],
    }
    return {key: int(value) for key, value in result.items()}


def write_reports(connection: Any) -> dict[str, Any]:
    paths.RESEARCH_DIR.mkdir(parents=True, exist_ok=True)
    entities = [dict(row) for row in connection.execute("SELECT * FROM countries_and_regions ORDER BY entity_id")]
    sources = [dict(row) for row in connection.execute("SELECT * FROM source_registry ORDER BY source_id")]
    coverage_rows = [dict(row) for row in connection.execute("SELECT * FROM coverage_matrix")]
    coverage_by_iso: dict[str, list[dict[str, Any]]] = {}
    for row in coverage_rows:
        coverage_by_iso.setdefault(row["country_iso2"], []).append(row)
    links = _link_results()
    written = []
    for entity in entities:
        applicable = [source for source in sources if _applies(source, entity)]
        iso2 = entity.get("iso2")
        key = iso2 or entity["entity_id"]
        filename = f"{iso2}.md" if iso2 else f"global-{_slug(entity['name_en'])}.md"
        path = paths.RESEARCH_DIR / filename
        path.write_text(
            _report(
                entity,
                applicable,
                coverage_by_iso.get(key, []),
                links,
                _entity_counts(connection, entity),
            ),
            encoding="utf-8",
        )
        written.append(path)
    index = [
        "# فهرس تقارير البحث القطري",
        "",
        f"عدد التقارير: {len(written)}",
        "",
        "| الكيان | الرمز | التقرير |",
        "|---|---|---|",
    ]
    for entity, path in zip(entities, written):
        index.append(
            f"| {entity['name_en']} | {entity.get('iso2') or entity['entity_id']} | [{path.name}]({path.name}) |"
        )
    (paths.RESEARCH_DIR / "index.md").write_text("\n".join(index) + "\n", encoding="utf-8")
    with connection:
        db.audit(connection, "reports.written", details={"reports": len(written)})
    return {"reports": len(written), "directory": str(paths.RESEARCH_DIR)}
