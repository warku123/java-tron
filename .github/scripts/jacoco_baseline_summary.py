#!/usr/bin/env python3
"""Generate JaCoCo flake baseline summary and evidence manifest.

Recursively discovers JaCoCo XML reports, JUnit XML reports, JaCoCo .exec
files, test reports, Gradle logs and JVM crash artifacts under --root, then
writes:

  * a stable-sorted JSON summary (--output) with report-level and class-level
    coverage counters, JUnit aggregates, failed-test identifiers and Gradle
    log crash/OOM/retry signals;
  * a stable-sorted evidence manifest (--manifest) listing every evidence
    file with relative path, size and sha256.

JaCoCo: for every report emit path/bytes/sha256, session count, package and
class counts, the six report-level counter types
(INSTRUCTION, BRANCH, LINE, COMPLEXITY, METHOD, CLASS) with missed/covered,
and class-level LINE missed/covered for cross-run diffing.

JUnit: aggregate tests/failures/errors/skipped/time across suites and list
failed test identifiers (classname.name) sorted stably.

Log signals: scan Gradle logs for OOM, worker crash/nonzero, Broken pipe,
Connection reset and retry keyword hits. Hits are recorded as
{file, line, keyword, content}; no precise retry counts are claimed.

Robustness: missing files and malformed XML are recorded in
summary["parse_errors"] and never crash the script. Output JSON is sorted
stably (sort_keys + explicitly sorted lists).

Stdlib only. Exit code 0 on success, 2 on CLI or fatal IO error.

CI integration: invoked by the `jacoco-flake-baseline` workflow on the
test/flaky-jacoco-coverage branch to capture a reproducible coverage
baseline for flaky-test detection.
"""
import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

COUNTER_TYPES = ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS")

# Evidence globs. Kept in sync with the artifact upload paths in
# .github/workflows/jacoco-flake-baseline.yml so the manifest mirrors exactly
# what is uploaded.
EVIDENCE_GLOBS = (
    "**/build/jacoco/**/*.exec",
    "**/build/reports/jacoco/**/*.xml",
    "**/build/test-results/**",
    "**/build/reports/tests/**",
    "evidence/gradle-*.log",
    "evidence/metadata.json",
    "**/hs_err_*.log",
    "**/replay_*.txt",
    "**/*.hprof",
)

# Directories pruned from recursive walks (not evidence, slow, or noisy).
PRUNE_DIRS = {".git", ".gradle", ".idea", "node_modules", "__pycache__"}

# Gradle log signal patterns. Each tuple is (keyword_label, compiled regex).
# Retry hits are recorded as occurrences only; no precise retry count is
# inferred from log text.
LOG_PATTERNS = (
    ("oom", re.compile(r"OutOfMemoryError|java\.lang\.OutOfMemory|\bOOM\b")),
    (
        "worker_crash",
        re.compile(
            r"[Ww]orker\b.*(?:crash|non-?zero|exited)"
            r"|(?:crash|exited)\b.*[Ww]orker"
            r"|exited with (?:non-?zero|error)"
        ),
    ),
    ("broken_pipe", re.compile(r"Broken pipe")),
    ("connection_reset", re.compile(r"Connection reset")),
    ("retry", re.compile(r"[Rr]etry")),
)

MAX_LOG_LINE_LEN = 300
CHUNK = 65536


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def _iter_evidence_files(root: Path):
    """Yield regular files under root matching EVIDENCE_GLOBS, pruned.

    Deduplicates and yields in sorted order. Paths under PRUNE_DIRS and the
    script's own outputs are skipped.
    """
    seen = set()
    matches = []
    for glob in EVIDENCE_GLOBS:
        for p in root.glob(glob):
            if not p.is_file():
                continue
            # Prune noisy/slow directories.
            if any(part in PRUNE_DIRS for part in p.parts):
                continue
            matches.append(p)
    for p in sorted(set(matches)):
        try:
            st = p.stat()
        except OSError:
            continue
        if not st.st_size and p.suffix == ".exec":
            # Empty .exec is still evidence (indicates no coverage data).
            pass
        if p in seen:
            continue
        seen.add(p)
        yield p


def _build_manifest(root: Path, exclude: set):
    """Return sorted manifest entries [{path, bytes, sha256}, ...]."""
    entries = []
    for p in _iter_evidence_files(root):
        rel = p.relative_to(root).as_posix()
        if rel in exclude:
            continue
        try:
            size = p.stat().st_size
            digest = _sha256_file(p)
        except OSError as exc:
            # Record a placeholder so the manifest still references the file;
            # the parse_errors list in the summary captures the failure.
            entries.append({"path": rel, "bytes": -1, "sha256": "", "error": str(exc)})
            continue
        entries.append({"path": rel, "bytes": int(size), "sha256": digest})
    entries.sort(key=lambda e: e["path"])
    return entries


def _counter_map(elem):
    """Build {type: {missed, covered}} for direct <counter> children of elem."""
    out = {}
    for c in elem.findall("counter"):
        ctype = c.get("type", "")
        try:
            missed = int(c.get("missed", "0"))
            covered = int(c.get("covered", "0"))
        except (TypeError, ValueError):
            continue
        out[ctype] = {"missed": missed, "covered": covered}
    return out


def _full_counters(partial):
    """Return all six counter types, defaulting missing ones to 0/0."""
    return {
        t: partial.get(t, {"missed": 0, "covered": 0})
        for t in COUNTER_TYPES
    }


def _parse_jacoco(path: Path, rel: str, parse_errors):
    """Parse a JaCoCo report XML. Return report dict or None on failure."""
    try:
        tree = ET.parse(str(path))
    except ET.ParseError as exc:
        parse_errors.append({"file": rel, "kind": "jacoco", "error": f"parse: {exc}"})
        return None
    root = tree.getroot()
    if root.tag != "report":
        parse_errors.append(
            {"file": rel, "kind": "jacoco", "error": f"unexpected root <{root.tag}>"}
        )
        return None
    try:
        size = path.stat().st_size
    except OSError:
        size = -1
    digest = _sha256_file(path) if size >= 0 else ""
    session_count = len(root.findall("sessioninfo"))
    packages = root.findall("package")
    package_count = len(packages)
    class_count = 0
    classes_line = []
    for pkg in packages:
        pkg_name = pkg.get("name", "")
        for cls in pkg.findall("class"):
            class_count += 1
            cls_name = cls.get("name", "")
            src = cls.get("sourcefilename", "")
            cmap = _counter_map(cls)
            line = cmap.get("LINE")
            if line is not None:
                classes_line.append(
                    {
                        "class": cls_name,
                        "package": pkg_name,
                        "sourcefilename": src,
                        "line_missed": line["missed"],
                        "line_covered": line["covered"],
                    }
                )
    classes_line.sort(key=lambda e: (e["package"], e["class"]))
    report_counters = _full_counters(_counter_map(root))
    return {
        "path": rel,
        "bytes": int(size),
        "sha256": digest,
        "name": root.get("name", ""),
        "session_count": session_count,
        "package_count": package_count,
        "class_count": class_count,
        "counters": report_counters,
        "classes_line": classes_line,
    }


def _parse_junit(path: Path, rel: str, parse_errors):
    """Parse a JUnit XML file. Return list of suite dicts (may be empty)."""
    try:
        tree = ET.parse(str(path))
    except ET.ParseError as exc:
        parse_errors.append({"file": rel, "kind": "junit", "error": f"parse: {exc}"})
        return []
    root = tree.getroot()
    if root.tag == "testsuites":
        suite_elems = root.findall("testsuite")
    elif root.tag == "testsuite":
        suite_elems = [root]
    else:
        parse_errors.append(
            {"file": rel, "kind": "junit", "error": f"unexpected root <{root.tag}>"}
        )
        return []
    suites = []
    for ts in suite_elems:
        name = ts.get("name", "")
        try:
            tests = int(ts.get("tests", "0") or 0)
            failures = int(ts.get("failures", "0") or 0)
            errors = int(ts.get("errors", "0") or 0)
            skipped = int(ts.get("skipped", "0") or 0)
        except (TypeError, ValueError):
            parse_errors.append(
                {"file": rel, "kind": "junit", "error": "non-integer suite counts"}
            )
            continue
        try:
            time_val = float(ts.get("time", "0") or 0)
        except (TypeError, ValueError):
            time_val = 0.0
        failed = []
        for tc in ts.findall("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                cname = tc.get("classname", "")
                tname = tc.get("name", "")
                failed.append(f"{cname}.{tname}" if cname else tname)
        failed.sort()
        suites.append(
            {
                "path": rel,
                "name": name,
                "tests": tests,
                "failures": failures,
                "errors": errors,
                "skipped": skipped,
                "time": time_val,
                "failed_tests": failed,
            }
        )
    return suites


def _scan_log(path: Path, rel: str):
    """Scan a Gradle log for signal keywords. Return list of hit dicts."""
    hits = []
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, 1):
                stripped = line.rstrip("\r\n")
                if not stripped:
                    continue
                for label, pat in LOG_PATTERNS:
                    if pat.search(stripped):
                        content = stripped
                        if len(content) > MAX_LOG_LINE_LEN:
                            content = content[:MAX_LOG_LINE_LEN] + "..."
                        hits.append(
                            {
                                "file": rel,
                                "line": lineno,
                                "keyword": label,
                                "content": content,
                            }
                        )
    except OSError:
        return []
    hits.sort(key=lambda h: (h["file"], h["line"], h["keyword"]))
    return hits


def _aggregate_junit(suites):
    totals = {
        "tests": sum(s["tests"] for s in suites),
        "failures": sum(s["failures"] for s in suites),
        "errors": sum(s["errors"] for s in suites),
        "skipped": sum(s["skipped"] for s in suites),
        "time": round(sum(s["time"] for s in suites), 6),
    }
    failed = []
    for s in suites:
        failed.extend(s["failed_tests"])
    failed = sorted(set(failed))
    return totals, failed


def _aggregate_jacoco(reports):
    """Aggregate report-level counters across all JaCoCo reports."""
    agg = {t: {"missed": 0, "covered": 0} for t in COUNTER_TYPES}
    for r in reports:
        for t in COUNTER_TYPES:
            c = r["counters"].get(t)
            if c:
                agg[t]["missed"] += c["missed"]
                agg[t]["covered"] += c["covered"]
    return agg


def build_summary(root: Path, gradle_log_glob: str, exclude: set):
    parse_errors = []
    jacoco_reports = []
    junit_suites = []
    log_signals = []

    # JaCoCo XML discovery: any *.xml under a jacoco reports path.
    jacoco_xmls = sorted(
        p for p in root.glob("**/build/reports/jacoco/**/*.xml")
        if p.is_file() and not any(part in PRUNE_DIRS for part in p.parts)
    )
    for p in jacoco_xmls:
        rel = p.relative_to(root).as_posix()
        if rel in exclude:
            continue
        rep = _parse_jacoco(p, rel, parse_errors)
        if rep is not None:
            jacoco_reports.append(rep)
    jacoco_reports.sort(key=lambda r: r["path"])

    # JUnit XML discovery.
    junit_xmls = sorted(
        p for p in root.glob("**/build/test-results/**/*.xml")
        if p.is_file() and not any(part in PRUNE_DIRS for part in p.parts)
    )
    for p in junit_xmls:
        rel = p.relative_to(root).as_posix()
        if rel in exclude:
            continue
        junit_suites.extend(_parse_junit(p, rel, parse_errors))
    junit_suites.sort(key=lambda s: (s["path"], s["name"]))

    # Gradle log signals.
    log_paths = sorted(
        p for p in root.glob(gradle_log_glob)
        if p.is_file() and not any(part in PRUNE_DIRS for part in p.parts)
    )
    for p in log_paths:
        rel = p.relative_to(root).as_posix()
        log_signals.extend(_scan_log(p, rel))
    log_signals.sort(key=lambda h: (h["file"], h["line"], h["keyword"]))

    junit_totals, failed_tests = _aggregate_junit(junit_suites)
    jacoco_totals = _aggregate_jacoco(jacoco_reports)

    exec_files = sorted(
        p.relative_to(root).as_posix()
        for p in root.glob("**/build/jacoco/**/*.exec")
        if p.is_file() and not any(part in PRUNE_DIRS for part in p.parts)
    )

    summary = {
        "schema_version": 1,
        "generated_at_utc": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "root": str(root),
        "gradle_log_glob": gradle_log_glob,
        "jacoco": {
            "report_count": len(jacoco_reports),
            "exec_count": len(exec_files),
            "exec_files": exec_files,
            "totals": jacoco_totals,
            "reports": jacoco_reports,
        },
        "junit": {
            "suite_count": len(junit_suites),
            "totals": junit_totals,
            "failed_tests": failed_tests,
            "suites": junit_suites,
        },
        "log_signals": log_signals,
        "parse_errors": sorted(parse_errors, key=lambda e: (e.get("file", ""), e.get("kind", ""))),
    }
    return summary


def _is_under(parent: Path, child: Path) -> bool:
    """Return True if *child* is equal to or under *parent*."""
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Generate JaCoCo flake baseline summary and evidence manifest."
    )
    parser.add_argument("--root", default=".", help="Repository root to scan (default: .)")
    parser.add_argument("--output", required=True, help="Path to write summary JSON.")
    parser.add_argument(
        "--manifest",
        default=None,
        help="Path to write evidence manifest JSON. Omit to skip manifest.",
    )
    parser.add_argument(
        "--gradle-log-glob",
        default="evidence/gradle-*.log",
        help="Glob (relative to --root) for Gradle logs to scan (default: evidence/gradle-*.log)",
    )
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"error: root not a directory: {root}", file=sys.stderr)
        return 2

    exclude = set()
    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = (root / output_path)
    if _is_under(root, output_path):
        exclude.add(output_path.relative_to(root).as_posix())
    manifest_path = None
    if args.manifest:
        manifest_path = Path(args.manifest)
        if not manifest_path.is_absolute():
            manifest_path = (root / manifest_path)
        if _is_under(root, manifest_path):
            exclude.add(manifest_path.relative_to(root).as_posix())

    summary = build_summary(root, args.gradle_log_glob, exclude)

    if manifest_path is not None:
        manifest = _build_manifest(root, exclude)
        summary["manifest_path"] = (
            manifest_path.relative_to(root).as_posix()
            if _is_under(root, manifest_path)
            else str(manifest_path)
        )
        summary["manifest_count"] = len(manifest)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        with manifest_path.open("w", encoding="utf-8") as fh:
            json.dump(manifest, fh, sort_keys=True, indent=2)
            fh.write("\n")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as fh:
        json.dump(summary, fh, sort_keys=True, indent=2)
        fh.write("\n")

    print(f"summary: {output_path}")
    if manifest_path is not None:
        print(f"manifest: {manifest_path}")
    print(
        f"jacoco_reports={summary['jacoco']['report_count']} "
        f"exec={summary['jacoco']['exec_count']} "
        f"junit_suites={summary['junit']['suite_count']} "
        f"failed_tests={len(summary['junit']['failed_tests'])} "
        f"log_signals={len(summary['log_signals'])} "
        f"parse_errors={len(summary['parse_errors'])}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
