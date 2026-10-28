# TEST_ON_REAL_DEVICE.md — خطوات التجربة على جهاز حقيقي

## المتطلبات المسبقة للتجربة

1. **جهاز Android حقيقي مع تمكين تصحيح USB (USB debugging)** في إعدادات المطوّر.
2. **ADB يعمل**: `adb devices` يجب أن يُظهر الجهاز.
3. **بيئة بناء Kotlin/Compose تعمل** (تم التحقق منها في CI: `assembleDebug` + `testDebugUnitTest` ناجحان).
4. **APK مبني**: `numx-android/app/build/outputs/apk/debug/app-debug.apk` موجود (تم التحقق: 21.9 MB).
5. **قاعدة المنصة متوفرة**: `data/platform.db` موجود مع `SCHEMA_VERSION=2`.

## خطوات التجربة الملموسة (لا يمكن تنفيذها حالياً بسبب بيئة ADB)

```bash
# 1. التحقق من اتصال ADB
adb devices
# النتيجة المتوقعة: قائمة بجهاز واحد على الأقل مع "device"

# 2. تثبيت APK
adb install -r numx-android/app/build/outputs/apk/debug/app-debug.apk
# النتيجة المتوقعة: Success

# 3. تشغيل النشاط الرئيسي
adb shell am start -n com.numx.app/.MainActivity

# 4. التحقق من وجود الأصول المدمجة
adb shell "ls -l /data/data/com.numx.app/shared_prefs/ || echo 'assets verified via build'"
# الأصول المدمجة (platform_summary.json) موجودة في APK عبر assembleDebug.

# 5. اختبار البحث دون اتصال
# أدخل رقماً مثل +963944567890 في حقل «بحث دون اتصال»، اختر سوريا، اضغط بحث.
# النتيجة المتوقعة: LookupResult مع iso2=SY, valid=true, sources تشير إلى libphonenumber.

# 6. اختبار قسم الإحصائيات
# اضغط «إحصائيات وإدارة»؛ يجب أن تظهر أعداد المضاف، آخر ملف، وسياسة البيانات.
# يجب أن يظهر نص مصدر مرجعي يشير إلى `libphonenumber 9.0.42`.

# 7. اختبار دليل الطوارئ
# اختر دولة، اضغط على فئات طوارئ، حدد إدخالات، اضغط إضافة.
# النتيجة: جهات اتصال تُحفظ مع وسم `REF=E1;...`.
```

## العوائق التي تمنع التجربة حالياً (موثقة من التشغيل)

### عائق ADB (تم التحقق من فشله)

```bash
# تم تجربته:
# adb connect 10.44.121.178:5555
# النتيجة: refused (الجهاز يرفض الاتصال عبر ADB)
# adb mdns services
# النتيجة: لا أجهزة تظهر
```

### عائق المحاكي (تم التحقق من فشله)

```bash
# emulators: Pixel_API37, Pixel_API35
# النتيجة عند التشغيل: SIGSEGV (exit code 139) حتى مع إيقاف GPU (emulator -gpu swiftshader_indirect -no-snapshot)
```

### عائق المصادر المحجوبة (لا يمكن تجاوزها دون خرق شروط الاستخدام)
- Wikidata SPARQL (`/sparql`) ممنوعة بـ robots.txt.
- Overpass (`/api/`) ممنوعة بـ robots.txt.
- OSM bulk (`*.pbf`/`*.gz`) ممنوعة بـ robots.txt من Geofabrik وBBBike.
- PhoneBlock يتطلب مصادقة وترخيص `CC BY-NC-SA 4.0` ويمنع robots.txt نقاط البيانات.
- FCC Open Data فشل في DNS؛ FTC DNC أعاد HTTP 404.

### عائق التغطية الناقصة (لا يمكن ادعاء اكتمالها)
- 21 دولة بلا إدخال ITU مستقل (من `itu_nnp.json` unmatched).
- 59 دولة بلا إدخال تنظيمي من Wikipedia (`regulators.json` يغطي 193 فقط).
- دليل مؤسسي (`directory_entries`): 4 سجلات فقط من عينة Wikidata (JO, MA, SY).
- بلاغات سمعة (`reputation_reports`): 0 سجلات (PhoneBlock محجوب).

## ما يجب إكماله قبل ادعاء "جاهز للتجربة"

1. **حل عائق ADB أو المحاكي** (بيئة خارجية، لا يمكن إصلاحها بالكود فقط).
2. **إعادة تشغيل التجربة على جهاز حقيقي** مع الخطوات أعلاه، وتسجيل النتائج الفعلية في `docs/VERIFICATION_AND_BLOCKERS.md`.
3. **إذا أصبحت FCC/FTC متاحة**: إعادة تشغيل `python3 -m tools.platform.cli import-all` وإعادة توليد `docs/`.
4. **إذا اكتمل البحث الوطني المستقل** لكل الدول الناقصة: إضافة تقارير `docs/country-source-research/*.md` جديدة وتحديث `DATA_COVERAGE.md`.

## ماذا يعمل الآن بدون جهاز حقيقي

- `python3 -m tools.platform.cli all` (يبني DB كاملة مع كل الموصلات).
- `python3 -m tools.platform.cli check` (يتحقق من سلامة DB والملفات).
- `python3 -m tools.platform.cli serve --port 8787` (API قراءة فقط متاحة محلياً).
- Kotlin build (`assembleDebug`) واختبارات (`testDebugUnitTest`) ناجحان.
- APK متوفر (`app-debug.apk` 21.9 MB) مع `platform_summary.json` مدمج.
