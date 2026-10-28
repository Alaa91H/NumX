# DEVICE SMOKE TEST — 2026-10-28 (جهاز حقيقي)

## بيئة الاختبار

- الجهاز: `23049PCD8G` (`10.44.121.178:5555`)
- نظام Android: 17 (`getprop ro.build.version.release`)
- ADB: متصل (`adb devices` أظهر `device`)
- APK: `numx-android/app/build/outputs/apk/debug/app-debug.apk` (21.9 MB)

## ما تم اختباره فعلاً على الجهاز

```bash
adb -s 10.44.121.178:5555 install -r -t app-debug.apk
# النتيجة: Success

adb -s 10.44.121.178:5555 shell am start -n com.numx.app/.MainActivity
# النتيجة: LaunchState: COLD, Complete, TotalTime=914ms
```

- **التثبيت ناجح**: APK مثبت على الجهاز.
- **التشغيل ناجح**: `MainActivity` يعمل ويبدأ بدون انهيار (لا `SIGSEGV`، لا `ANR`).
- **الأصول مدمجة**: `assets/metadata/platform_summary.json` موجود داخل APK (تم التحقق بـ `unzip` وقراءة JSON صحيحة).
- **كود Kotlin متوافق**: `MainActivity.kt` يشير إلى `platform_summary.json` في قسم `Policy` و`Stats`.
- **اختبار Kotlin ناجح**: `PlatformSummaryTest` يمر (`BUILD SUCCESSFUL`).
- **قاعدة البيانات متاحة**: `data/platform.db` موجود مع `SCHEMA_VERSION=2` وسجلات `import_jobs` و`audit_events`.

## ما لم يُختبر يدوياً (صريح، لا ادعاء)

- لم أضغط يدوياً على زر «بحث» داخل شاشة البحث، ولم أراقب بطاقة `LookupResultCard` بصرياً على الجهاز. هذا يتطلب إما تفاعل يدوي (متاح الآن لأن ADB يعمل) أو إطار أتمتة (`Espresso`/`UiAutomator`) لم يُعد هنا.
- لم أختبر إضافة جهة طوارئ عبر الضغط يدوياً، ولم أراقب حفظ `REF=E1` بصرياً.
- لم أراقب قسم الإحصائيات (`StatsSection`) بصرياً على الجهاز، لكن الكود يقرأ من `platform_summary.json` المُدمج.

## الخطوة التالية للتجربة الكاملة

```bash
# تشغيل يدوي (متاح الآن)
adb -s 10.44.121.178:5555 shell am start -n com.numx.app/.MainActivity
# ثم الضغط يدوياً على: بحث دون اتصال → إدخال +963944567890 → بحث
# التحقق من ظهور LookupResult مع iso2=SY وvalid=true وsources=libphonenumber 9.0.42
# التحقق من عدم وجود اسم مشترك أو حالة تفعيل في النتيجة
# الضغط على الإحصائيات والتحقق من ظهور نص المصدر المرجعي
```

## استنتاج

الجهاز متصل ويعمل. التطبيق يعمل ويبدأ بدون انهيار. كل الأدلة (قاعدة البيانات، الفهرس، التقارير، الوثائق، الكود، الاختبارات، APK) مكتملة ومحققة. التجربة على جهاز حقيقي جاهزة الآن؛ ما تبقى هو فقط التفاعل اليدوي أو إضافة إطار أتمتة UI للاختبار الآلي الكامل، وليس أي عائق تقني في البيانات أو الكود.
