# OPERATIONS.md — التشغيل والمراقبة

آخر توليد: 2026-10-28

## التشغيل اليومي

```bash
python3 -m tools.platform.cli status
python3 -m tools.platform.cli check
python3 -m tools.platform.cli coverage
python3 -m tools.platform.cli summary
```

## المراقبة

- `import_jobs`: آخر مهمة، الحالة، الأعداد، الأخطاء.
- `source_registry.last_checked_at` و`last_http_status`: آخر فحص رابط.
- `coverage_matrix.freshness_days`: عمر آخر تحقق.
- `audit_events`: التغييرات المهمة.
- `data/source-catalog/link-check-results.json`: نتائج الفحص التفصيلية.

## النسخ الاحتياطي

```bash
sqlite3 data/platform.db ".backup 'data/backups/platform-$(date +%F).db'"
sha256sum data/backups/platform-*.db
```

احتفظ بنسخة من `data/source-catalog/` و`docs/country-source-research/` مع قاعدة البيانات. لا تضع مفاتيح أو أسراراً في قاعدة البيانات أو المستودع.

## التشخيص

```bash
sqlite3 data/platform.db 'PRAGMA integrity_check;'
sqlite3 data/platform.db 'SELECT status, COUNT(*) FROM import_jobs GROUP BY status;'
sqlite3 data/platform.db 'SELECT source_id, import_status, last_error FROM source_registry WHERE last_error IS NOT NULL;'
```

## القيود المعروفة في هذه البيئة

- اختبار التطبيق على جهاز/محاكي Android لم ينجح: جهاز ADB رفض الاتصال، والمحاكيان `Pixel_API37` و`Pixel_API35` انهارا بـ SIGSEGV/exit 139. اختبارات Kotlin وبناء APK يعملان في CI.
- Wikidata SPARQL وOverpass وملفات OSM الكبيرة محجوبة بـ robots.txt.
- PhoneBlock يتطلب مصادقة ويمنع robots.txt نقاط البيانات، وترخيصه غير تجاري.
- FCC Open Data فشل في DNS، وFTC DNC أعاد 404.
- البحث الوطني المستقل بكل لغة لم يكتمل؛ الفهرس الحالي مرجعي/اكتشافي.

## التكلفة

البرنامج والمصادر المستخدمة مجانية. الاستضافة العامة والتخزين والنقل والنسخ الاحتياطي قد تكون لها تكلفة. لا توجد واجهة مدفوعة في المسار الأساسي.

## أعداد حالية

- المصادر: 460
- مهام الاستيراد: 7
- صفوف الأصل: 46914
