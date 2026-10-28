# DATA_INGESTION.md — تشغيل ETL

آخر توليد: 2026-10-28

## المتطلبات

```bash
python3 -m pip install -r tools/metadata/requirements.txt
python3 -m tools.platform.cli catalog
python3 -m tools.platform.cli import-all
python3 -m tools.platform.cli coverage
python3 -m tools.platform.cli summary
python3 -m tools.platform.cli reports
python3 -m tools.platform.cli docs
```

قاعدة البيانات: `data/platform.db` (SQLite). لا تحتاج خادماً. المخطط منطقي وقابل للنقل إلى PostgreSQL؛ راجع `DATABASE_SCHEMA.md`.

## الموصلات المنفذة

| الموصل | المصدر | الجدول الهدف | وضع الاستيراد |
|---|---|---|---|
| `libphonenumber-ranges` | libphonenumber PhoneNumberMetadata | `number_ranges` | نطاقات مرجعية |
| `libphonenumber-short-numbers` | ShortNumberMetadata | `number_ranges` | أرقام قصيرة |
| `numx-emergency-reference` | `emergency.json` | `number_ranges` | طوارئ/خدمات مرجعية |
| `libphonenumber-carrier-prefixes` | carrier data | `carrier_observations` | تخصيص نطاق فقط |
| `itu-nnp-metadata` | ITU seed | `numbering_plans` | بيانات وصفية |
| `wikipedia-regulator-discovery` | Wikipedia regulator table | `regulator_entries` | اكتشاف فقط |
| `wikidata-entitydata-directory` | Wikidata EntityData sample | `directory_entries` | عينة مؤسسية |

## أوامر الاستيراد

```bash
# استيراد كل الموصلات المسموحة
python3 -m tools.platform.cli import-all

# استيراد موصل واحد مع تجربة جافة
python3 -m tools.platform.cli import libphonenumber --dry-run

# استيراد محدود لعينة
python3 -m tools.platform.cli import wikidata --limit 25

# كشف روابط ITU ومستنداتها (شبكة)
python3 -m tools.platform.cli discover-itu --limit 20

# عينة Wikidata المؤسسية (شبكة)
python3 -m tools.platform.cli discover-wikidata

# فحص الروابط (شبكة)
python3 -m tools.platform.cli linkcheck --workers 4
```

## ضمانات التنفيذ

- كل استيراد يعمل داخل savepoint؛ فشل سجل يُرفض ولا يلغي بقية السجلات.
- فشل قاتل (ملف تالف/انقطاع) يتراجع عن كل تغييرات المهمة.
- `--dry-run` ينفذ التحقق والتحويل ثم يتراجع، ويترك تقرير المهمة.
- إعادة الاستيراد تستخدم `ON CONFLICT` على مفاتيح المصدر، فلا تتكرر السجلات.
- تغير قيم الأصل يُحفظ في `provenance_history` قبل تحديث الصف الحالي.
- كل مهمة تُسجل في `import_jobs` مع الأعداد والأخطاء، وكل تغيير مهم في `audit_events`.

## أعداد آخر تشغيل

- خطط الترقيم: 484
- نطاقات الأرقام: 18325
- ملاحظات المشغل: 28138
- الجهات التنظيمية: 209
- الأدلة المؤسسية: 4
- صفوف الأصل: 46914
- مهام الاستيراد: 7

## إضافة موصل جديد

1. أضف المصدر إلى `catalog.py` مع الترخيص وحالة المراجعة.
2. نفّذ `records`, `validate`, `to_row`, `provenance` وفق `connectors/base.py`.
3. أضف اختبارات بملف ثابت (fixture) لا تعتمد على الشبكة.
4. سجّل الموصل في `cli.py` وفي هذا الملف.
5. شغّل `import <id> --dry-run` ثم تحقق من `import_jobs` و`record_provenance`.

## الجدولة

لا تستخدم دورة موحدة. أمثلة مقترحة:

- libphonenumber: عند إصدار جديد مثبت في `sources.lock.json`.
- ITU/regulators: مراجعة ربع سنوية أو عند تغيير الجهة.
- Wikidata sample: شهرياً بحد أقصى، مع احترام robots وحدود المعدل.
- linkcheck: أسبوعياً، مع تخزين النتائج وعدم اعتبار الفشل المؤقت حذفاً للبيانات.

## ما لا يفعله ETL

- لا ينزّل مجموعات عالمية كاملة افتراضياً.
- لا يتجاوز مصادقة أو robots.txt.
- لا ينشئ سجلات لأرقام محتملة لم تظهر في مصدر.
- لا يدمج تعارضات المؤسسات تلقائياً دون مراجعة.
