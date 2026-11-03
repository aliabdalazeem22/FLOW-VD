# FlowDL — محرّك تحميل مُجزّأ (Segmented Downloader) لنظام Android

تطبيق Android أصلي مكتوب بالكامل بـ Kotlin + Jetpack Compose، يعيد بناء منطق
النقل والجدولة الموجود في محرّك SnapTube (`com.wandoujia.mtdownload`) بصورة
مستقلة، مع تحكم يدوي مستقل بسرعة كل ملف، وإيقاف/استئناف فردي، وواجهة حديثة
Edge-to-Edge، وبدون أي إعلانات أو مكتبات تتبّع.

## المزايا

| الميزة | التنفيذ |
|---|---|
| تحميل متعدّد الأجزاء | طلبات `Range` متوازية على نفس الملف عبر `RandomAccessFile` |
| جدولة ديناميكية | خيط منسّق + عمّال يسرقون نصف أكبر جزء متبقٍ عند توفّر عامل حر |
| جدولة المهام | 3 مهام نشطة كحد أقصى، والباقي في طابور FIFO |
| تحكم بالسرعة لكل ملف | Token Bucket مستقل لكل مهمة، شريط تمرير من 64KB/s إلى 32MB/s أو بلا حد |
| إيقاف/استئناف فردي | `disconnect()` فوري + حفظ مؤشرات كل جزء في JSON |
| استئناف بعد موت العملية | `tasks.json` ذرّي يستعيد كل الأجزاء والمؤشرات |
| كشف الحجم ودعم Range | `GET bytes=0-0` ثم `HEAD` ثم بثّ مباشر كخطة أخيرة |
| خدمة أمامية | `dataSync` مع إشعار تقدّم وأزرار تحكم |
| بدون إعلانات/تتبّع | لا AdMob ولا Firebase ولا أي SDK إعلاني |

## بنية المشروع

```
FlowDL/
├── .github/workflows/build-apk.yml  # بناء APK تلقائياً على GitHub
├── .gitignore
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── app/build.gradle.kts
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/…                       # أيقونات، ثيمات، FileProvider
    └── java/com/example/flowdl/
        ├── App.kt                  # قناة الإشعارات + تهيئة المحرّك
        ├── MainActivity.kt         # Edge-to-edge + إذن الإشعارات
        ├── core/
        │   ├── Models.kt           # DownloadTask / Connection / TaskStatus
        │   ├── Http.kt             # إعادة توجيه يدوية + Probe + Range
        │   ├── RateLimiter.kt      # Token Bucket لكل مهمة
        │   ├── TaskRuntime.kt      # المنسّق + العمّال + سرقة الأجزاء
        │   ├── TaskStore.kt        # حفظ/استعادة JSON ذرّي
        │   └── DownloadManager.kt  # الطابور + الحالة + واجهة التحكم
        ├── service/DownloadService.kt
        └── ui/…                    # Compose: Home, TaskCard, AddSheet, Theme
```

## آلية العمل (ملخّص)

1. **الاستكشاف:** `Http.probe` يرسل `Range: bytes=0-0`. الاستجابة `206`
   و`Content-Range: bytes 0-0/N` تعطي الحجم الكلي وتؤكد دعم الأجزاء. عند
   الفشل يُجرّب `HEAD`، وإلا يتحوّل الملف إلى بثّ واحد.
2. **التقسيم:** `N = min(threads, ceil(size / 256KB))`، ثم تُبنى أجزاء
   `[start, end)` متساوية ومؤشر `current = start`.
3. **الجدولة:** كل عامل يأخذ جزءاً غير مملوك. إذا لم يجد، يسرق **نصف**
   أكبر جزء متبقٍ (بحد أدنى 256KB) بتقليص `end` الخاص بالمالك وإنشاء جزء جديد.
   هذا هو نفس سلوك `Dispatcher` في SnapTube.
4. **النقل:** كل نافذة طلب محدودة بـ 8MB، مع `Range: bytes=current-(end-1)`،
   والتحقق من تطابق بداية `Content-Range` والحجم الكلي. تُكتب البيانات عبر
   `seek + write` داخل قفل ملف واحد.
5. **الاستئناف:** عند الإيقاف تُقطع الاتصالات ويُحفظ `connections[]`
   و`downloadedBytes` في `tasks.json` (كتابة ذرّية عبر ملف مؤقت + rename).
6. **الحد من السرعة:** بعد كل قراءة 64KB يُستهلك رصيد من دلو الرموز الخاص
   بالمهمة؛ تجاوز الحدّ يُنيم الخيط كسور ثانية فقط، ما يجعل الاستجابة فورية.
7. **الاكتمال:** عند بلوغ كل المؤشرات نهاياتها يُغلق الملف، يُضبط طوله على
   الحجم الكلي، ثم يُعاد تسمية `.flowpart` إلى الاسم النهائي.

## البناء وتوليد APK

### المتطلبات
- JDK 17 (مضمّن مع Android Studio أو `sudo apt install openjdk-17-jdk`).
- Android SDK Platform 35 + Build-Tools 35.
- Android Studio حديث (Ladybug أو أحدث) **أو** Gradle 8.9 عبر الـ wrapper.

### خطوات Gradle (سطر أوامر)

```bash
# 1) اضبط مسار الـ SDK
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

# 2) من داخل مجلد المشروع
cd FlowDL
chmod +x gradlew

# 3) بناء نسخة التطوير
./gradlew assembleDebug

# الناتج:
# app/build/outputs/apk/debug/app-debug.apk
```

### البناء داخل Android Studio
1. `File → Open` ثم اختر مجلد `FlowDL`.
2. انتظر مزامنة Gradle (سينشئ `local.properties` تلقائياً).
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`.

### نسخة الإصدار الموقّعة

```bash
keytool -genkeypair -v -keystore flowdl-release.jks \
  -alias flowdl -keyalg RSA -keysize 2048 -validity 10000

./gradlew assembleRelease
# ثم وقّع الناتج غير الموقّع:
$ANDROID_HOME/build-tools/35.0.0/apksigner sign \
  --ks flowdl-release.jks \
  --out flowdl-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

أو أضف `signingConfigs` في `app/build.gradle.kts` ليقوم Gradle بالتوقيع تلقائياً.

### التثبيت
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## توليد APK تلقائياً على GitHub Actions

المشروع يحتوي على `.github/workflows/build-apk.yml` جاهزًا. بعد رفع الكود إلى
GitHub سيبني الـ workflow نسخة Debug عند كل `push`، ويستطيع بناء نسخة Release
عند تشغيله يدويًا أو عند إنشاء tag يبدأ بـ `v`.

### 1) رفع المشروع

```bash
cd FlowDL
git init
git add .
git commit -m "FlowDL: segmented downloader"
git branch -M main
git remote add origin https://github.com/<USER>/<REPO>.git
git push -u origin main
```

> الملفات الناتجة عن البناء (`.gradle/`, `build/`, `*.jks`, `local.properties`)
> مستثناة مسبقًا في `.gitignore`.

### 2) تحميل الـ APK

1. افتح المستودع على GitHub ثم تبويب **Actions**.
2. اختر آخر تشغيل لـ **Build APK**.
3. من قسم **Artifacts** نزّل `FlowDL-debug`، وفكّ الضغط للحصول على `app-debug.apk`.

### 3) بناء Release موقّع (اختياري)

أنشئ keystore محليًا ثم حوّله إلى Base64:

```bash
keytool -genkeypair -v -keystore flowdl-release.jks \
  -alias flowdl -keyalg RSA -keysize 2048 -validity 10000

base64 -w 0 flowdl-release.jks > keystore.b64
```

أضف في **Settings → Secrets and variables → Actions** القيم التالية:

| السرّ | القيمة |
|---|---|
| `KEYSTORE_BASE64` | محتوى `keystore.b64` |
| `KEYSTORE_PASSWORD` | كلمة مرور الـ keystore |
| `KEY_PASSWORD` | كلمة مرور المفتاح (غالبًا نفسها) |
| `KEY_ALIAS` | `flowdl` |

بعدها شغّل الـ workflow يدويًا من **Actions → Build APK → Run workflow**.
سيُنشئ `FlowDL-release-unsigned.apk` دائمًا، وإذا وُجدت الأسرار سيُنشئ
`FlowDL-release.apk` موقّعًا ومُتحقَّقًا عبر `apksigner verify`.

### 4) إصدار تلقائي عند tag

```bash
git tag v1.0.0
git push origin v1.0.0
```

سيبني الـ workflow نسخة Release ويرفعها في صفحة **Releases** مع ملاحظات
التغييرات.

## ملاحظات تقنية

- ملفات التحميل تُخزَّن في مجلد التطبيق الخاص
  `/Android/data/com.example.flowdl/files/Download/` (بلا أذونات تخزين).
  زر الفتح يستخدم `FileProvider` لمشاركة الملف مع تطبيقات أخرى.
- التطبيق يطلب `POST_NOTIFICATIONS` على Android 13+ لعرض إشعار التقدّم.
- `usesCleartextTraffic="true"` ضروري لروابط `http://` المباشرة؛ أزله إن
  أردت HTTPS فقط.
- المحرّك يتعامل مع روابط الملفات المباشرة (HTTP/HTTPS + Range). لا يفكّ
  روابط YouTube/HLS/DASH المشفّرة، لأن ذلك خارج نطاق محرّك النقل.
- عدد الاتصالات الأقصى لكل مهمة 8، والحد الأقصى للمهام النشطة 3 (قابلان
  للتعديل من `Http.MAX_THREADS` و`DownloadManager.MAX_ACTIVE`).

## Media link inspection (experimental)

The add-download sheet includes **Analyze media / find qualities**. The current experimental extractor can:

- Accept directly accessible HTTP(S) media file URLs.
- Inspect public HTML pages for common Open Graph / HTML5 video and audio URLs.
- Read HLS master playlists and list publicly advertised variants and resolutions.
- Detect MPEG-DASH (`.mpd`) manifests and explain the current limitation.

### Important limitations

This is not yet a full SnapTube-equivalent platform extractor. Sites that hide stream URLs in JavaScript, require account/session tokens, use DRM, or change their internal APIs need dedicated, maintained adapters. HLS/DASH variants are identified but are not yet downloaded and remuxed into a single playable media file by the existing byte-range download engine. The app does not bypass DRM, access controls, or paywalls. Always respect platform terms and content rights.

The Android build was not verified in this environment because the Gradle wrapper could not download Gradle (`services.gradle.org` was unreachable). Run `./gradlew :app:assembleDebug` in an environment with the required Gradle distribution and Maven repositories available.

## Multi-platform media extraction engine

FlowDL now integrates the embedded `yt-dlp-android` engine (`dev.ffmpegkit-maintained:yt-dlp-android:2.0.2`) for platform-aware metadata extraction. The extractor asks yt-dlp for metadata without downloading the media, then maps available audio/video renditions into quality labels containing resolution, container, track type, bitrate, and approximate size. The original page URL and yt-dlp format ID are retained so a selected format is resolved by the extractor again at download time rather than relying on a short-lived CDN URL.

A conservative fallback remains for direct HTTP media URLs and public HTML/HLS/DASH references. The fallback does not claim to extract protected streams. DRM, private or login-gated media, expired signatures, platform anti-bot changes, and unavailable codecs can still prevent extraction or downloading. Site coverage depends on the bundled yt-dlp version and may require dependency updates over time.

The embedded Python/native runtime currently supports `arm64-v8a` and `x86_64`; the Android module filters builds to those ABIs. The dependency substantially increases app size. This engine integration has not been build-verified in this environment because Gradle distribution downloads are blocked by network DNS restrictions.

## Royal UI refresh (November 2026)

This revision refreshes the visual identity and home-screen experience:

- Deep navy / electric violet / aqua brand palette with consistent light and dark themes.
- New FlowDL launcher icon artwork for adaptive and legacy Android launcher icons.
- Branded download-studio hero card with live aggregate throughput, active/saved counts, and a direct Add link action.
- Search downloads by filename or source URL.
- Quick filters for All, Active, Completed, and Paused/Failed tasks.
- Improved empty and no-results states.

The UI changes do not claim additional platform-extraction support; extraction capability remains dependent on the configured media engine and each source platform.
