#!/usr/bin/env python3
"""Unified Linux/cross-platform maintainer tool for Omar's Aniyomi extensions repo.

Handles:
- Checking src/ vs apk/ vs index.json consistency and version parity
- Building release APKs via ./gradlew without violating canonical /apk layout
- Verifying APK signatures, AndroidManifest metadata, DEX presence, and CI rules
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
APK_DIR = ROOT / "apk"
ICON_DIR = ROOT / "icon"
SRC_DIR = ROOT / "src"
GRADLEW = ROOT / "gradlew"
BUILD_TOOLS = Path("/home/omar/Android/Sdk/build-tools/34.0.0")
AAPT = BUILD_TOOLS / "aapt"
APKSIGNER = BUILD_TOOLS / "apksigner"
EXPECTED_SHA256 = "84300648046b4e4d24e940d892207fc94d6c723c120fddb5450b222c4e8d3a4d"

GROUPS = {
    "all:A1": [
        "all:animeonsen",
        "all:animetsu",
        "all:animeworldindia",
        "all:animexin",
        "all:anizone",
        "all:chineseanime",
        "all:lmanime",
        "all:streamingcommunity",
        "all:subsplease",
    ],
    "all:A2": [
        "all:googledrive",
        "all:googledriveindex",
        "all:jellyfin",
        "all:newgrounds",
        "all:shabakatycinemana",
        "all:stremio",
    ],
    "all:A3": [
        "all:debridindex",
        "all:hentaitorrent",
        "all:nyaatorrent",
        "all:ptorrent",
        "all:torrentio",
        "all:torrentioanime",
    ],
    "all:A4": [
        "all:jable",
        "all:javdb",
        "all:javgg",
        "all:javguru",
        "all:missav",
        "all:myreadingmanga",
        "all:pornhub",
        "all:rouvideo",
        "all:sextb",
        "all:supjav",
        "all:supjav2",
        "all:xnxx",
        "all:xvideos",
    ],
}


def theme_base_version(theme_pkg: str) -> int:
    for fname in ("build.gradle.kts", "build.gradle"):
        f = ROOT / "lib-multisrc" / theme_pkg / fname
        if f.exists():
            m = re.search(r'baseVersionCode["\']?\s*\]?\s*=\s*(\d+)', f.read_text())
            if m:
                return int(m.group(1))
    return 0


def expected_version_code(lang: str, name: str) -> int | None:
    bg = SRC_DIR / lang / name / "build.gradle"
    if not bg.exists():
        return None
    txt = bg.read_text(encoding="utf-8")
    m_theme = re.search(r"themePkg\s*=\s*['\"]([^'\"]+)['\"]", txt)
    if m_theme:
        m_over = re.search(r"overrideVersionCode\s*=\s*(\d+)", txt)
        override = int(m_over.group(1)) if m_over else 0
        return theme_base_version(m_theme.group(1)) + override
    m_ver = re.search(r"extVersionCode\s*=\s*(\d+)", txt)
    return int(m_ver.group(1)) if m_ver else None


def verify_apk(apk_path: Path) -> list[str]:
    issues: list[str] = []
    if not apk_path.is_file():
        return [f"Missing APK: {apk_path}"]

    sig_out = subprocess.run(
        [str(APKSIGNER), "verify", "--print-certs", str(apk_path)],
        capture_output=True,
        text=True,
    )
    if sig_out.returncode != 0 or EXPECTED_SHA256 not in sig_out.stdout:
        issues.append(f"{apk_path.name}: signature mismatch or verification failed")

    badging_out = subprocess.run(
        [str(AAPT), "dump", "--include-meta-data", "badging", str(apk_path)],
        capture_output=True,
        text=True,
        errors="replace",
    )
    if badging_out.returncode != 0:
        issues.append(f"{apk_path.name}: aapt badging failed")
    else:
        if "tachiyomi.animeextension.class" not in badging_out.stdout:
            issues.append(f"{apk_path.name}: missing tachiyomi.animeextension.class meta-data")
        if "application-icon-320:" not in badging_out.stdout:
            issues.append(f"{apk_path.name}: missing application-icon-320")

    with ZipFile(apk_path) as zf:
        names = set(zf.namelist())
        if not any(n.endswith(".dex") for n in names):
            issues.append(f"{apk_path.name}: missing classes.dex")

    return issues


def cmd_status(lang_filter: str | None) -> int:
    idx = {e["pkg"]: e for e in json.loads((ROOT / "index.json").read_text(encoding="utf-8"))}
    langs = [lang_filter] if lang_filter else ["all", "ar", "en"]
    warnings = 0
    for lang in langs:
        print(f"=== {lang.upper()} ===")
        for d in sorted(p for p in (SRC_DIR / lang).iterdir() if p.is_dir()):
            pkg = f"eu.kanade.tachiyomi.animeextension.{lang}.{d.name}"
            e = idx.get(pkg)
            src_code = expected_version_code(lang, d.name)
            idx_code = e["code"] if e else None
            flags = []
            if not e:
                flags.append("NOT_IN_INDEX")
            elif src_code is None:
                flags.append("NO_VERSION")
            elif src_code != idx_code:
                flags.append(f"VER_DRIFT(src={src_code},idx={idx_code})")
            if e and not (APK_DIR / e["apk"]).is_file():
                flags.append("MISSING_APK")
            if e and any(s.get("id") in ("", "0") or s.get("name") in ("all", "ar", "en") for s in e.get("sources", [])):
                flags.append("BAD_SOURCE_META")
            if flags:
                warnings += 1
                print(f"  [WARN] {lang}:{d.name:20s} -> {', '.join(flags)}")
    print(f"Total warnings: {warnings}")
    return 0


def cmd_verify_apks(lang_filter: str | None) -> int:
    pattern = f"aniyomi-{lang_filter}.*.apk" if lang_filter else "*.apk"
    apks = sorted(APK_DIR.glob(pattern))
    all_issues: list[str] = []
    for apk in apks:
        all_issues.extend(verify_apk(apk))

    # CI checks
    if list(ROOT.glob("*.apk")):
        all_issues.append("Root-level *.apk duplicates exist")
    if (APK_DIR / "apk").exists():
        all_issues.append("Nested apk/apk directory exists")

    entries = json.loads((ROOT / "index.json").read_text(encoding="utf-8"))
    min_entries = json.loads((ROOT / "index.min.json").read_text(encoding="utf-8"))
    if entries != min_entries:
        all_issues.append("index.json and index.min.json differ")
    if len(entries) != len(list(APK_DIR.glob("*.apk"))):
        all_issues.append("APK count in /apk does not match index.json")

    if all_issues:
        for issue in all_issues:
            print(f"[FAIL] {issue}", file=sys.stderr)
        return 1
    print(f"[OK] Verified {len(apks)} APKs and repository layout.")
    return 0


def resolve_targets(raw_targets: list[str], group: str | None, lang: str | None) -> list[tuple[str, str]]:
    items: list[str] = []
    if group:
        if group not in GROUPS:
            raise SystemExit(f"Unknown group {group}. Available: {', '.join(sorted(GROUPS))}")
        items.extend(GROUPS[group])
    elif lang:
        items.extend(
            f"{lang}:{p.name}"
            for p in sorted((SRC_DIR / lang).iterdir())
            if p.is_dir() and (p / "build.gradle").exists()
        )
    items.extend(raw_targets)
    parsed: list[tuple[str, str]] = []
    seen = set()
    for t in items:
        if ":" not in t:
            raise SystemExit(f"Target must be lang:name (got {t})")
        l, n = t.split(":", 1)
        if (l, n) not in seen:
            seen.add((l, n))
            parsed.append((l, n))
    return parsed


def cmd_build(targets: list[tuple[str, str]], bump: bool, batch_size: int) -> int:
    if not targets:
        raise SystemExit("No targets specified.")

    if bump:
        for lang, name in targets:
            bg = SRC_DIR / lang / name / "build.gradle"
            txt = bg.read_text(encoding="utf-8")
            if "overrideVersionCode" in txt:
                m = re.search(r"overrideVersionCode\s*=\s*(\d+)", txt)
                assert m, f"Missing overrideVersionCode in {bg}"
                new_val = int(m.group(1)) + 1
                txt = re.sub(r"overrideVersionCode\s*=\s*\d+", f"overrideVersionCode = {new_val}", txt)
            else:
                m = re.search(r"extVersionCode\s*=\s*(\d+)", txt)
                assert m, f"Missing extVersionCode in {bg}"
                new_val = int(m.group(1)) + 1
                txt = re.sub(r"extVersionCode\s*=\s*\d+", f"extVersionCode = {new_val}", txt)
            bg.write_text(txt, encoding="utf-8")
            print(f"Bumped {lang}:{name} -> {new_val}")

    tasks = [f":src:{lang}:{name}:assembleRelease" for lang, name in targets]
    for i in range(0, len(tasks), batch_size):
        chunk = tasks[i : i + batch_size]
        print(f"Building: {' '.join(chunk)}")
        res = subprocess.run([str(GRADLEW), *chunk, "--no-daemon"], cwd=ROOT)
        if res.returncode != 0:
            return res.returncode

    for lang, name in targets:
        release_dir = SRC_DIR / lang / name / "build" / "outputs" / "apk" / "release"
        apks = sorted(release_dir.glob("*.apk"))
        if not apks:
            print(f"[ERR] No release APK built for {lang}:{name}", file=sys.stderr)
            return 1
        built = apks[0]
        code = expected_version_code(lang, name)
        if code is None:
            print(f"[ERR] Could not determine version code for {lang}:{name}", file=sys.stderr)
            return 1
        pkg_prefix = f"aniyomi-{lang}.{name}"
        for old in APK_DIR.glob(f"{pkg_prefix}-v*.apk"):
            old.unlink()
        dest = APK_DIR / f"{pkg_prefix}-v14.{code}.apk"
        shutil.copy2(built, dest)
        issues = verify_apk(dest)
        if issues:
            for issue in issues:
                print(f"[ERR] {issue}", file=sys.stderr)
            return 1
        print(f"[SYNC] {dest.name}")

    subprocess.run([sys.executable, str(ROOT / "rebuild_index.py")], cwd=ROOT, check=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Maintainer tool for Omar's extensions repo")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_status = sub.add_parser("status", help="Compare src/, apk/, and index.json")
    p_status.add_argument("--lang", choices=["all", "ar", "en"])

    p_verify = sub.add_parser("verify-apks", help="Verify APK signatures, manifests, and CI layout")
    p_verify.add_argument("--lang", choices=["all", "ar", "en"])

    p_build = sub.add_parser("build", help="Build release APKs, validate, sync to /apk, rebuild index")
    p_build.add_argument("targets", nargs="*", help="Targets like all:javdb ar:anime4up")
    p_build.add_argument("--group", help="Named group e.g. all:A1")
    p_build.add_argument("--lang", choices=["all", "ar", "en"], help="Build all modules for language")
    p_build.add_argument("--bump", action="store_true", help="Increment version code before building")
    p_build.add_argument("--batch-size", type=int, default=6)

    args = parser.parse_args()
    if args.cmd == "status":
        return cmd_status(args.lang)
    if args.cmd == "verify-apks":
        return cmd_verify_apks(args.lang)
    if args.cmd == "build":
        targets = resolve_targets(args.targets, args.group, args.lang)
        return cmd_build(targets, args.bump, args.batch_size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
