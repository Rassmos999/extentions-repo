# Extension Repository Inventory & Partitioning

Repository structure and usage-based partitioning for the **110 published extensions** (`all`: 35, `ar`: 23, `en`: 52).

> Legacy unindexed directories under `src/` (`de`, `es`, `fr`, `hi`, `id`, `it`, `ko`, `pl`, `pt`, `ru`, `sr`, `tr`, `uk`, `zh`) are excluded from `settings.gradle.kts` and left untouched.

## Repository Tooling

- Build, sign-verify, and sync APKs cleanly to `/apk`:
  `python3 scripts/repo_tool.py build all:sextb [--bump]`
- Build an entire usage group:
  `python3 scripts/repo_tool.py build --group all:A1`
- Check `src/` vs `/apk` vs `index.json` parity:
  `python3 scripts/repo_tool.py status`
- Verify APK signatures, manifests, and CI layout rules:
  `python3 scripts/repo_tool.py verify-apks`

---

## Part 1: `all` — Language-Independent & Multi-Language (35 extensions)

### Group A1 — Anime & Donghua Streaming (9)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `animeonsen` | `all.animeonsen` | Anime streaming (OAuth + Meilisearch API) | Verified live (`v14.10`, unchanged) |
| `animetsu` | `all.animetsu` | Anime streaming (`m3u8server`, +18 toggle) | **Dead** (site shut down / removed upstream; kept at `v14.7` pending removal confirmation) |
| `animeworldindia` | `all.animeworldindia` | Multi-lang anime (`watchanimeworld.one`) | **Fixed & Rebuilt** (`v14.23`, `bdb8b5b`: `ZephyrixExtractor` + updated domain/filters) |
| `animexin` | `all.animexin` | Donghua (`themePkg: animestream`) | **Fixed & Rebuilt** (`v14.30`, `bdb8b5b`: restored `animestream` theme) |
| `anizone` | `all.anizone` | Anime (`anizone.to`, Livewire) | **Fixed & Rebuilt** (`v14.12`, `bdb8b5b`: updated Livewire / `AnizoneDto`) |
| `chineseanime` | `all.chineseanime` | Donghua (`themePkg: animestream`) | **Dead / Broken Site** (`chineseanime.vip` parked `"Redirecting..."` stub, TLS/HTTP2 reset; `v14.32` idx vs `src=33` `VER_DRIFT` left unbuilt pending removal confirmation) |
| `lmanime` | `all.lmanime` | Donghua/Anime (`themePkg: animestream`) | **Fixed & Rebuilt** (`v14.28`, `bdb8b5b`) |
| `streamingcommunity` | `all.streamingcommunity` | Movies & TV EN/IT (`StreamingUnity`) | **Fixed & Rebuilt** (`v14.22`, `bdb8b5b`) |
| `subsplease` | `all.subsplease` | Anime releases (Torrent magnet / Debrid) | **Fixed & Rebuilt** (`v14.6`, `bdb8b5b`: `AndroidManifest.xml` torrent flag) |

### Group A2 — Media Servers, Cloud & General Portals (6)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `googledrive` | `all.googledrive` | Google Drive folder player | User-configured URL portal (`v14.18`, unchanged) |
| `googledriveindex` | `all.googledriveindex` | Cloudflare Worker GD Index client | User-configured URL portal (`v14.7`, unchanged) |
| `jellyfin` | `all.jellyfin` | Self-hosted Jellyfin media server client | **Fixed & Rebuilt** (`v14.31`, `b695535`: ProGuard rules, subpath, login) |
| `newgrounds` | `all.newgrounds` | Newgrounds animations/movies | **Fixed & Rebuilt** (`v14.2`, `b695535`: NG Guard) |
| `shabakatycinemana` | `all.shabakatycinemana` | Shabakaty Cinemana portal (Iraq ISP scoped) | **Dead / Geo-locked** (removed upstream / Iraq ISP scoped; kept at `v14.3` pending confirmation) |
| `stremio` | `all.stremio` | Stremio addon client (HTTP / Torrent / Debrid) | **Fixed & Rebuilt** (`v14.10`, `b695535`: torrent manifest + login) |

### Group A3 — Torrent & Debrid Indexers (6)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `debridindex` | `all.debridindex` | Torrentio Debrid Index (requires Debrid key) | Requires user Debrid API key (`v14.2`, unchanged) |
| `hentaitorrent` | `all.hentaitorrent` | Hentai Torrent (`isTorrent = true`, +18) | **Fixed & Rebuilt** (`v14.17`, `8da0dc3`/`419657b`: torrent manifest, deeplinks, categories) |
| `nyaatorrent` | `all.nyaatorrent` | Nyaa & Sukebei (`isTorrent = true`, `TorrentUtils`) | **Fixed & Rebuilt** (`v14.7`, `8da0dc3`/`419657b`: torrent manifest + resolved `VER_DRIFT`) |
| `ptorrent` | `all.ptorrent` | PTorrent (`isTorrent = true`, +18) | **Fixed & Rebuilt** (`v14.18`, `8da0dc3`/`419657b`: torrent manifest, deeplinks, categories) |
| `torrentio` | `all.torrentio` | Torrentio Movies/TV (Torrent / Debrid) | **Fixed & Rebuilt** (`v14.9`, `8da0dc3`/`419657b`: torrent manifest, deeplinks, DTO) |
| `torrentioanime` | `all.torrentioanime` | Torrentio Anime (Torrent / Debrid) | **Fixed & Rebuilt** (`v14.20`, `8da0dc3`/`419657b`: torrent manifest, deeplinks) |

### Group A4 — Adult / JAV +18 (14)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `jable` | `all.jable` | JAV HLS streaming | **Upgraded & Rebuilt** (`v14.16`: `:lib:m3u8server` hybrid seek-cache + lookahead prefetch toggle) |
| `javdb` | `all.javdb` | JAV index + MissAV HLS + magnets (`isTorrent = true`) | **Upgraded & Rebuilt** (`v14.3`: `:lib:m3u8server` hybrid seek-cache + lookahead prefetch on MissAV HLS streams) |
| `javgg` | `all.javgg` | JAV multi-embed streaming | **Fixed & Rebuilt** (`v14.32`: `hls4` via `m3u8Server` for EarnVids/StreamHG/StreamWish, F4 `/v/...` proxy preference, parallel extraction with early cutoff, 3-min `pageCache`, multi-select Tags/Genres, seek-cache) |
| `javguru` | `all.javguru` | JAV multi-embed + Actress catalog | **Fixed & Rebuilt** (`v14.57`: `hls4` + `m3u8Server` for StreamWish/VidHide/EarnVids, `fastExtractorClient`, hybrid seek-cache) |
| `missav` | `all.missav` | JAV HLS streaming | **Upgraded & Rebuilt** (`v14.79`: `:lib:m3u8server` hybrid seek-cache + `Origin`/`Referer`/`Cookie` forwarding + lookahead prefetch) |
| `myreadingmanga` | `all.myreadingmanga` | Bara/Yaoi video/gallery scraper (multi-lang factory) | **Fixed & Rebuilt** (`v14.3`, `156cfd5`: CF Turnstile gated) |
| `pornhub` | `all.pornhub` | Adult tube (HLS / MP4) | **Upgraded & Rebuilt** (`v14.69`: `:lib:m3u8server` HLS + `/media` MP4 512 KB byte-range seek-cache) |
| `rouvideo` | `all.rouvideo` | Asian adult (TanStack Start + PNG-HLS proxy) | **Rebuilt** (`v14.27`: inherits upgraded `:lib:m3u8server` hybrid RAM+Disk LRU cache & lookahead prefetch) |
| `sextb` | `all.sextb` | JAV multi-embed streaming | **Restructured** (`v14.21`: added same-origin WebView session bridge for Cloudflare JA4 fingerprint mismatch, switched default Popular/Latest category to `LTX Decensored` with configurable default-category preference, added HTTP 429 backoff retry; `v14.20`: fixed F4 `src` HLS resolution, `hls4`/`hls3`/`hls2` fallback, `E_HUMAN` detection, dynamic rotated-hash resolution, expanded 140+ genres, new categories/TOP charts, `/ajax/search` movie+actress lookup, detail poster fix, preferences & seek-cache toggle) |
| `supjav` | `all.supjav` | JAV multi-embed streaming (CF protected) | **Rebuilt** (`v14.103`: inherits upgraded `:lib:m3u8server` hybrid RAM+Disk LRU cache & lookahead prefetch) |
| `supjav2` | `all.supjav2` | JAV multi-embed streaming | **Fixed & Rebuilt** (`v14.30`: parity with `SupJav` — parallel `ExecutorCompletionService`, `hls4` + `m3u8Server`, fixed `/popular`, 3-min `pageCache`, multi-select Categories/Tags/Makers/Actresses, seek-cache) |
| `xmoviesforyou` | `all.xmoviesforyou` | Adult movies (direct HLS + mirrors) | **Upgraded & Rebuilt** (`v14.4`: `disableRelatedAnimes`, upgraded `:lib:m3u8server` seek-cache + prefetch for direct HLS and mirrors) |
| `xnxx` | `all.xnxx` | Adult tube | **Upgraded & Rebuilt** (`v14.23`: `:lib:m3u8server` HLS/MP4 seek-cache + lookahead prefetch) |
| `xvideos` | `all.xvideos` | Adult tube | **Upgraded & Rebuilt** (`v14.24`: `:lib:m3u8server` HLS/MP4 seek-cache + lookahead prefetch) |

---

## Part 2: `ar` — Arabic Extensions (23 extensions — Scheduled Next)

- **R1 — Arabic Anime (8)**: `anime4up`, `animeblkom`, `animeiat`, `animelek`, `animerco`, `arabanime`, `okanime`, `witanime`
- **R2 — Arabic Movies & Series (7)**: `arabseed`, `asia2tv`, `cimaleek`, `cimalight`, `egydead`, `faselhd`, `tuktukcinema`
- **R3 — Arabic Adult +18 (8 — All Audited & Upgraded with Seek-Cache)**: `aflamk1` (`v14.15`), `arabshentai` (`v14.40`: parallel DooPlay + `hls4`/`m3u8Server`), `arabx` (`v14.19`), `arabxn` (`v14.19`), `hentaiaraby` (`v14.3`), `nxxhentai` (`v14.38`: `hls4`/`m3u8Server`), `sexalarab` (`v14.23`), `sexmahali` (`v14.20`)

---

## Part 3: `en` — English Extensions (52 extensions — Scheduled Later)

- **E1 — English Anime Standalone (17)**: `allanime`, `anidb`, `anikage`, `anilist`, `animegg`, `animepahe`, `animeparadise`, `animetake`, `animeverse`, `av1encodes`, `kayoanime`, `kickassanime`, `kissanime`, `miruro`, `myanime`, `noobsubs`, `onetwothreeanime`
- **E2 — Theme-based Anime / Donghua / Cartoon (14)**:
  - `animestream`: `animekhor`, `animenosub`, `donghuastream`, `luciferdonghua`
  - `anikototheme`: `anikoto`, `animesogo`, `aniwave`, `kotokai`
  - `wcotheme`: `wcoanimedub`, `wcoanimesub`, `wcoforever`, `wcofun`, `wcostream`, `wcotv`
- **E3 — English Movies, Asian Drama & Tokusatsu (15)**: `asiaflix`, `blzone`, `cineby`, `hexawatch`, `jpfilms`, `kimoitv`, `kisskh`, `mapple`, `moviesmod`, `myrunningman`, `pinoymoviepedia`, `superstream`, `tokuzilla`, `uhdmovies`, `uniquestream`
- **E4 — English Adult +18 (6 — All Audited & Upgraded)**: `hahomoe` (`v14.23`: Referer + 25s timeout + `pageCache` + MP4 seek-cache), `hanime` (`v14.39`: HLS seek-cache), `hentaimama` (`v14.20`: 2023–2026 years + response closing + `\u0026` + seek-cache), `hstream` (`v14.23`: safe XSRF + headers + `1080i`/`2160i`), `oppaistream` (`v14.7`: migrated to `POST /api/search.php` + new `storageBase`/`folder`/`layout` player + seek-cache), `rule34video` (`v14.23`: fixed `/most-popular/`, fixed `tag_ids`, multi-select Categories, `pageCache`, MP4 seek-cache)
