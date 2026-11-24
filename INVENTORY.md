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
| `jable` | `all.jable` | JAV HLS streaming | **Fixed & Rebuilt** (`v14.15`, `156cfd5`: `/categories/*` + seeded tags, single `sort_by`, headers, HLS `PlaylistUtils` + `Referer`, live-verified) |
| `javdb` | `all.javdb` | JAV index + MissAV HLS + magnets (`isTorrent = true`) | **Fixed & Rebuilt** (`v14.2`, `3b51b4a`: `missav.live` default + MissAV `Referer` fixing `surrit.com` 403, `isTorrent = true` verified) |
| `javgg` | `all.javgg` | JAV multi-embed streaming | **Fixed & Rebuilt** (`v14.31`, `9d2e370`) |
| `javguru` | `all.javguru` | JAV multi-embed + Actress catalog | **Fixed & Rebuilt** (`v14.56`, `9d2e370`) |
| `missav` | `all.missav` | JAV HLS streaming | **Fixed & Rebuilt** (`v14.78`, `3b51b4a`: `missav.live` default + site `Origin`/`Referer` fixing `surrit.com` 403, top categories, pagination, `VER_DRIFT` resolved) |
| `myreadingmanga` | `all.myreadingmanga` | Bara/Yaoi video/gallery scraper (multi-lang factory) | **Fixed & Rebuilt** (`v14.3`, `156cfd5`: merged CookieJar + WebView cookies + `Referer`/`Origin` headers, `cacheAssistant` retry guard; CF Turnstile gated) |
| `pornhub` | `all.pornhub` | Adult tube (HLS / MP4) | **Fixed & Rebuilt** (`v14.64`, `3b51b4a`: search `o=ht`/`cm` 404 fix, 99 live categories, `videoHeaders` cookies) |
| `rouvideo` | `all.rouvideo` | Asian adult (TanStack Start + PNG-HLS proxy) | **Fixed & Rebuilt** (`v14.26`, `9d2e370`: `/home` parsing + `m3u8server` PNG-HLS proxy) |
| `sextb` | `all.sextb` | JAV multi-embed streaming | **Fixed & Rebuilt** (`v14.17`, `9d2e370`) |
| `supjav` | `all.supjav` | JAV multi-embed streaming (CF protected) | **Fixed & Rebuilt** (`v14.100`, `156cfd5`: `supportsLatest`, pagination, synchronous extractors + `TV`/`DOOD`/`MIXDROP`/`UQLOAD`/`FST`/`EVS`/`LUC`/`VAS` + filters; CF Turnstile gated) |
| `supjav2` | `all.supjav2` | JAV multi-embed streaming | **Fixed & Rebuilt** (`v14.29`, `156cfd5`: wired `uqloadExtractor`, `DOOD` mirrors, `StreamWishExtractor`/`VidHideExtractor` fallbacks, multi-variant HLS, removed dead suspend block; CF Turnstile gated) |
| `xmoviesforyou` | `all.xmoviesforyou` | Adult movies (direct HLS + mirrors) | **Added & Built** (`v14.1`, `/api/stream` HLS with `Origin`+`Referer`, Dood/StreamTape/MixDrop mirrors, live-verified) |
| `xnxx` | `all.xnxx` | Adult tube | **Fixed & Rebuilt** (`v14.22`, `3b51b4a`: dynamic `/todays-selection/` archive date pagination + next-page selectors) |
| `xvideos` | `all.xvideos` | Adult tube | **Fixed & Rebuilt** (`v14.23`, `3b51b4a`: next-page selectors + category/tag + filter search routing) |

---

## Part 2: `ar` — Arabic Extensions (23 extensions — Scheduled Next)

- **R1 — Arabic Anime (8)**: `anime4up`, `animeblkom`, `animeiat`, `animelek`, `animerco`, `arabanime`, `okanime`, `witanime`
- **R2 — Arabic Movies & Series (7)**: `arabseed`, `asia2tv`, `cimaleek`, `cimalight`, `egydead`, `faselhd`, `tuktukcinema`
- **R3 — Arabic Adult +18 (8)**: `aflamk1`, `arabshentai`, `arabx`, `arabxn`, `hentaiaraby`, `nxxhentai`, `sexalarab`, `sexmahali`

---

## Part 3: `en` — English Extensions (52 extensions — Scheduled Later)

- **E1 — English Anime Standalone (17)**: `allanime`, `anidb`, `anikage`, `anilist`, `animegg`, `animepahe`, `animeparadise`, `animetake`, `animeverse`, `av1encodes`, `kayoanime`, `kickassanime`, `kissanime`, `miruro`, `myanime`, `noobsubs`, `onetwothreeanime`
- **E2 — Theme-based Anime / Donghua / Cartoon (14)**:
  - `animestream`: `animekhor`, `animenosub`, `donghuastream`, `luciferdonghua`
  - `anikototheme`: `anikoto`, `animesogo`, `aniwave`, `kotokai`
  - `wcotheme`: `wcoanimedub`, `wcoanimesub`, `wcoforever`, `wcofun`, `wcostream`, `wcotv`
- **E3 — English Movies, Asian Drama & Tokusatsu (15)**: `asiaflix`, `blzone`, `cineby`, `hexawatch`, `jpfilms`, `kimoitv`, `kisskh`, `mapple`, `moviesmod`, `myrunningman`, `pinoymoviepedia`, `superstream`, `tokuzilla`, `uhdmovies`, `uniquestream`
- **E4 — English Adult +18 (6)**: `hahomoe`, `hanime`, `hentaimama`, `hstream`, `oppaistream`, `rule34video`
