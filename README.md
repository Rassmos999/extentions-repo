# Rassmos Extensions (Aniyomi / Tadami)

<p align="center">
  <b>A curated, maintained Aniyomi-compatible anime extension repository.</b><br/>
  Supports Arabic (AR), English (EN), and Multi/Language-independent (ALL) video sources.<br/>
  Compatible with <b>Aniyomi</b>, <b>Tadami</b>, and forks running Extension API <b>14.x</b>.
</p>

<p align="center">
  <a href="https://github.com/Rassmos999/extentions-repo"><img src="https://img.shields.io/badge/Extensions-100%2B-blue?style=flat-square" alt="Extensions Count"></a>
  <a href="https://github.com/Rassmos999/extentions-repo"><img src="https://img.shields.io/badge/API-14.x-orange?style=flat-square" alt="API Version"></a>
  <a href="https://github.com/Rassmos999/extentions-repo"><img src="https://img.shields.io/badge/Status-Maintained-brightgreen?style=flat-square" alt="Status"></a>
</p>

---

## 🚀 إضافة المستودع للتطبيق (Quick Add)

### 1. الإضافة بضغطة واحدة على أندرويد (One-Tap Add)

اضغط على الرابط التالي مباشرة من هاتفك لفتح التطبيق وإضافة المستودع تلقائياً:

👉 **[إضافة المستودع إلى Aniyomi / Tadami](aniyomi://add-repo?url=https%3A%2F%2Fraw.githubusercontent.com%2FRassmos999%2Fextentions-repo%2Fmain%2Findex.min.json)** 👈

> **ملاحظة:** الرابط يفتح نافذة التأكيد الرسمية داخل التطبيق، ولا يقوم بتخطي أذونات نظام أندرويد أو التثبيت التلقائي الصامت.

---

### 2. الإضافة اليدوية (Manual Setup)

انسخ الرابط التالي:

```text
https://raw.githubusercontent.com/Rassmos999/extentions-repo/main/index.min.json
```

ثم توجه داخل التطبيق إلى:
- **تطبيق Aniyomi:**  
  `الإعدادات (Settings) ➔ التصفح (Browse) ➔ مستودعات إضافات الأنمي (Anime extension repos)`  
  اضغط على زر `+` وألصق الرابط.
- **تطبيق Tadami:**  
  `المزيد (More) ➔ الإعدادات (Settings) ➔ التصفح (Browse) ➔ متجر الإضافات (Extension Stores - Anime)`  
  وألصق الرابط.

بعد الإضافة، توجه إلى **التصفح ➔ إضافات الأنمي (Browse ➔ Anime Extensions)** وستظهر جميع الإضافات المحدثة جاهزة للتثبيت فوراً.

---

## 🌐 تصفح الإضافات عبر المتصفح (Web Catalog)

يحتوي المستودع على صفحة ويب متجاوبة وسريعة في [`index.html`](./index.html) تتيح:
- البحث المباشر في جميع الإضافات المتاحة.
- الفلترة حسب اللغة (`Arabic`, `English`, `All / Multi`).
- تنزيل ملفات الـ APK الموقعة مباشرة دون الحاجة لفتح التطبيق.

---

## 🔑 التوقيع والتحديثات (Signing & Updates)

كافة ملفات الـ APK المنشورة في المستودع موقعة رقمياً بالبصمة التالية:

```text
SHA-256: 84300648046b4e4d24e940d892207fc94d6c723c120fddb5450b222c4e8d3a4d
```

> ⚠️ **ملاحظة أمان:** نظام أندرويد لا يسمح بتحديث أي إضافة موقعة بمفتاح مختلف. إذا واجهت رسالة تعارض توقيع (Signature conflict) أو ظهرت الإضافة كـ **Local**، قم بإلغاء تثبيت النسخة القديمة مرة واحدة ثم أعد تثبيتها من هذا المستودع.

---

## 📂 هيكلية المستودع (Repository Structure)

```text
├── apk/            # مجلد ملفات الـ APK الرسمية والموقعة فقط
├── icon/           # أيقونات الإضافات المعتمدة
├── src/            # الكود المصدري لكافة الإضافات (Kotlin)
│   ├── all/        # مصادر عامة ودولية (PornHub, Jable, Stremio, etc.)
│   ├── ar/         # مصادر عربية (Witanime, Anime4up, Arabseed, etc.)
│   └── en/         # مصادر إنجليزية
├── core/           # المكتبة المركزية (الأدوات المساعدة ومستخرجات الفيديو)
├── lib/            # مستخرجات الفيديو المساعدة (Dood, Streamtape, Okru, etc.)
├── index.json      # فهرس الإضافات الكامل بصيغة مقروءة
├── index.min.json  # الفهرس المصغر الموجه للتطبيق
├── repo.json       # هوية المستودع وبصمة التوقيع
├── rebuild_index.py# سكربت فحص وتحديث الفهرس واستخراج بيانات الـ APK
└── index.html      # صفحة الويب لتصفح وتنزيل الإضافات
```

---

## 🛠️ دليل المطورين (Maintainer Workflow)

### بناء إضافة معينة (Build an Extension):

- **على أنظمة Linux / macOS:**
  ```bash
  ./gradlew :src:all:pornhub:assembleRelease --no-daemon
  ```

- **على أنظمة Windows:**
  ```powershell
  .\gradlew.bat :src:all:pornhub:assembleRelease --no-daemon
  ```

### تحديث الفهرس بعد التجميع (Rebuild Index):
بعد نقل ملفات الـ APK الناتجة إلى مجلد `apk/`:
```bash
python3 rebuild_index.py
```

### معاينة صفحة الويب محلياً:
```bash
python3 -m http.server 8080
```
ثم افتح الرابط `http://127.0.0.1:8080/` في المتصفح.

---

## ⚖️ إخلاء مسؤولية (Disclaimer)

هذا المستودع مستقل تماماً ولا يتبع رسمياً لفريق تطوير تطبيق Aniyomi أو Tadami أو أي جهة استضافة محتوى خارجية. كافة الإضافات مقدمة لأغراض برمجية ومفتوحة المصدر، والمستخدم مسؤول عن استخدامه وفقاً للقوانين المعمول بها.

