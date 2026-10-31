# خطة إصلاح الإكستنشنز

تاريخ الفحص: 2026-10-31. الفحص اتعمل على المواقع الحية: كل extension اتجرب فيه رابط الكتالوج، والـ selectors، والبحث، والفلاتر، وصفحة الحلقة، والسيرفرات على HTML الموقع الحقيقي.

مفيش أي حاجة اترفعت على GitHub، ومفيش commit.

## 1. مشاكل هيكلية في الريبو نفسه

| المشكلة | التأثير | الحل |
|---|---|---|
| ثيمات `lib-multisrc` ناقصة: `animestream` و`wcotheme` و`anikototheme`، والموجود بس `dooplay` | 17 إكستنشن منشورين **مستحيل يتعملهم build** من الريبو: animexin, chineseanime, lmanime, anikoto, animekhor, animenosub, animesogo, aniwave, donghuastream, kotokai, luciferdonghua, wcoanimedub, wcoanimesub, wcoforever, wcofun, wcostream, wcotv | نرجّع الثيمات من الريبو الأصلي (yuzono) لـ `lib-multisrc/` (محتاج موافقتك قبل ما أنزّل الكود) |
| `settings.gradle.kts` فيه 46 موديول بس من 107 منشورين | 61 إكستنشن مش بيتعملهم build ولا بيتختبروا، فبيبوظوا من غير ما حد ياخد باله | نخلي القايمة تتولد من `index.json`، أو على الأقل تضم كل المنشورين |
| الدومين مكتوب ثابت في الكود في أغلب الإكستنشنز | أي نقل للموقع بيوقف الإكستنشن خالص (witanime, animeiat, arabseed, animelek…) | نضيف إعداد "دومين مخصص" زي اللي في TukTukCinema للمواقع اللي بتنقل كتير |
| User-Agent ديسكتوب مفروض في مواقع وراها Cloudflare | كوكي `cf_clearance` بيبقى مربوط بالـ UA بتاع الـ WebView، ولو الـ UA اختلف التحدي بيفضل يتكرر | ما نفرضش UA غير على المواقع اللي مش وراها CF (sexalarab/aflamk1 حالياً مش وراهم تحدي، فالتعديل بتاعك سليم) |

## 2. اللي اتصلح (مستني build على الجهاز)

| الإكستنشن | التصنيف/الكتالوج | الفلترة/البحث | الفيديو | الإصدار |
|---|---|---|---|---|
| **Anime4Up** | ترتيب الحلقات بقى من الأحدث للأقدم (كان مقلوب) | الصفحة 2 من أي فلتر كانت بترجع 404 (`/page/N/` بقت `?page=N`). فلتر "الحالة" كان كله 404 فاتشال واتحط مكانه "القسم" (مترجم/مدبلج). اتضافت تصنيفات ناقصة (ايسيكاي، سباق، طبي) | **مكانش فيه ولا سيرفر شغال**: السيرفرات بقت مشغّل داخلي `Anime4up-S1/S2`، فاتعمله extractor جديد (HLS 360/720/1080 + ترجمة عربي) | 80 → 81 |
| **Animerco** | الدومين `eta` → `eva`. رقم الحلقة كان بيتلزق بأرقام العنوان ("الحلقة 12: … 2" كانت بتطلع 122) | شغال (اتجرب) | **كل السيرفرات كانت بايظة**: الموقع بقى بيلف كل سيرفر في صفحة `/player/?pnonce=`، فبقينا نفك الـ iframe. اتضاف Sibnet وBurstCloud | 59 → 60 |
| **Okanime** | شغال | **البحث كان بيرجع فاضي**: البارامتر `?s=` بقى `?q=` | سيرفرات MegaMax (share4max) مكانتش مدعومة، واتضافت (StreamWish/Lulu/Voe/Mp4upload/Mixdrop/EarnVids) | 28 → 29 |
| **Cimaleek** | Race condition: الحلقات كانت بتتضاف من threads متوازية لليستة عادية (حلقات بتضيع أو كراش). ترتيب الحلقات (1.10 كانت قبل 1.2). `//watch/` | 3 أقسام كانت 404: `aflam-online` و`netflix-movies` و`english-series` (بقوا `-1`). أسماء أقسام الأنمي اتصلحت | (ورا Cloudflare، ما اتختبرش) | 14 → 15 |
| **CimaLight** | **كل الكروت كانت بتفتح زرار "Watch Later"** (`#modal-login-form`) بدل الفيديو. الصفحة التالية مكانتش بتظهر خالص (`?&page=`). الدومين `r.` → `e.` | الـ pagination اتصلح للبحث كمان | **الكود القديم كان بيرجّع صفحات HTML كأنها فيديو**. اتكتب من جديد: بيقرا `data-embed` من الصفحة الوسيطة (Uqload/GoodStream/StreamWish + Universal fallback) | 2 → 3 |
| **TukTukCinema** | الدومين `tuktukhd.com` → `tuktukcinemas.com`. ترتيب الحلقات | شغال (القسم/النوع/المتقدم اتجربوا) | Mp4upload اتضاف (موجود في كل الجودات) | 7 → 8 |
| **Mapple** | الدومين الافتراضي `mapple.rip` → `mapple.fun` (القديم لسه في القايمة) | — | — | 6 → 7 |
| **SexAlArab** | شغال | صفحة 1 من التصنيف كانت بترتيب مختلف عن 2+ (تكرار/نقص) فاتوحّدت | شغال (فك تشفير KVS اتجرب ورجّع MP4 حقيقي) | 19 → 20 |

## 2.1 إكستنشنز +18 (ar / en / all): اللي اتصلح

| الإكستنشن | التصنيف/الكتالوج | الفلترة/البحث | الفيديو | الإصدار |
|---|---|---|---|---|
| **HentaiMama** (en) | الموقع اتغير تصميمه: الكروت بقت `article.series-card`، وصفحة الأنمي بقت `dsc-*`، والحلقات بقت `a.dt-se-item`. **الكتالوج وصفحة التفاصيل والحلقات كانوا فاضيين** | البحث بالنص كان 404 (`/advance-search/?s=`)، وبقى `/page/N/?s=`. الفلاتر شغالة | الـ post id كان بيتقري غلط (كان بيرجّع العنوان). اتكتب من جديد: `get_player_contents` ← 5 سيرفرات (mp4 + HLS بجودات 480/720/1080) | 18 → 19 |
| **Hanime** (en) | البحث/الكتالوج: `search_hvs` بقى بيرجّع `{data:[...]}` بدل array | ← نفس الإصلاح | (تشفير handshake، ما اتختبرش برا التطبيق) | 36 → 37 |
| **HahoMoe** (en) | تاريخ الحلقات كان دايماً فاضي (`div.date` ← `span.episode-date`) | شغال | شغال (filegasm mp4) | 21 → 22 |
| **Rule34Video** (en) | الوصف والتاجات والمدة/المشاهدات كانت فاضية (التصميم الجديد `vp-*`) | البحث والتاجات شغالين | **ولا فيديو كان بيشتغل**: زرار التحميل بقى `a.tag_item_download` بدل `div.label:contains(Download)`. الجودة كانت بتطلع "1080p37 MB" | 21 → 22 |
| **JavGuru** (all) | شغال | شغال | 3 من 5 سيرفرات كانت بتترمي: `javlesbians` (VOE)، `streamhihi` (LuluStream)، `vide0/playmogo` (Dood). اتضافوا | 54 → 55 |
| **SexTB** (all) | **أول كارت في كل صفحة كان إعلان** (`/advertise`) وبيفشل لما تفتحه | ← | سيرفر SW (`hglink.to`) لودر JS بس، فبنحوّله لـ mirror بيرجّع المشغل مباشرة (hanerix/vibuxer) | 15 → 16 |
| **RouVideo** (all) | **الإكستنشن كله كان واقع**: الموقع اتنقل من Next.js لـ TanStack Start، و`__NEXT_DATA__` اختفى. اتعمل محوّل seroval → JSON فالـ DTOs القديمة رجعت تشتغل. صفحة التاجات بقت `taxonomy` | البحث/التاج/الترتيب شغالين | `/api/v/{id}` بقى بيرجّع `{}`. اللينك بقى متشفر في الصفحة (`ev.d/k`)، والـ playlist وكل segment بقوا **صور PNG والفيديو جوه chunk اسمه `roUd`**، فبيتشغلوا عن طريق الـ m3u8 proxy المحلي (اتجرب: segment بقى TS سليم h264/aac) | 24 → 25 |
| **Jable** (all) | **الصفحة 2+ عمرها ما ظهرت**: الصفحة الحالية بقت `span.active` جوه `li` | ← | شغال (hlsUrl) | 13 → 14 |
| **ArabXN** (ar) | — | فلتر "كل الفيديوهات" صفحة 2+ كان فاضي (`/video/N` ← `/video/page/N`) | شغال (mp4) | 15 → 16 |
| **SupJav** (all) | شغال (اتختبر في Chrome بتاعك) | **البحث كان بيتجاهل رقم الصفحة**، فكل صفحة كانت بتكرر نتايج الأولى (الموقع بيستخدم `/page/N?s=`). ومكانش فيه أي فلتر، فاتضاف فلتر التصنيف (6 أقسام) | الموقع غيّر السيرفرات لـ EVS/VAS/ST/VOE/LUC، والإكستنشن كان بيعرف ST وVOE بس فكان **3 من 5 سيرفرات بيترموا**. EVS (EarnVids) وLUC (LuluStream) ← VidHide، وVAS ← `POST /api/stream` | 86 → 87 |
| **SupJav2** (all) | شغال | شغال (البحث والـ 6 أقسام والـ 25 تاج اتجربوا كلهم بصفحة 2) | **VOE كان دايماً بيفشل**: المسار اللي بيشتغل فعلاً كان باعته لمحلل الـ packed JS. وVAS ماكانش متضاف. EVS وLUC شغالين أصلاً عن طريق الـ packed fallback | 27 → 28 |
| **PornHub** (all) | شغال | شغال | **403 من أول ما الفيديو يفتح، ومرة يشتغل ومرة لا، مع VPN/WARP**: اللينكات كلها (HLS وMP4، ديسكتوب وموبايل) مربوطة بالـ IP (`ipa=1`) وصلاحيتها ساعة. والـ CDN بيطلب Referer + UA متصفح (من غيرهم بيرجّع 404/410)، والتوكن الغلط بيرجّع 471. يعني الـ 403 جاي من `www.pornhub.com` نفسه وهو بيرفض IP الـ WARP. اتضاف retry على اتصال جديد لـ 403/429، ورسالة واضحة بدل "HTTP error 403". الحل الأكيد: اقفل WARP أو خلي تطبيق 1.1.1.1 على وضع DNS بس | 62 → 63 |

### إصلاح هيكلي في `lib/m3u8server`
- **الـ lib ما كانش بيتعمله build خالص**: `nanohttpd:-SNAPSHOT` على jitpack بقى بيرجّع 404، و`master-SNAPSHOT` بيرجّع إصدار مش متطابق. ده كان مبوّظ **Animetsu وMiruro** كمان. اتنقل لـ `nanohttpd-project-2.3.1` (نفس اللي AnimePahe بيستخدمه) والـ API اتعدّل (`handle` ← `serve`). Animetsu وMiruro اتعملهم compile تاني ونجحوا.
- اتضاف `PngContainer`: بيفك الـ playlists والـ segments المتغلفة في PNG (chunk خاص + flag خام/zlib). مش بيأثر على أي حاجة تانية: لو مالقاش chunk فيه TS/M3U8 بيرجّع null والسلوك القديم بيكمل زي ما هو.

### +18 اتختبر وشغال من غير تعديل
- **ar**: Aflamk1، SexAlArab، SexMahali، ArabX (playeriz ← HLS)، NxxHentai (dooplayer ← b-cdn mp4 720p)، فلاتر ArabX (13 تصنيف × صفحتين) وArabXN.
- **en**: HStream.
- **all**: XNXX وXVideos (الكتالوج والبحث والتاجات والـ getvideo API)، PornHub (`mediaDefinitions`)، JavGG (VidHide)، MissAV (packed ← m3u8 1080p)، HentaiTorrent وPTorrent (الكتالوج ← ملف torrent).

### +18 محتاج منك
| الإكستنشن | المشكلة |
|---|---|
| **SupJav / SupJav2** (ملاحظة) | Cloudflare على `supjav.com` و`lk1.supremejav.com` بيدّي challenge من الشبكة دي، فلو التطبيق طلع "Cloudflare"، افتح الإكستنشن في WebView مرة. لينكات VAS متقفلة على الـ IP، فلازم تتطلب من الموبايل نفسه (وده اللي الكود بيعمله) |
| **ArabsHentai** | كل الفيديوهات mp4 مباشر على `x-tez.com`، والدومين ده مالوش DNS من الشبكة هنا. الكود بيستخرج اللينك صح. لازم تجرّب من شبكة تانية: لو الدومين ميت يبقى الموقع نفسه بايظ |

## 3. اتختبر وشغال (من غير تعديل)

- **Aflamk1**: الكتالوج والبحث وصفحات التصنيف شغالة، وفك KVS بيرجّع MP4 شغال.
- **SexMahali**: الكتالوج والبحث شغالين. سيرفر hentaized بيرجّع HLS، والـ segments محتاجة Referer `hentaized.com` والكود بيبعته فعلاً.
- **ArabX**, **ArabXN**, **ArabsHentai**, **NxxHentai**: الكتالوج وصفحة 2 شغالين.
- **HentaiTorrent**, **PTorrent**, **JavGG**, **JavGuru**, **SubsPlease**: الكتالوج شغال.

## 4. محتاج قرار أو مساعدة منك

| الإكستنشن | المشكلة | المطلوب |
|---|---|---|
| **WitAnime** | `witanime.cyou` بيحوّل لـ `witanime.xyz` اللي مالوش DNS. `witanime.net` بقى مدونة Blogger مزيفة | الدومين الحقيقي الجديد (من قناة تليجرام/ديسكورد الموقع) |
| **Animeiat** | `api.animeiat.co` مالوش DNS، و`animeiat.tv` دومين متباع (parked) | على الأغلب الموقع قفل. يتشال من الريبو؟ |
| **AnimeLek** | `.me` بيعمل timeout، و`.cc` صفحة "Bot Verification" | تأكيد الدومين |
| **ArabSeed** | اتنقل لـ `arabseed.cheap`، وكل الصفحات الداخلية عليها CAPTCHA بتاع Cloudflare | تحل التحدي في المتصفح المدمج عشان أقدر أشوف الـ HTML |
| **Cimaleek** (فيديو), **EgyDead**, **FaselHD**, **AnimeBlkom**, **ArabAnime** | ورا Cloudflare (403) | زي اللي فوق |
| **AnimeWorldIndia** | `anime-world.co` متباع. الموقع بقى `watchanimeworld.one` بثيم مختلف تماماً | إعادة كتابة كاملة |
| **JPFilms** | `jp-films.com` → `kodasusaka.com`، والموقع بقى Next.js ونقاط WordPress اختفت | إعادة كتابة كاملة |
| **MyAnime** | `myanime.live` بيحوّل لمدونة WordPress مختلفة | على الأغلب ميت |
| **KissAnime** | `kissanime.com.ru` بيرجّع 523 (السيرفر الأصلي واقع) | على الأغلب ميت |
| **AniDB** | `anidb.app` بقى بيحوّل لـ `anilab.so` (موقع تاني) | فحص |
| **Asiaflix**, **Tokuzilla** | الدومين مالوش DNS | دومين جديد أو حذف |
| **AnimePahe**, **MoviesMod**, **UHDMovies**, **ChineseAnime** | IP بتاعهم `162.4.84.x` محجوب من الشبكة هنا (حجب ISP) | لازم يتختبروا من شبكة تانية |

## 5. لسه ما اتفحصش

النطاق اتحدد على +18 في ar/en/all، والباقي (غير الـ +18) على الريبو التاني. اللي لسه ما اتفحصش من غير الـ +18: allanime, anikage, anilist, animegg, animeparadise, animetake, animeverse, av1encodes, blzone, cineby, hexawatch, kayoanime, kickassanime, kimoitv, kisskh, miruro, myrunningman, noobsubs, onetwothreeanime, oppaistream, pinoymoviepedia, superstream, uniquestream, animeonsen, animetsu, nyaatorrent, streamingcommunity, torrentio, debridindex, googledrive, jellyfin, stremio…

## 6. ترتيب الشغل الجاي

1. ~~نتأكد إن كل اللي اتعدل بيعمل compile~~ ✅ كل الموديولات المعدّلة عملت `compileReleaseKotlin` بنجاح (و`settings.gradle.kts` رجع زي ما كان).
2. نرجّع ثيمات `lib-multisrc` الناقصة (17 إكستنشن).
3. ندخّل كل الـ 107 في `settings.gradle.kts`.
4. نكمّل فحص الإنجليزي و`all` بنفس الطريقة: كتالوج، ثم فلاتر/بحث، ثم فيديو.
5. المواقع اللي ورا Cloudflare: نفحصها في المتصفح بعد ما تحل التحدي.
6. نعمل bump للإصدارات، وbuild، ونحدّث `index.json` محلياً من غير رفع.

## إكستنشن جديد: JavDB (all, v1)

`src/all/javdb`، واتضاف لـ `settings.gradle.kts`. الـ APK: `src/all/javdb/build/outputs/apk/release/aniyomi-all.javdb-v14.1-release.apk`

| الجزء | المتاح |
|---|---|
| الكتالوج | Popular = ترتيب Censored (يومي/أسبوعي/شهري من الإعدادات)، Latest = أحدث الأفلام |
| الأقسام | All، Censored، Western **من غير تسجيل دخول**. Uncensored وFC2 وAnime **محتاجين تسجيل دخول** |
| الفلاتر | Show (كله، فيه magnets، مترجم، فيه تقييمات، Playable، Playable مترجم صيني)، الترتيب (تاريخ الإصدار، تحديث الـ magnet، تحديث الـ Playable) |
| الترتيبات (Rankings) | يومي/أسبوعي/شهري × Censored/Western، وFANZA award. Uncensored وFC2 وTOP250 وTop played محتاجين تسجيل دخول |
| التاجات | 109 تاج في 7 مجموعات (Theme، Role، Outfit، Body، Action، Play، Category)، وتقدر تختار كذا تاج مع بعض. الموقع بيقفل التاجات على الأعضاء، فلازم تسجيل دخول |
| البحث | أفلام، Playable، أعمال ممثلة واحدة، فيه magnets، مترجم، فيه صور، **ID prefix** (ROE ← كل أفلام ROE)، و**ممثلة/شركة/سلسلة/مخرج ← أفلامهم**. ولو لزقت لينك javdb.com (فيلم أو أي لستة) بيفتح على طول |
| الفيديو | JavDB نفسه مافيهوش مشغّل ببلاش، فالكود بتاع الفيلم بيتدوّر عليه في **MissAV** (الأصلي + leak + ترجمة صيني) ← HLS. وكمان **كل الـ magnets** (الاسم، الحجم، HD/Subtitle، التاريخ) |
| تسجيل الدخول | افتح WebView من الإكستنشن وسجّل دخول. الكوكيز بتتحفظ وكل الأقسام بتفتح. ولو قسم محتاج دخول بتظهر رسالة واضحة بدل صفحة فاضية |
| الحماية | Rate limit مرتين في الثانية على javdb.com، لأن الموقع بيعمل ban للسرعة |

**اتختبر على الموقع:** كل صفحات الليستات (Latest، Censored، Western، Playable، البحث، الممثلة، الشركة، الترتيبات، FANZA)، والـ pagination، وصفحة التفاصيل، والـ magnets، والـ m3u8 من MissAV. **ما اتختبرش:** الأقسام اللي محتاجة تسجيل دخول (مفيش حساب)، وتشغيل الـ magnets (محتاج نسخة تطبيق فيها torrent).
