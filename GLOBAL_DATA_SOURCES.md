# GLOBAL_DATA_SOURCES.md — المصادر العالمية والوطنية

آخر توليد: 2026-10-28

## نطاق هذا الملف

يسجل هذا الملف المصادر التي فُهرست فعلاً في قاعدة المنصة. لا يعني وجود مصدر أنه مسموح بإعادة توزيعه أو أنه تم استيراد بياناته. الحقول منفصلة عمداً: حالة الوصول، حالة الترخيص، حالة التحقق، وحالة الاستيراد.

## أرقام فعلية

- الدول والمناطق: 252
- الشبكات العالمية: 7
- المصادر المسجلة: 460
- خطط الترقيم: 484
- نطاقات الأرقام: 18325
- ملاحظات المشغل المخصص: 28138
- سجلات الجهات التنظيمية: 209
- سجلات الأدلة المؤسسية: 4
- بلاغات السمعة: 0

## المصادر العالمية والأساسية

| Source ID | Name | Country/entity | Category | Access | License | Verification | Import status |
|---|---|---|---|---|---|---|---|
| `itu-nnp-index` | ITU National Numbering Plans index | global | numbering_plan_index | document_only | ITU terms of use | official_index_reachable | metadata_only |
| `libphonenumber-carrier` | libphonenumber carrier prefix mapping | global | carrier_reference | library_data | Apache-2.0 | reference_verified | not_imported |
| `libphonenumber-metadata` | libphonenumber PhoneNumberMetadata | global | numbering_plan_reference | open_download | Apache-2.0 | reference_verified_against_sha256 | not_imported |
| `libphonenumber-short` | libphonenumber ShortNumberMetadata | global | short_code_reference | open_download | Apache-2.0 | reference_verified_against_sha256 | not_imported |
| `numx-emergency-reference` | NumX emergency and service reference list | global | emergency_reference | local_file | Apache-2.0 AND ODbL-1.0 | reference_not_officially_confirmed | not_imported |
| `osm-bulk` | OpenStreetMap bulk extracts (Geofabrik/BBBike) | global | poi_directory | open_download | ODbL-1.0 | blocked_by_robots | blocked_by_robots |
| `osm-overpass` | OpenStreetMap Overpass API | global | poi_directory | public_api | ODbL-1.0 | blocked_by_robots | blocked_by_robots |
| `phoneblock` | PhoneBlock spam-report API and community data | global | reputation | public_api | CC BY-NC-SA 4.0 (content) / GPL-3.0 (code) | restricted | blocked_by_robots |
| `wikidata-entitydata` | Wikidata EntityData JSON | global | organization_directory | public_api | CC0-1.0 | community_curated | not_imported |
| `wikidata-sparql` | Wikidata Query Service (SPARQL) | global | organization_directory | public_api | CC0-1.0 | blocked_by_robots | blocked_by_robots |
| `wikipedia-regulators` | Wikipedia: List of telecommunications regulatory bodies | global | regulator_discovery_index | public_search | CC BY-SA 4.0 | discovery_only | metadata_only |

## مصادر الشبكات العالمية

| Source ID | Name | Country/entity | Category | Access | License | Verification | Import status |
|---|---|---|---|---|---|---|---|
| `itu-global-att-cingular` | ITU global numbering plan — AT&T Cingular Wireless Network | GLOBAL:ATT_CINGULAR | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |
| `itu-global-china-telecom-sat` | ITU global numbering plan — China Telecommunications Corporation Satellite Communications Branch | GLOBAL:CHINA_TELECOM_SAT | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |
| `itu-global-globalstar` | ITU global numbering plan — Globalstar Inc. | GLOBAL:GLOBALSTAR | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |
| `itu-global-inmarsat` | ITU global numbering plan — Inmarsat | GLOBAL:INMARSAT | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |
| `itu-global-iridium` | ITU global numbering plan — Iridium Satellite LLC | GLOBAL:IRIDIUM | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |
| `itu-global-mcp` | ITU global numbering plan — Maritime Communications Partner (MCP) | GLOBAL:MCP | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |
| `itu-global-thuraya` | ITU global numbering plan — Thuraya | GLOBAL:THURAYA | global_numbering_plan | document_only | ITU terms of use | official_document_reachable | metadata_only |

## المصادر الوطنية

عدد المصادر المرتبطة بدولة/منطقة: 442. القائمة الكاملة في `data/source-catalog/country-source-catalog.csv`، وتقرير مستقل لكل كيان في `docs/country-source-research/`.

## مصادر محجوبة أو غير متاحة (مفهرسة ولا تُستورد)

| المصدر | السبب | الحالة |
|---|---|---|
| `fcc-opendata` | DNS resolution failed in the execution environment on 2026-10-28. No data was fetched or imported. | temporarily_unavailable |
| `ftc-dnc` | The documented URL returned HTTP 404 on 2026-10-28. No data was fetched or imported. | temporarily_unavailable |
| `osm-bulk` | The tested extract hosts disallow .osm.pbf/.gz downloads in robots.txt. No bulk OSM file is downloaded or imported. | blocked_by_robots |
| `osm-overpass` | overpass-api.de/robots.txt disallows /api/. Bulk Geofabrik and BBBike files are also disallowed by their robots.txt. No OSM records are imported; the source remains catalogued. | blocked_by_robots |
| `phoneblock` | The API requires authentication and its data endpoints are disallowed by robots.txt. The content licence is non-commercial and share-alike. No reports are imported. | blocked_by_robots |
| `wikidata-sparql` | query.wikidata.org/robots.txt disallows /sparql. The endpoint is recorded for transparency and is not used by any connector. | blocked_by_robots |

## ما لم يُفعل

- لم تُنزَّل وثائق ITU الكاملة ولم تُعاد توزيعها؛ سُجلت الروابط والبيانات الوصفية.
- لم تُستخدم نقاط Wikidata SPARQL أو Overpass أو ملفات OSM الكبيرة لأن robots.txt يمنعها.
- لم تُستخدم واجهات PhoneBlock لأنها تتطلب مصادقة وتمنعها robots.txt، وترخيصها غير تجاري.
- لم تُستورد بيانات FCC/FTC لأن نقطة FCC فشلت في DNS ونقطة FTC أعادت HTTP 404.
- لم يُجرَ بحث مستقل بلغة كل دولة على حدة؛ الفهارس الحالية مرجعية واكتشافية وليست تحققاً وطنياً كاملاً.
