# API_REFERENCE.md — واجهات API الفعلية

آخر توليد: 2026-10-28

الخادم اختياري وقراءة فقط، مبني على مكتبة Python القياسية فوق SQLite. التطبيق لا يعتمد عليه؛ البحث المحلي يعمل دون إنترنت.

## التشغيل

```bash
python3 -m tools.platform.cli serve --host 127.0.0.1 --port 8787
```

## الضمانات

- استجابات JSON فقط.
- حد 120 طلباً/دقيقة لكل عنوان IP (في الذاكرة).
- لا توجد كتابة أو مصادقة مطلوبة للقراءة العامة.
- لا تُسجل أرقام الهواتف كاملة في سجلات الطلبات.
- كل استجابة تتضمن حدود المعلومة عند انطباقها.
- لا توجد نقطة لإرجاع بيانات مشتركين أو حالة نشاط خط.

## النقاط

| الطريقة | المسار | الوصف |
|---|---|---|
| GET | `/api/v1/health` | صحة الخدمة وإصدار المخطط |
| GET | `/api/v1/phone/lookup?number=...&region=SY` | تحليل رقم مع المصادر والحدود |
| GET | `/api/v1/phone/metadata?number=...` | حقول التحليل الأساسية |
| GET | `/api/v1/phone/carrier?number=...` | المشغل المخصص فقط |
| GET | `/api/v1/phone/reputation?number=...` | البلاغات المتاحة فقط |
| GET | `/api/v1/countries` | قائمة الدول/المناطق |
| GET | `/api/v1/countries/{code}` | تفاصيل دولة وتغطيتها ومصادرها |
| GET | `/api/v1/countries/{code}/sources` | مصادر الدولة |
| GET | `/api/v1/data-sources` | سجل المصادر مع فلاتر |
| GET | `/api/v1/data-sources/{sourceId}/status` | حالة مصدر ومهامه |
| GET | `/api/v1/coverage` | مصفوفة التغطية |
| GET | `/api/v1/imports` | مهام الاستيراد |

## معاملات مشتركة

- `limit` (1–500، افتراضي 50) و`offset` (≥0) لنقاط القوائم.
- `category` لفلترة فئات البيانات.
- `importStatus` لفلترة حالة الاستيراد.
- `country` و`category` لمصفوفة التغطية.

## أمثلة

```bash
curl 'http://127.0.0.1:8787/api/v1/health'
curl 'http://127.0.0.1:8787/api/v1/phone/lookup?number=%2B963944567890'
curl 'http://127.0.0.1:8787/api/v1/countries/SY'
curl 'http://127.0.0.1:8787/api/v1/data-sources?category=mobile&limit=10'
curl 'http://127.0.0.1:8787/api/v1/coverage?country=SY'
```

## مثال استجابة lookup (مختصر)

```json
{
  "validNumberingPlan": true,
  "e164": "+963944567890",
  "countryIso2": "SY",
  "numberType": "MOBILE",
  "allocatedCarrier": {"carrierName": "Syriatel", "notCurrentCarrier": true},
  "lineActive": null,
  "lineActiveNote": "No permitted source in this platform proves that a line is active."
}
```

## أعداد حالية

- المصادر المتاحة عبر API: 460
- الكيانات: 252 + 7
- صفوف التغطية: 2849 (متوقع)

## ما لا يقدمه API

- لا يقدم اسم مشترك أو عنواناً خاصاً.
- لا يثبت نشاط الخط.
- لا يعيد توزيع مصدراً يمنع ترخيصه ذلك.
- لا يستعلم المصادر المحجوبة وقت الطلب.
