import hashlib
import json
import re
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

AAPT = Path("/home/omar/Android/Sdk/build-tools/34.0.0/aapt")
ROOT = Path(__file__).resolve().parent
APK_DIR = ROOT / "apk"
ICON_DIR = ROOT / "icon"
SRC_DIR = ROOT / "src"

LANG_CODES = {"all", "ar", "en"}


def aniyomi_id(name: str, lang: str, version_id: int = 1) -> str:
    key = f"{name.lower()}/{lang}/{version_id}".encode("utf-8")
    digest = hashlib.md5(key).digest()[:8]
    val = struct.unpack(">q", digest)[0] & 0x7FFFFFFFFFFFFFFF
    return str(val)


def badging(apk: Path):
    out = subprocess.check_output(
        [str(AAPT), "dump", "--include-meta-data", "badging", str(apk)],
        text=True,
        errors="replace",
    )
    pkg = re.search(r"package: name='([^']+)'", out).group(1)
    code = int(re.search(r"versionCode='([^']+)'", out).group(1))
    ver = re.search(r"versionName='([^']+)'", out).group(1)
    label = re.search(r"^application-label:'([^']+)'", out, re.M).group(1)
    nsfw_m = re.search(r"'tachiyomi\.animeextension\.nsfw' value='([^']+)'", out)
    nsfw = int(nsfw_m.group(1)) if nsfw_m else 0
    icon_m = re.search(r"application-icon-320:'([^']+)'", out)
    icon = icon_m.group(1) if icon_m else None
    return pkg, code, ver, label, nsfw, icon


def infer_source_metadata(lang: str, ext_dir_name: str) -> tuple[str | None, str]:
    """Read explicit `override val id` and static `baseUrl` from source directory if present."""
    ext_dir = SRC_DIR / lang / ext_dir_name
    if not ext_dir.is_dir():
        return None, ""

    explicit_id = None
    base_url = ""

    bg = ext_dir / "build.gradle"
    if bg.exists():
        m = re.search(r"baseUrl\s*=\s*['\"](https?://[^'\"]+)['\"]", bg.read_text(errors="ignore"))
        if m:
            base_url = m.group(1).rstrip("/")

    for kt in sorted(ext_dir.rglob("*.kt")):
        text = kt.read_text(errors="ignore")
        if explicit_id is None:
            m_id = re.search(r"override\s+val\s+id\s*=\s*(\d+)", text)
            if m_id:
                explicit_id = m_id.group(1)
        if not base_url:
            m_url = re.search(r"override\s+(?:val|var)\s+baseUrl\s*=\s*\"(https?://[^\"]+)\"", text)
            if m_url and "$" not in m_url.group(1):
                base_url = m_url.group(1).rstrip("/")
    return explicit_id, base_url


def sanitize_sources(sources: list, lang: str, ext_name: str, ext_dir_name: str) -> list:
    explicit_id, inferred_url = infer_source_metadata(lang, ext_dir_name)
    cleaned = []
    for src in sources:
        s = dict(src)
        s_name = str(s.get("name", "")).strip()
        s_lang = str(s.get("lang", "")).strip()

        if "replaceFirstChar" in s_name or "${" in s_name:
            continue

        # Fix swapped name/lang from legacy regex extraction (e.g. name="all", lang="AnimeXin")
        if s_name in LANG_CODES and s_lang and s_lang not in LANG_CODES:
            s_name, s_lang = s_lang, s_name

        # Filter bogus pseudo-sources leaked by URL regexes
        if s_lang == "genre[]":
            continue

        # Clean multi-line / comment-polluted baseUrl values
        s_url = str(s.get("baseUrl", "")).strip()
        if "\n" in s_url or " " in s_url or not s_url.startswith("http"):
            s_url = ""
        if not s_url and inferred_url:
            s_url = inferred_url

        s_id = str(s.get("id", "0")).strip()
        if not s_id or s_id == "0" or s_name != src.get("name"):
            s_id = explicit_id or aniyomi_id(s_name or ext_name, s_lang or lang)

        cleaned.append(
            {
                "name": s_name or ext_name,
                "lang": s_lang or lang,
                "id": s_id,
                "baseUrl": s_url,
            }
        )

    if not cleaned:
        src_name = ext_name.replace("Aniyomi: ", "")
        cleaned = [
            {
                "name": src_name,
                "lang": lang,
                "id": explicit_id or aniyomi_id(src_name, lang),
                "baseUrl": inferred_url,
            }
        ]
    return cleaned


def main():
    APK_DIR.mkdir(exist_ok=True)
    ICON_DIR.mkdir(exist_ok=True)

    prev_data = []
    index_min_file = ROOT / "index.min.json"
    if index_min_file.exists():
        prev_data = json.loads(index_min_file.read_text(encoding="utf-8-sig"))
    by_pkg = {e["pkg"]: e for e in prev_data}

    entries = []
    for apk in sorted(APK_DIR.glob("*.apk")):
        pkg, code, ver, label, nsfw, icon_path = badging(apk)
        if icon_path:
            with ZipFile(apk) as z:
                (ICON_DIR / f"{pkg}.png").write_bytes(z.read(icon_path))

        m = re.match(r"aniyomi-([^.]+)\.([^.-]+)-", apk.name)
        lang = m.group(1) if m else "all"
        ext_dir_name = m.group(2) if m else pkg.split(".")[-1]
        name = label if label.startswith("Aniyomi") else f"Aniyomi: {label}"

        prev = by_pkg.get(pkg, {})
        raw_sources = list(prev.get("sources") or [])

        if pkg.endswith(".streamingcommunity"):
            _, sc_url = infer_source_metadata(lang, ext_dir_name)
            sc_base = sc_url or "https://streamingunity.fun"
            good = [s for s in raw_sources if "StreamingUnity" in s.get("name", "")]
            if len(good) >= 4:
                sources = [
                    {
                        "name": s["name"],
                        "lang": s["lang"],
                        "id": s["id"],
                        "baseUrl": f"{sc_base}/{s['lang']}",
                    }
                    for s in good
                ]
            else:
                sources = [
                    {
                        "name": "StreamingUnity (Movie)",
                        "lang": "en",
                        "id": aniyomi_id("StreamingUnity (Movie)", "en"),
                        "baseUrl": f"{sc_base}/en",
                    },
                    {
                        "name": "StreamingUnity (Movie)",
                        "lang": "it",
                        "id": aniyomi_id("StreamingUnity (Movie)", "it"),
                        "baseUrl": f"{sc_base}/it",
                    },
                    {
                        "name": "StreamingUnity (Tv)",
                        "lang": "en",
                        "id": aniyomi_id("StreamingUnity (Tv)", "en"),
                        "baseUrl": f"{sc_base}/en",
                    },
                    {
                        "name": "StreamingUnity (Tv)",
                        "lang": "it",
                        "id": aniyomi_id("StreamingUnity (Tv)", "it"),
                        "baseUrl": f"{sc_base}/it",
                    },
                ]
        else:
            sources = sanitize_sources(raw_sources, lang, name.replace("Aniyomi: ", ""), ext_dir_name)

        entries.append(
            {
                "name": name,
                "pkg": pkg,
                "apk": apk.name,  # basename only
                "lang": lang,
                "code": code,
                "version": ver,
                "nsfw": nsfw,
                "sources": sources,
            }
        )

    assert entries, "No APKs found in /apk"
    assert not list(ROOT.glob("*.apk")), "Root-level APK duplicates are not allowed"
    assert not (APK_DIR / "apk").exists(), "Nested apk/apk mirror is not allowed"
    assert all("/" not in e["apk"] and "\\" not in e["apk"] for e in entries), (
        "apk fields must be basenames"
    )
    assert len({e["pkg"] for e in entries}) == len(entries), "Duplicate package names in index"
    assert len({e["apk"] for e in entries}) == len(entries), "Duplicate APK names in index"
    assert all((APK_DIR / e["apk"]).is_file() for e in entries), "Index references a missing APK"
    assert all((ICON_DIR / f"{e['pkg']}.png").is_file() for e in entries), "Missing icon for entry"

    min_json_bytes = json.dumps(entries, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    full_json_bytes = (json.dumps(entries, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    repo_name = "Omar AR+EN+ALL (14.x)"
    repo_website = "https://github.com/Rassmos999/extentions-repo"
    branch = ""
    try:
        branch = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except Exception:
        branch = ""
    if branch and branch not in ("main", "HEAD"):
        repo_name = f"Omar AR+EN+ALL ({branch.upper()} - 14.x)"
        repo_website = f"https://github.com/Rassmos999/extentions-repo/tree/{branch}"

    repo_json_str = (
        json.dumps(
            {
                "meta": {
                    "name": repo_name,
                    "website": repo_website,
                    "signingKeyFingerprint": "84300648046b4e4d24e940d892207fc94d6c723c120fddb5450b222c4e8d3a4d",
                }
            },
            indent=2,
        )
        + "\n"
    )

    (ROOT / "index.min.json").write_bytes(min_json_bytes)
    (ROOT / "index.json").write_bytes(full_json_bytes)
    (ROOT / "repo.json").write_text(repo_json_str, encoding="utf-8")

    print(
        "verified canonical layout:",
        len(entries),
        "extensions in /apk and",
        len(list(ICON_DIR.glob("*.png"))),
        "icons",
    )


if __name__ == "__main__":
    main()
