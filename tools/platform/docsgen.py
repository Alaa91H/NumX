"""Generate repository documentation from the live platform database."""

from __future__ import annotations

import json
from datetime import date
from typing import Any, Iterable, Mapping

from . import coverage, db, paths

CATEGORY_LABELS = {
    "numbering_plan": "Numbering plan",
    "fixed_line": "Fixed line",
    "mobile": "Mobile",
    "short_codes_emergency": "Emergency short codes",
    "services": "Service numbers",
    "allocated_carrier": "Allocated carrier",
    "regulator": "Regulator",
    "business_directory": "Business directory",
    "government_directory": "Government directory",
    "reputation": "Reputation reports",
    "satellite_networks": "Satellite networks",
}

STATUS_LABELS = {
    "imported": "Imported",
    "partial": "Partial provenance",
    "metadata_only": "Metadata only",
    "source_identified": "Source identified",
    "blocked": "Blocked / unavailable",
    "missing": "Missing",
}


def _sources(connection: Any) -> list[dict[str, Any]]:
    rows = []
    for row in connection.execute("SELECT * FROM source_registry ORDER BY source_id"):
        item = dict(row)
        item["data_categories"] = json.loads(item.pop("data_categories_json") or "[]")
        rows.append(item)
    return rows


def _coverage(connection: Any) -> list[dict[str, Any]]:
    return [dict(row) for row in connection.execute("SELECT * FROM coverage_matrix ORDER BY country_iso2, data_category")]


def _counts(connection: Any) -> dict[str, int]:
    return {
        "countries": connection.execute(
            "SELECT COUNT(*) FROM countries_and_regions WHERE entity_kind!='global_network'"
        ).fetchone()[0],
        "networks": connection.execute(
            "SELECT COUNT(*) FROM countries_and_regions WHERE entity_kind='global_network'"
        ).fetchone()[0],
        "sources": db.table_count(connection, "source_registry"),
        "plans": db.table_count(connection, "numbering_plans"),
        "ranges": db.table_count(connection, "number_ranges"),
        "carriers": db.table_count(connection, "carrier_observations"),
        "regulators": db.table_count(connection, "regulator_entries"),
        "directory": db.table_count(connection, "directory_entries"),
        "reputation": db.table_count(connection, "reputation_reports"),
        "provenance": db.table_count(connection, "record_provenance"),
        "history": db.table_count(connection, "provenance_history"),
        "jobs": db.table_count(connection, "import_jobs"),
    }


def _status_counts(connection: Any) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    for row in connection.execute(
        "SELECT data_category, status, COUNT(*) AS n FROM coverage_matrix GROUP BY data_category, status"
    ):
        result.setdefault(row["data_category"], {})[row["status"]] = row["n"]
    return result


def _source_table(sources: Iterable[Mapping[str, Any]], *, include_country: bool = True) -> list[str]:
    header = "| Source ID | Name | Country/entity | Category | Access | License | Verification | Import status |"
    divider = "|---|---|---|---|---|---|---|---|"
    lines = [header, divider]
    for source in sources:
        lines.append(
            "| `{sid}` | {name} | {country} | {category} | {access} | {license} | {verification} | {import_status} |".format(
                sid=source["source_id"],
                name=str(source["source_name"]).replace("|", "/"),
                country=source.get("country_iso2") or source.get("entity_id") or "global",
                category=source["source_category"],
                access=source["access_method"],
                license=source["license_name"],
                verification=source["verification_status"],
                import_status=source["import_status"],
            )
        )
    return lines


def global_sources(connection: Any) -> str:
    sources = _sources(connection)
    counts = _counts(connection)
    core = [source for source in sources if not source.get("country_iso2") and not source.get("entity_id")]
    countries = [source for source in sources if source.get("country_iso2")]
    networks = [source for source in sources if (source.get("entity_id") or "").startswith("GLOBAL:")]
    blocked = [source for source in sources if source["import_status"] in coverage.BLOCKED_IMPORT_STATUSES]
    lines = [
        "# GLOBAL_DATA_SOURCES.md — المصادر العالمية والوطنية",
        "",
        f"آخر توليد: {date.today().isoformat()}",
        "",
        "## نطاق هذا الملف",
        "",
        "يسجل هذا الملف المصادر التي فُهرست فعلاً في قاعدة المنصة. "
        "لا يعني وجود مصدر أنه مسموح بإعادة توزيعه أو أنه تم استيراد بياناته. "
        "الحقول منفصلة عمداً: حالة الوصول، حالة الترخيص، حالة التحقق، وحالة الاستيراد.",
        "",
        "## أرقام فعلية",
        "",
        f"- الدول والمناطق: {counts['countries']}",
        f"- الشبكات العالمية: {counts['networks']}",
        f"- المصادر المسجلة: {counts['sources']}",
        f"- خطط الترقيم: {counts['plans']}",
        f"- نطاقات الأرقام: {counts['ranges']}",
        f"- ملاحظات المشغل المخصص: {counts['carriers']}",
        f"- سجلات الجهات التنظيمية: {counts['regulators']}",
        f"- سجلات الأدلة المؤسسية: {counts['directory']}",
        f"- بلاغات السمعة: {counts['reputation']}",
        "",
        "## المصادر العالمية والأساسية",
        "",
        *_source_table(core),
        "",
        "## مصادر الشبكات العالمية",
        "",
        *_source_table(networks),
        "",
        "## المصادر الوطنية",
        "",
        f"عدد المصادر المرتبطة بدولة/منطقة: {len(countries)}. "
        "القائمة الكاملة في `data/source-catalog/country-source-catalog.csv`، "
        "وتقرير مستقل لكل كيان في `docs/country-source-research/`.",
        "",
        "## مصادر محجوبة أو غير متاحة (مفهرسة ولا تُستورد)",
        "",
        "| المصدر | السبب | الحالة |",
        "|---|---|---|",
    ]
    for source in blocked:
        lines.append(
            f"| `{source['source_id']}` | {source['known_limitations']} | {source['import_status']} |"
        )
    lines.extend(
        [
            "",
            "## ما لم يُفعل",
            "",
            "- لم تُنزَّل وثائق ITU الكاملة ولم تُعاد توزيعها؛ سُجلت الروابط والبيانات الوصفية.",
            "- لم تُستخدم نقاط Wikidata SPARQL أو Overpass أو ملفات OSM الكبيرة لأن robots.txt يمنعها.",
            "- لم تُستخدم واجهات PhoneBlock لأنها تتطلب مصادقة وتمنعها robots.txt، وترخيصها غير تجاري.",
            "- لم تُستورد بيانات FCC/FTC لأن نقطة FCC فشلت في DNS ونقطة FTC أعادت HTTP 404.",
            "- لم يُجرَ بحث مستقل بلغة كل دولة على حدة؛ الفهارس الحالية مرجعية واكتشافية وليست تحققاً وطنياً كاملاً.",
            "",
        ]
    )
    return "\n".join(lines)


def data_coverage(connection: Any) -> str:
    counts = _counts(connection)
    status = _status_counts(connection)
    rows = _coverage(connection)
    entities = {
        row["entity_id"]: dict(row)
        for row in connection.execute("SELECT * FROM countries_and_regions")
    }
    by_iso: dict[str, dict[str, dict[str, Any]]] = {}
    for row in rows:
        by_iso.setdefault(row["country_iso2"], {})[row["data_category"]] = row
    lines = [
        "# DATA_COVERAGE.md — مصفوفة التغطية",
        "",
        f"آخر توليد: {date.today().isoformat()}",
        "",
        "## تعريف الحالات",
        "",
        "| الحالة | المعنى |",
        "|---|---|",
        "| `imported` | توجد سجلات مستوردة وتغطية أصل ≥ 90%. |",
        "| `partial` | توجد سجلات لكن تغطية الأصل أقل من 90%. |",
        "| `metadata_only` | سُجلت بيانات وصفية/رابط فقط دون استيراد سجلات. |",
        "| `source_identified` | وُجد مصدر مرشح لكن لم تُستورد منه سجلات لهذا الكيان. |",
        "| `blocked` | كل المصادر المكتشفة محجوبة أو غير متاحة. |",
        "| `missing` | لم يُعثر على مصدر مسموح. |",
        "",
        "## ملخص حسب الفئة",
        "",
        "| الفئة | imported | partial | metadata_only | source_identified | blocked | missing |",
        "|---|---|---|---|---|---|---|",
    ]
    for category in coverage.CATEGORY_ORDER:
        item = status.get(category, {})
        lines.append(
            f"| {CATEGORY_LABELS[category]} | {item.get('imported', 0)} | {item.get('partial', 0)} | "
            f"{item.get('metadata_only', 0)} | {item.get('source_identified', 0)} | "
            f"{item.get('blocked', 0)} | {item.get('missing', 0)} |"
        )
    lines.extend(
        [
            "",
            f"إجمالي صفوف المصفوفة: {len(rows)}. "
            f"عدد الكيانات: {counts['countries']} دولة/منطقة + {counts['networks']} شبكة عالمية.",
            "",
            "## التغطية لكل كيان",
            "",
            "الاختصارات: `IMP` مستورد، `PART` جزئي، `META` بيانات وصفية، "
            "`SRC` مصدر محدد، `BLK` محجوب، `MISS` مفقود.",
            "",
            "| الكيان | " + " | ".join(CATEGORY_LABELS[category] for category in coverage.CATEGORY_ORDER) + " |",
            "|---|" + "---|" * len(coverage.CATEGORY_ORDER),
        ]
    )
    abbreviations = {
        "imported": "IMP",
        "partial": "PART",
        "metadata_only": "META",
        "source_identified": "SRC",
        "blocked": "BLK",
        "missing": "MISS",
    }
    for entity_id, entity in sorted(entities.items()):
        key = entity.get("iso2") or entity_id
        label = f"{entity['name_en']} ({key})"
        cells = []
        for category in coverage.CATEGORY_ORDER:
            row = by_iso.get(key, {}).get(category)
            cells.append(abbreviations.get(row["status"], "—") if row else "—")
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    lines.extend(
        [
            "",
            "## ملاحظات إلزامية",
            "",
            "- التغطية تقيس وجود سجلات مسموحة في قاعدة البيانات، وليس اكتمال الواقع الوطني.",
            "- `numbering_plan` المستورد من libphonenumber هو خطة مرجعية، وليس قرار الجهة الوطنية.",
            "- `regulator` مستورد من فهرس اكتشافي، ودرجة الثقة منخفضة.",
            "- `reputation` لا يحتوي سجلات لأن المصادر المكتشفة محجوبة أو مقيدة.",
            "- الأرقام القصيرة وأرقام الطوارئ لا تُطبَّع إلى E.164.",
            "",
        ]
    )
    return "\n".join(lines)


def data_licenses(connection: Any) -> str:
    sources = _sources(connection)
    groups: dict[str, list[Mapping[str, Any]]] = {}
    for source in sources:
        groups.setdefault(source["license_name"], []).append(source)
    lines = [
        "# DATA_LICENSES.md — التراخيص والقيود",
        "",
        f"آخر توليد: {date.today().isoformat()}",
        "",
        "> هذا سجل تقني وليس رأياً قانونياً. راجع الترخيص الأصلي قبل إعادة التوزيع.",
        "",
        "## ملخص التراخيص",
        "",
        "| الترخيص | عدد المصادر | إعادة التوزيع | الاستخدام التجاري | الإسناد | Share-Alike |",
        "|---|---|---|---|---|---|",
    ]
    for license_name, items in sorted(groups.items(), key=lambda pair: (-len(pair[1]), pair[0])):
        redistribution = sorted({item["redistribution_allowed"] for item in items})
        commercial = sorted({item["commercial_use_allowed"] for item in items})
        attribution = any(item["attribution_required"] for item in items)
        share_alike = any(item["share_alike_required"] for item in items)
        lines.append(
            f"| {license_name} | {len(items)} | {', '.join(redistribution)} | {', '.join(commercial)} | "
            f"{'نعم' if attribution else 'لا'} | {'نعم' if share_alike else 'لا'} |"
        )
    lines.extend(
        [
            "",
            "## التفاصيل حسب المصدر",
            "",
            "| المصدر | الترخيص | رابط الترخيص | إعادة التوزيع | تجاري | الإسناد | Share-Alike | المراجعة القانونية |",
            "|---|---|---|---|---|---|---|---|",
        ]
    )
    for source in sources:
        lines.append(
            "| `{sid}` | {license} | {url} | {redist} | {commercial} | {attr} | {share} | {legal} |".format(
                sid=source["source_id"],
                license=source["license_name"],
                url=source.get("license_url") or "—",
                redist=source["redistribution_allowed"],
                commercial=source["commercial_use_allowed"],
                attr="نعم" if source["attribution_required"] else "لا",
                share="نعم" if source["share_alike_required"] else "لا",
                legal=source["legal_review_status"],
            )
        )
    lines.extend(
        [
            "",
            "## قيود مهمة",
            "",
            "- **ODbL-1.0** لبيانات الطوارئ المشتقة وOSM: توجد شروط مشاركة بالمثل وإسناد. "
            "ملف الطوارئ في التطبيق مشتق من مصادر متعددة، ووُسم `conditional`.",
            "- **CC BY-SA 4.0** لجدول Wikipedia: يتطلب إسناداً ومشاركة بالمثل عند إعادة استخدام الجدول.",
            "- **CC0-1.0** لبيانات Wikidata: لا يتطلب إسناداً قانونياً، لكن الإسناد ممارسة جيدة.",
            "- **Apache-2.0** لبيانات libphonenumber: يتطلب إسناداً وإشعارات الترخيص.",
            "- **ITU terms of use**: البيانات الوصفية والروابط فقط؛ لم تُنزَّل الوثائق أو تُعاد توزيعها.",
            "- **PhoneBlock**: ترخيص المحتوى غير تجاري ويشارك بالمثل، والواجهات تتطلب مصادقة وتمنعها robots.txt. "
            "المصدر مفهرس فقط ولا توجد له سجلات.",
            "- **FCC/FTC**: بيانات حكومية أمريكية عامة، لكن نقاط الوصول لم تعمل في بيئة التنفيذ.",
            "",
        ]
    )
    return "\n".join(lines)


def data_ingestion(connection: Any) -> str:
    counts = _counts(connection)
    lines = [
        "# DATA_INGESTION.md — تشغيل ETL",
        "",
        f"آخر توليد: {date.today().isoformat()}",
        "",
        "## المتطلبات",
        "",
        "```bash",
        "python3 -m pip install -r tools/metadata/requirements.txt",
        "python3 -m tools.platform.cli catalog",
        "python3 -m tools.platform.cli import-all",
        "python3 -m tools.platform.cli coverage",
        "python3 -m tools.platform.cli summary",
        "python3 -m tools.platform.cli reports",
        "python3 -m tools.platform.cli docs",
        "```",
        "",
        "قاعدة البيانات: `data/platform.db` (SQLite). لا تحتاج خادماً. "
        "المخطط منطقي وقابل للنقل إلى PostgreSQL؛ راجع `DATABASE_SCHEMA.md`.",
        "",
        "## الموصلات المنفذة",
        "",
        "| الموصل | المصدر | الجدول الهدف | وضع الاستيراد |",
        "|---|---|---|---|",
        "| `libphonenumber-ranges` | libphonenumber PhoneNumberMetadata | `number_ranges` | نطاقات مرجعية |",
        "| `libphonenumber-short-numbers` | ShortNumberMetadata | `number_ranges` | أرقام قصيرة |",
        "| `numx-emergency-reference` | `emergency.json` | `number_ranges` | طوارئ/خدمات مرجعية |",
        "| `libphonenumber-carrier-prefixes` | carrier data | `carrier_observations` | تخصيص نطاق فقط |",
        "| `itu-nnp-metadata` | ITU seed | `numbering_plans` | بيانات وصفية |",
        "| `wikipedia-regulator-discovery` | Wikipedia regulator table | `regulator_entries` | اكتشاف فقط |",
        "| `wikidata-entitydata-directory` | Wikidata EntityData sample | `directory_entries` | عينة مؤسسية |",
        "",
        "## أوامر الاستيراد",
        "",
        "```bash",
        "# استيراد كل الموصلات المسموحة",
        "python3 -m tools.platform.cli import-all",
        "",
        "# استيراد موصل واحد مع تجربة جافة",
        "python3 -m tools.platform.cli import libphonenumber --dry-run",
        "",
        "# استيراد محدود لعينة",
        "python3 -m tools.platform.cli import wikidata --limit 25",
        "",
        "# كشف روابط ITU ومستنداتها (شبكة)",
        "python3 -m tools.platform.cli discover-itu --limit 20",
        "",
        "# عينة Wikidata المؤسسية (شبكة)",
        "python3 -m tools.platform.cli discover-wikidata",
        "",
        "# فحص الروابط (شبكة)",
        "python3 -m tools.platform.cli linkcheck --workers 4",
        "```",
        "",
        "## ضمانات التنفيذ",
        "",
        "- كل استيراد يعمل داخل savepoint؛ فشل سجل يُرفض ولا يلغي بقية السجلات.",
        "- فشل قاتل (ملف تالف/انقطاع) يتراجع عن كل تغييرات المهمة.",
        "- `--dry-run` ينفذ التحقق والتحويل ثم يتراجع، ويترك تقرير المهمة.",
        "- إعادة الاستيراد تستخدم `ON CONFLICT` على مفاتيح المصدر، فلا تتكرر السجلات.",
        "- تغير قيم الأصل يُحفظ في `provenance_history` قبل تحديث الصف الحالي.",
        "- كل مهمة تُسجل في `import_jobs` مع الأعداد والأخطاء، وكل تغيير مهم في `audit_events`.",
        "",
        "## أعداد آخر تشغيل",
        "",
        f"- خطط الترقيم: {counts['plans']}",
        f"- نطاقات الأرقام: {counts['ranges']}",
        f"- ملاحظات المشغل: {counts['carriers']}",
        f"- الجهات التنظيمية: {counts['regulators']}",
        f"- الأدلة المؤسسية: {counts['directory']}",
        f"- صفوف الأصل: {counts['provenance']}",
        f"- مهام الاستيراد: {counts['jobs']}",
        "",
        "## إضافة موصل جديد",
        "",
        "1. أضف المصدر إلى `catalog.py` مع الترخيص وحالة المراجعة.",
        "2. نفّذ `records`, `validate`, `to_row`, `provenance` وفق `connectors/base.py`.",
        "3. أضف اختبارات بملف ثابت (fixture) لا تعتمد على الشبكة.",
        "4. سجّل الموصل في `cli.py` وفي هذا الملف.",
        "5. شغّل `import <id> --dry-run` ثم تحقق من `import_jobs` و`record_provenance`.",
        "",
        "## الجدولة",
        "",
        "لا تستخدم دورة موحدة. أمثلة مقترحة:",
        "",
        "- libphonenumber: عند إصدار جديد مثبت في `sources.lock.json`.",
        "- ITU/regulators: مراجعة ربع سنوية أو عند تغيير الجهة.",
        "- Wikidata sample: شهرياً بحد أقصى، مع احترام robots وحدود المعدل.",
        "- linkcheck: أسبوعياً، مع تخزين النتائج وعدم اعتبار الفشل المؤقت حذفاً للبيانات.",
        "",
        "## ما لا يفعله ETL",
        "",
        "- لا ينزّل مجموعات عالمية كاملة افتراضياً.",
        "- لا يتجاوز مصادقة أو robots.txt.",
        "- لا ينشئ سجلات لأرقام محتملة لم تظهر في مصدر.",
        "- لا يدمج تعارضات المؤسسات تلقائياً دون مراجعة.",
        "",
    ]
    return "\n".join(lines)


def database_schema(connection: Any) -> str:
    counts = _counts(connection)
    lines = [
        "# DATABASE_SCHEMA.md — المخطط المنطقي",
        "",
        f"إصدار المخطط: `{db.SCHEMA_VERSION}`. قاعدة التنفيذ الحالية: SQLite (`data/platform.db`).",
        "",
        "## المبدأ",
        "",
        "المخطط تحليلي/مرجعي وليس قاعدة مشتركين. لا يحتوي حالة نشاط خط، "
        "ولا هوية مشترك، ولا ادعاءً بالمشغل الحالي بعد نقل الرقم. "
        "كل حقيقة مهمة مرتبطة بمصدر وتاريخ وترخيص وحدود.",
        "",
        "## الجداول",
        "",
        "| الجدول | الغرض | مفتاح الربط |",
        "|---|---|---|",
        "| `countries_and_regions` | الدول والمناطق والشبكات العالمية | `entity_id`, `iso2` |",
        "| `source_registry` | سجل المصادر والوصول والتراخيص | `source_id` |",
        "| `source_datasets` | مجموعات البيانات وفئاتها | `dataset_id` → `source_id` |",
        "| `source_releases` | الإصدارات والبصمات | `release_id` → `source_id` |",
        "| `numbering_plans` | خطط الترقيم | `plan_id` → `source_id` |",
        "| `number_ranges` | نطاقات/أنماط الأرقام | `range_id` → `plan_id`, `source_id` |",
        "| `phone_numbers` | أرقام ظهرت فعلاً في مصدر مسموح | `phone_id` → `source_id` |",
        "| `directory_entries` | أرقام مؤسسات منشورة | `entry_id` → `source_id` |",
        "| `carrier_observations` | تخصيص النطاق/المشغل | `observation_id` → `source_id` |",
        "| `reputation_reports` | بلاغات السمعة | `report_id` → `source_id` |",
        "| `regulator_entries` | الجهات التنظيمية المكتشفة | `regulator_id` → `source_id` |",
        "| `record_provenance` | أصل كل حقل/سجل | `provenance_id` |",
        "| `provenance_history` | النسخ السابقة عند التغيير | `history_id` |",
        "| `import_jobs` | مهام ETL وأعدادها | `job_id` |",
        "| `audit_events` | تدقيق التغييرات | `event_id` |",
        "| `coverage_matrix` | التغطية لكل كيان/فئة | (`country_iso2`,`data_category`) |",
        "",
        "## العلاقات المهمة",
        "",
        "- `number_ranges.source_id` → `source_registry.source_id`.",
        "- `number_ranges.source_release_id` → `source_releases.release_id`.",
        "- `record_provenance.source_id` → `source_registry.source_id`.",
        "- `record_provenance.import_job_id` → `import_jobs.job_id`.",
        "- `regulator_entries.discovery_source_id` يشير إلى فهرس الاكتشاف، بينما `source_id` يشير إلى المصدر المرشح.",
        "- `countries_and_regions.entity_id` يستخدم `COUNTRY:<ISO2>` أو `GLOBAL:<NAME>`.",
        "",
        "## الفهارس",
        "",
        "الفهارس تغطي: الدولة/النوع في النطاقات، الدولة في الأرقام والأدلة، "
        "البادئة في ملاحظات المشغل، الدولة في الجهات التنظيمية، "
        "والكيان في الأصل. يوجد فهرس فريد على "
        "(`entity_type`,`entity_id`,`field_name`,`source_id`,`source_record_id`) "
        "لمنع تضاعف الأصل عند إعادة الاستيراد.",
        "",
        "## أعداد حالية",
        "",
        f"- الكيانات: {counts['countries']} + {counts['networks']}",
        f"- المصادر: {counts['sources']}",
        f"- خطط الترقيم: {counts['plans']}",
        f"- نطاقات الأرقام: {counts['ranges']}",
        f"- ملاحظات المشغل: {counts['carriers']}",
        f"- الجهات التنظيمية: {counts['regulators']}",
        f"- الأدلة المؤسسية: {counts['directory']}",
        f"- صفوف الأصل: {counts['provenance']}",
        "",
        "## ملاحظات PostgreSQL",
        "",
        "المخطط منطقي ومتوافق مع PostgreSQL بعد تحويلات بسيطة: "
        "`INTEGER PRIMARY KEY AUTOINCREMENT` → `BIGSERIAL`, "
        "`TEXT` → `TEXT`, `REAL` → `DOUBLE PRECISION`, "
        "و`ON CONFLICT` مدعوم. لا حاجة إلى PostGIS إلا لاستعلامات مكانية فعلية. "
        "لم يُنشر خادم PostgreSQL لأن المشروع الحالي لا يحتويه، وSQLite يلبي "
        "التشغيل المحلي وCI دون تكلفة.",
        "",
    ]
    return "\n".join(lines)


def provenance_audit(connection: Any) -> str:
    counts = _counts(connection)
    lines = [
        "# PROVENANCE_AND_AUDIT.md — الأصل والتدقيق",
        "",
        f"آخر توليد: {date.today().isoformat()}",
        "",
        "## ما الذي يُحفظ",
        "",
        "لكل حقل/سجل مهم: `source_id`, رابط المصدر، معرّف السجل الأصلي، "
        "وقت الجلب UTC، وقت تحديث المصدر إن عُرف، إصدار المصدر، مهمة الاستيراد، "
        "إصدار الموصل، القيمة الأصلية، القيمة المطّبعة، التحويلات، حالة التحقق، "
        "الترخيص، وحدود المعلومة.",
        "",
        "هذا يسمح بإعادة بناء كيف دخلت المعلومة وكيف تحولت. "
        "**لا يدّعي أن سجلات التطبيق أدلة قضائية معتمدة.**",
        "",
        "## التدفق",
        "",
        "1. المصدر يُسجل في `source_registry` مع الترخيص وحالة المراجعة.",
        "2. الجلب/الاكتشاف يُسجل الوقت وحالة HTTP والبصمة عند توفرها.",
        "3. الموصل يحوّل السجل ويحفظ القيمة الأصلية.",
        "4. `run_import` يتحقق من السجل ويكتبه داخل savepoint.",
        "5. `add_provenance` يحدّث صف الأصل الحالي؛ إذا تغيرت القيمة الجوهرية "
        "ينسخ النسخة السابقة إلى `provenance_history`.",
        "6. `import_jobs` و`audit_events` يسجلان النتيجة والأعداد والأخطاء.",
        "",
        "## منع التكرار",
        "",
        "يوجد فهرس فريد على "
        "(`entity_type`,`entity_id`,`field_name`,`source_id`,`source_record_id`). "
        "إعادة الاستيراد لا تضاعف صفوف الأصل؛ التغيير الجوهري يحفظ نسخة تاريخية.",
        "",
        "## أمثلة استعلام",
        "",
        "```sql",
        "-- أصل نطاق معين",
        "SELECT * FROM record_provenance",
        "WHERE entity_type='number_range' AND source_record_id='SY:MOBILE:...';",
        "",
        "-- مهام فاشلة",
        "SELECT job_id, connector_id, status, errors_json FROM import_jobs WHERE status='failed';",
        "",
        "-- تغطية الأصل حسب الفئة",
        "SELECT data_category, status, COUNT(*) FROM coverage_matrix GROUP BY 1,2;",
        "```",
        "",
        "## أعداد حالية",
        "",
        f"- صفوف الأصل الحالية: {counts['provenance']}",
        f"- نسخ تاريخية: {counts['history']}",
        f"- مهام الاستيراد: {counts['jobs']}",
        "",
        "## حدود",
        "",
        "- بعض المصادر لا توفر بصمة أو تاريخ نشر؛ تُترك القيم فارغة بدل اختراعها.",
        "- وثائق ITU لم تُنزَّل، لذا الأصل يغطي البيانات الوصفية والرابط فقط.",
        "- بيانات Wikidata مجتمعية وقد تتغير؛ تاريخ `modified` يُحفظ عند توفره.",
        "- سجلات التدقيق قابلة للتعديل بصلاحيات قاعدة البيانات؛ التصميم لا يضمن "
        "استحالة العبث، بل يوفّر سجلاً قابلاً للمراجعة.",
        "",
    ]
    return "\n".join(lines)


def api_reference(connection: Any) -> str:
    counts = _counts(connection)
    return "\n".join(
        [
            "# API_REFERENCE.md — واجهات API الفعلية",
            "",
            f"آخر توليد: {date.today().isoformat()}",
            "",
            "الخادم اختياري وقراءة فقط، مبني على مكتبة Python القياسية فوق SQLite. "
            "التطبيق لا يعتمد عليه؛ البحث المحلي يعمل دون إنترنت.",
            "",
            "## التشغيل",
            "",
            "```bash",
            "python3 -m tools.platform.cli serve --host 127.0.0.1 --port 8787",
            "```",
            "",
            "## الضمانات",
            "",
            "- استجابات JSON فقط.",
            "- حد 120 طلباً/دقيقة لكل عنوان IP (في الذاكرة).",
            "- لا توجد كتابة أو مصادقة مطلوبة للقراءة العامة.",
            "- لا تُسجل أرقام الهواتف كاملة في سجلات الطلبات.",
            "- كل استجابة تتضمن حدود المعلومة عند انطباقها.",
            "- لا توجد نقطة لإرجاع بيانات مشتركين أو حالة نشاط خط.",
            "",
            "## النقاط",
            "",
            "| الطريقة | المسار | الوصف |",
            "|---|---|---|",
            "| GET | `/api/v1/health` | صحة الخدمة وإصدار المخطط |",
            "| GET | `/api/v1/phone/lookup?number=...&region=SY` | تحليل رقم مع المصادر والحدود |",
            "| GET | `/api/v1/phone/metadata?number=...` | حقول التحليل الأساسية |",
            "| GET | `/api/v1/phone/carrier?number=...` | المشغل المخصص فقط |",
            "| GET | `/api/v1/phone/reputation?number=...` | البلاغات المتاحة فقط |",
            "| GET | `/api/v1/countries` | قائمة الدول/المناطق |",
            "| GET | `/api/v1/countries/{code}` | تفاصيل دولة وتغطيتها ومصادرها |",
            "| GET | `/api/v1/countries/{code}/sources` | مصادر الدولة |",
            "| GET | `/api/v1/data-sources` | سجل المصادر مع فلاتر |",
            "| GET | `/api/v1/data-sources/{sourceId}/status` | حالة مصدر ومهامه |",
            "| GET | `/api/v1/coverage` | مصفوفة التغطية |",
            "| GET | `/api/v1/imports` | مهام الاستيراد |",
            "",
            "## معاملات مشتركة",
            "",
            "- `limit` (1–500، افتراضي 50) و`offset` (≥0) لنقاط القوائم.",
            "- `category` لفلترة فئات البيانات.",
            "- `importStatus` لفلترة حالة الاستيراد.",
            "- `country` و`category` لمصفوفة التغطية.",
            "",
            "## أمثلة",
            "",
            "```bash",
            "curl 'http://127.0.0.1:8787/api/v1/health'",
            "curl 'http://127.0.0.1:8787/api/v1/phone/lookup?number=%2B963944567890'",
            "curl 'http://127.0.0.1:8787/api/v1/countries/SY'",
            "curl 'http://127.0.0.1:8787/api/v1/data-sources?category=mobile&limit=10'",
            "curl 'http://127.0.0.1:8787/api/v1/coverage?country=SY'",
            "```",
            "",
            "## مثال استجابة lookup (مختصر)",
            "",
            "```json",
            "{",
            '  "validNumberingPlan": true,',
            '  "e164": "+963944567890",',
            '  "countryIso2": "SY",',
            '  "numberType": "MOBILE",',
            '  "allocatedCarrier": {"carrierName": "Syriatel", "notCurrentCarrier": true},',
            '  "lineActive": null,',
            '  "lineActiveNote": "No permitted source in this platform proves that a line is active."',
            "}",
            "```",
            "",
            "## أعداد حالية",
            "",
            f"- المصادر المتاحة عبر API: {counts['sources']}",
            f"- الكيانات: {counts['countries']} + {counts['networks']}",
            f"- صفوف التغطية: {counts['countries'] * len(coverage.CATEGORY_ORDER) + counts['networks'] * len(coverage.CATEGORY_ORDER)} (متوقع)",
            "",
            "## ما لا يقدمه API",
            "",
            "- لا يقدم اسم مشترك أو عنواناً خاصاً.",
            "- لا يثبت نشاط الخط.",
            "- لا يعيد توزيع مصدراً يمنع ترخيصه ذلك.",
            "- لا يستعلم المصادر المحجوبة وقت الطلب.",
            "",
        ]
    )


def operations(connection: Any) -> str:
    counts = _counts(connection)
    return "\n".join(
        [
            "# OPERATIONS.md — التشغيل والمراقبة",
            "",
            f"آخر توليد: {date.today().isoformat()}",
            "",
            "## التشغيل اليومي",
            "",
            "```bash",
            "python3 -m tools.platform.cli status",
            "python3 -m tools.platform.cli check",
            "python3 -m tools.platform.cli coverage",
            "python3 -m tools.platform.cli summary",
            "```",
            "",
            "## المراقبة",
            "",
            "- `import_jobs`: آخر مهمة، الحالة، الأعداد، الأخطاء.",
            "- `source_registry.last_checked_at` و`last_http_status`: آخر فحص رابط.",
            "- `coverage_matrix.freshness_days`: عمر آخر تحقق.",
            "- `audit_events`: التغييرات المهمة.",
            "- `data/source-catalog/link-check-results.json`: نتائج الفحص التفصيلية.",
            "",
            "## النسخ الاحتياطي",
            "",
            "```bash",
            "sqlite3 data/platform.db \".backup 'data/backups/platform-$(date +%F).db'\"",
            "sha256sum data/backups/platform-*.db",
            "```",
            "",
            "احتفظ بنسخة من `data/source-catalog/` و`docs/country-source-research/` مع قاعدة البيانات. "
            "لا تضع مفاتيح أو أسراراً في قاعدة البيانات أو المستودع.",
            "",
            "## التشخيص",
            "",
            "```bash",
            "sqlite3 data/platform.db 'PRAGMA integrity_check;'",
            "sqlite3 data/platform.db 'SELECT status, COUNT(*) FROM import_jobs GROUP BY status;'",
            "sqlite3 data/platform.db 'SELECT source_id, import_status, last_error FROM source_registry WHERE last_error IS NOT NULL;'",
            "```",
            "",
            "## القيود المعروفة في هذه البيئة",
            "",
            "- اختبار التطبيق على جهاز/محاكي Android لم ينجح: جهاز ADB رفض الاتصال، "
            "والمحاكيان `Pixel_API37` و`Pixel_API35` انهارا بـ SIGSEGV/exit 139. "
            "اختبارات Kotlin وبناء APK يعملان في CI.",
            "- Wikidata SPARQL وOverpass وملفات OSM الكبيرة محجوبة بـ robots.txt.",
            "- PhoneBlock يتطلب مصادقة ويمنع robots.txt نقاط البيانات، وترخيصه غير تجاري.",
            "- FCC Open Data فشل في DNS، وFTC DNC أعاد 404.",
            "- البحث الوطني المستقل بكل لغة لم يكتمل؛ الفهرس الحالي مرجعي/اكتشافي.",
            "",
            "## التكلفة",
            "",
            "البرنامج والمصادر المستخدمة مجانية. الاستضافة العامة والتخزين والنقل "
            "والنسخ الاحتياطي قد تكون لها تكلفة. لا توجد واجهة مدفوعة في المسار الأساسي.",
            "",
            "## أعداد حالية",
            "",
            f"- المصادر: {counts['sources']}",
            f"- مهام الاستيراد: {counts['jobs']}",
            f"- صفوف الأصل: {counts['provenance']}",
            "",
        ]
    )


def methodology(connection: Any) -> str:
    counts = _counts(connection)
    return "\n".join(
        [
            "# منهجية البحث عن المصادر",
            "",
            f"آخر توليد: {date.today().isoformat()}",
            "",
            "## 1. نطاق الكيانات",
            "",
            "بدأنا من أصول NumX الحالية (252 دولة/منطقة) وطابقناها مع فهرس "
            "ITU National Numbering Plans. أضفنا الشبكات العالمية السبع الواردة "
            "في وثائق ITU: Inmarsat، Iridium، Globalstar، Thuraya، MCP، "
            "AT&T Cingular، وChina Telecom Satellite. "
            "المطابقة تعني وجود إدخال في الفهرس، ولا تعني أن الخطة حديثة أو أن "
            "الجهة التنظيمية مؤكدة.",
            "",
            "## 2. قواعد قبول المصدر",
            "",
            "1. الوصول مسموح: لا مصادقة متجاوزة، لا robots.txt مخالف، لا حد معدل متجاوز.",
            "2. الترخيص معروف أو قابل للمراجعة، ومسجل في `source_registry`.",
            "3. المصدر يحدد بوضوح ما يمثله: خطة ترقيم، تخصيص نطاق، جهة تنظيمية، "
            "دليل مؤسسي، أو بلاغ سمعة.",
            "4. لا يُستورد مصدر مقيد أو محجوب؛ يُفهرس فقط.",
            "5. لا يُنشأ رقم مشترك أو هوية خاصة من بادئة.",
            "6. لا يُعاد نشر وثيقة لا يسمح ترخيصها بذلك.",
            "",
            "## 3. مستويات التحقق",
            "",
            "| المستوى | المعنى |",
            "|---|---|",
            "| `OFFICIAL_INDEX` | إدخال في فهرس رسمي مثل ITU، دون تحليل الوثيقة. |",
            "| `REFERENCE` | بيانات مرجعية من libphonenumber أو قائمة NumX. |",
            "| `COMMUNITY_CURATED` | بيانات مجتمعية مثل Wikidata. |",
            "| `DISCOVERY` | اسم/رابط اكتُشف من فهرس، دون تحقق ملكية. |",
            "| `BLOCKED` | مصدر مفهرس لكنه غير مسموح/غير متاح. |",
            "",
            "## 4. البحث متعدد اللغات",
            "",
            "اللغة الإنجليزية استخدمت للفهارس العالمية. لم يكتمل البحث المستقل "
            "بلغات كل دولة (العربية، الفرنسية، الإسبانية، البرتغالية، الروسية، "
            "الصينية، وغيرها). لذلك تُوصف التغطية الحالية بأنها مرجعية/اكتشافية، "
            "وليست تحققاً وطنياً شاملاً. الخطوة التالية هي مراجعة بوابة كل جهة "
            "بلغتها المحلية والبحث عن ملفات CSV/XLSX/JSON/XML/PDF وواجهات API.",
            "",
            "## 5. قياس التغطية",
            "",
            "لكل كيان/فئة:",
            "",
            "- `source_count`: عدد المصادر المطبقة في السجل.",
            "- `imported_record_count`: عدد السجلات الفعلية في الجدول الهدف.",
            "- `provenance_coverage`: نسبة السجلات التي لها صف أصل.",
            "- `freshness_days`: عمر آخر تحقق/استيراد.",
            "- `status`: imported/partial/metadata_only/source_identified/blocked/missing.",
            "",
            "لا تُحتسب صفحة فهرس قابلة للوصول كتغطية مستوردة. "
            "ولا تُحتسب وثيقة ITU غير المحللة كخطة مستوردة.",
            "",
            "## 6. التعارضات",
            "",
            "لا تُدمج السجلات المتعارضة بصمت. تُحفظ قيم المصادر المختلفة في "
            "`record_provenance` و`provenance_history`. مطابقة المؤسسات تعتمد على "
            "معرف المصدر والرقم والاسم والموقع، ولا يُعتبر الرقم وحده دليلاً نهائياً.",
            "",
            "## 7. القيود الحالية",
            "",
            f"- الدول/المناطق: {counts['countries']}، الشبكات: {counts['networks']}.",
            "- 21 دولة/منطقة في أصول NumX ليس لها إدخال ITU مستقل.",
            "- 59 دولة/منطقة ليس لها إدخال في فهرس Wikipedia التنظيمي.",
            "- عينة Wikidata المؤسسية محدودة بدول مختارة وليست عالمية.",
            "- لا توجد بلاغات سمعة مستوردة.",
            "",
            "## 8. إعادة التوليد",
            "",
            "```bash",
            "python3 -m tools.platform.cli catalog",
            "python3 -m tools.platform.cli import-all",
            "python3 -m tools.platform.cli coverage",
            "python3 -m tools.platform.cli summary",
            "python3 -m tools.platform.cli reports",
            "python3 -m tools.platform.cli docs",
            "```",
            "",
        ]
    )


def write_docs(connection: Any) -> dict[str, str]:
    paths.ensure_dirs()
    files = {
        "GLOBAL_DATA_SOURCES.md": global_sources(connection),
        "DATA_COVERAGE.md": data_coverage(connection),
        "DATA_LICENSES.md": data_licenses(connection),
        "DATA_INGESTION.md": data_ingestion(connection),
        "DATABASE_SCHEMA.md": database_schema(connection),
        "PROVENANCE_AND_AUDIT.md": provenance_audit(connection),
        "API_REFERENCE.md": api_reference(connection),
        "OPERATIONS.md": operations(connection),
        "docs/source-research-methodology.md": methodology(connection),
    }
    for name, content in files.items():
        path = paths.REPO_ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content.rstrip() + "\n", encoding="utf-8")
    return {name: str(paths.REPO_ROOT / name) for name in files}
