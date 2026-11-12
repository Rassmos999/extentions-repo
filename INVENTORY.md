# Extension Repository Inventory & Partitioning

Repository structure and usage-based partitioning for the **108 published extensions** (`all`: 34, `ar`: 22, `en`: 52).

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

## Part 1: `all` — Language-Independent & Multi-Language (34 extensions)

### Group A1 — Anime & Donghua Streaming (9)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `animeonsen` | `all.animeonsen` | Anime streaming (OAuth + Meilisearch API) | Pending live audit |
| `animetsu` | `all.animetsu` | Anime streaming (`m3u8server`, +18 toggle) | Pending live audit (domains suspected dead) |
| `animeworldindia` | `all.animeworldindia` | Multi-lang anime (`anime-world.co` -> `watchanimeworld.one`) | Pending live audit |
| `animexin` | `all.animexin` | Donghua (`themePkg: animestream`) | Pending live audit |
| `anizone` | `all.anizone` | Anime (`anizone.to`, Livewire) | Pending live audit |
| `chineseanime` | `all.chineseanime` | Donghua (`themePkg: animestream`) | Pending live audit |
| `lmanime` | `all.lmanime` | Donghua/Anime (`themePkg: animestream`) | Pending live audit |
| `streamingcommunity` | `all.streamingcommunity` | Movies & TV EN/IT (`StreamingUnity`) | Pending live audit |
| `subsplease` | `all.subsplease` | Anime releases (Torrent magnet / Debrid) | Pending live audit |

### Group A2 — Media Servers, Cloud & General Portals (6)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `googledrive` | `all.googledrive` | Google Drive folder player | Pending audit |
| `googledriveindex` | `all.googledriveindex` | Cloudflare Worker GD Index client | Pending audit |
| `jellyfin` | `all.jellyfin` | Self-hosted Jellyfin media server client | Pending audit |
| `newgrounds` | `all.newgrounds` | Newgrounds animations/movies | Pending audit |
| `shabakatycinemana` | `all.shabakatycinemana` | Shabakaty Cinemana portal (Iraq ISP scoped) | Pending audit |
| `stremio` | `all.stremio` | Stremio addon client (HTTP / Torrent / Debrid) | Pending audit |

### Group A3 — Torrent & Debrid Indexers (6)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `debridindex` | `all.debridindex` | Torrentio Debrid Index (requires Debrid key) | Pending audit |
| `hentaitorrent` | `all.hentaitorrent` | Hentai Torrent (`isTorrent = true`, +18) | Pending audit |
| `nyaatorrent` | `all.nyaatorrent` | Nyaa & Sukebei (`isTorrent = true`, `TorrentUtils`) | Pending audit |
| `ptorrent` | `all.ptorrent` | PTorrent (`isTorrent = true`, +18) | Pending audit |
| `torrentio` | `all.torrentio` | Torrentio Movies/TV (Torrent / Debrid) | Pending audit |
| `torrentioanime` | `all.torrentioanime` | Torrentio Anime (Torrent / Debrid) | Pending audit |

### Group A4 — Adult / JAV +18 (13)
| Extension | Package Suffix | Usage / Type | Current Status |
|---|---|---|---|
| `jable` | `all.jable` | JAV HLS streaming | Pending audit |
| `javdb` | `all.javdb` | JAV index + MissAV HLS + magnets (`isTorrent = true`) | Pending audit |
| `javgg` | `all.javgg` | JAV multi-embed streaming | Pending audit |
| `javguru` | `all.javguru` | JAV multi-embed + Actress catalog | Pending audit |
| `missav` | `all.missav` | JAV HLS streaming (`src` v65 vs `apk` v77 drift) | Pending audit |
| `myreadingmanga` | `all.myreadingmanga` | Bara/Yaoi video/gallery scraper (multi-lang factory) | Pending audit |
| `pornhub` | `all.pornhub` | Adult tube (HLS / MP4) | Pending audit |
| `rouvideo` | `all.rouvideo` | Asian adult (TanStack Start + PNG-HLS proxy) | Pending audit |
| `sextb` | `all.sextb` | JAV multi-embed streaming | Pending audit |
| `supjav` | `all.supjav` | JAV multi-embed streaming (CF protected) | Pending audit |
| `supjav2` | `all.supjav2` | JAV multi-embed streaming | Pending audit |
| `xnxx` | `all.xnxx` | Adult tube | Pending audit |
| `xvideos` | `all.xvideos` | Adult tube | Pending audit |

---

## Part 2: `ar` — Arabic Extensions (22 extensions — Scheduled Next)

- **R1 — Arabic Anime (8)**: `anime4up`, `animeblkom`, `animeiat`, `animelek`, `animerco`, `arabanime`, `okanime`, `witanime`
- **R2 — Arabic Movies & Series (7)**: `arabseed`, `asia2tv`, `cimaleek`, `cimalight`, `egydead`, `faselhd`, `tuktukcinema`
- **R3 — Arabic Adult +18 (7)**: `aflamk1`, `arabshentai`, `arabx`, `arabxn`, `nxxhentai`, `sexalarab`, `sexmahali`

---

## Part 3: `en` — English Extensions (52 extensions — Scheduled Later)

- **E1 — English Anime Standalone (17)**: `allanime`, `anidb`, `anikage`, `anilist`, `animegg`, `animepahe`, `animeparadise`, `animetake`, `animeverse`, `av1encodes`, `kayoanime`, `kickassanime`, `kissanime`, `miruro`, `myanime`, `noobsubs`, `onetwothreeanime`
- **E2 — Theme-based Anime / Donghua / Cartoon (14)**:
  - `animestream`: `animekhor`, `animenosub`, `donghuastream`, `luciferdonghua`
  - `anikototheme`: `anikoto`, `animesogo`, `aniwave`, `kotokai`
  - `wcotheme`: `wcoanimedub`, `wcoanimesub`, `wcoforever`, `wcofun`, `wcostream`, `wcotv`
- **E3 — English Movies, Asian Drama & Tokusatsu (15)**: `asiaflix`, `blzone`, `cineby`, `hexawatch`, `jpfilms`, `kimoitv`, `kisskh`, `mapple`, `moviesmod`, `myrunningman`, `pinoymoviepedia`, `superstream`, `tokuzilla`, `uhdmovies`, `uniquestream`
- **E4 — English Adult +18 (6)**: `hahomoe`, `hanime`, `hentaimama`, `hstream`, `oppaistream`, `rule34video`
