# DATABASE_SCHEMA.md — المخطط المنطقي

إصدار المخطط: `2`. قاعدة التنفيذ الحالية: SQLite (`data/platform.db`).

## المبدأ

المخطط تحليلي/مرجعي وليس قاعدة مشتركين. لا يحتوي حالة نشاط خط، ولا هوية مشترك، ولا ادعاءً بالمشغل الحالي بعد نقل الرقم. كل حقيقة مهمة مرتبطة بمصدر وتاريخ وترخيص وحدود.

## الجداول

| الجدول | الغرض | مفتاح الربط |
|---|---|---|
| `countries_and_regions` | الدول والمناطق والشبكات العالمية | `entity_id`, `iso2` |
| `source_registry` | سجل المصادر والوصول والتراخيص | `source_id` |
| `source_datasets` | مجموعات البيانات وفئاتها | `dataset_id` → `source_id` |
| `source_releases` | الإصدارات والبصمات | `release_id` → `source_id` |
| `numbering_plans` | خطط الترقيم | `plan_id` → `source_id` |
| `number_ranges` | نطاقات/أنماط الأرقام | `range_id` → `plan_id`, `source_id` |
| `phone_numbers` | أرقام ظهرت فعلاً في مصدر مسموح | `phone_id` → `source_id` |
| `directory_entries` | أرقام مؤسسات منشورة | `entry_id` → `source_id` |
| `carrier_observations` | تخصيص النطاق/المشغل | `observation_id` → `source_id` |
| `reputation_reports` | بلاغات السمعة | `report_id` → `source_id` |
| `regulator_entries` | الجهات التنظيمية المكتشفة | `regulator_id` → `source_id` |
| `record_provenance` | أصل كل حقل/سجل | `provenance_id` |
| `provenance_history` | النسخ السابقة عند التغيير | `history_id` |
| `import_jobs` | مهام ETL وأعدادها | `job_id` |
| `audit_events` | تدقيق التغييرات | `event_id` |
| `coverage_matrix` | التغطية لكل كيان/فئة | (`country_iso2`,`data_category`) |

## العلاقات المهمة

- `number_ranges.source_id` → `source_registry.source_id`.
- `number_ranges.source_release_id` → `source_releases.release_id`.
- `record_provenance.source_id` → `source_registry.source_id`.
- `record_provenance.import_job_id` → `import_jobs.job_id`.
- `regulator_entries.discovery_source_id` يشير إلى فهرس الاكتشاف، بينما `source_id` يشير إلى المصدر المرشح.
- `countries_and_regions.entity_id` يستخدم `COUNTRY:<ISO2>` أو `GLOBAL:<NAME>`.

## الفهارس

الفهارس تغطي: الدولة/النوع في النطاقات، الدولة في الأرقام والأدلة، البادئة في ملاحظات المشغل، الدولة في الجهات التنظيمية، والكيان في الأصل. يوجد فهرس فريد على (`entity_type`,`entity_id`,`field_name`,`source_id`,`source_record_id`) لمنع تضاعف الأصل عند إعادة الاستيراد.

## أعداد حالية

- الكيانات: 252 + 7
- المصادر: 460
- خطط الترقيم: 484
- نطاقات الأرقام: 18325
- ملاحظات المشغل: 28138
- الجهات التنظيمية: 209
- الأدلة المؤسسية: 4
- صفوف الأصل: 46914

## ملاحظات PostgreSQL

المخطط منطقي ومتوافق مع PostgreSQL بعد تحويلات بسيطة: `INTEGER PRIMARY KEY AUTOINCREMENT` → `BIGSERIAL`, `TEXT` → `TEXT`, `REAL` → `DOUBLE PRECISION`, و`ON CONFLICT` مدعوم. لا حاجة إلى PostGIS إلا لاستعلامات مكانية فعلية. لم يُنشر خادم PostgreSQL لأن المشروع الحالي لا يحتويه، وSQLite يلبي التشغيل المحلي وCI دون تكلفة.
