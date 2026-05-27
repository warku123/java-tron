#!/usr/bin/env python3
"""Simulate reviewdog filter_mode=added locally.

Runs (or reuses) `./gradlew :framework:checkstyleStrict`, then intersects the
resulting Checkstyle XML report with `git diff` against a base ref (default:
develop). Only violations on lines actually added/modified in the diff are
reported -- mirroring what the CI Check would do.

Usage:
  scripts/checkstyle-diff.py                     # diff vs develop, run gradle
  scripts/checkstyle-diff.py --base master       # different base
  scripts/checkstyle-diff.py --skip-build        # reuse existing report
  scripts/checkstyle-diff.py --working-tree      # include uncommitted changes
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def repo_root() -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], text=True
    ).strip()


def run_gradle(root: str) -> None:
    print("[1/3] Running ./gradlew :framework:checkstyleStrict ...", flush=True)
    subprocess.run(
        ["./gradlew", ":framework:checkstyleStrict", "-q"],
        cwd=root,
        check=True,
    )


def get_diff(root: str, base: str, working_tree: bool) -> str:
    """Return unified=0 diff text, restricted to *.java files."""
    if working_tree:
        spec = [base]
    else:
        spec = [f"{base}...HEAD"]
    cmd = ["git", "diff", "--unified=0", *spec, "--", "*.java"]
    return subprocess.check_output(cmd, cwd=root, text=True)


def parse_diff(diff_text: str) -> dict[str, set[int]]:
    """Return {repo-relative path: set(line numbers in NEW file)}."""
    added: dict[str, set[int]] = defaultdict(set)
    cur_file: str | None = None
    for line in diff_text.splitlines():
        if line.startswith("+++ b/"):
            cur_file = line[6:]
        elif line.startswith("+++ /dev/null"):
            cur_file = None
        elif line.startswith("@@") and cur_file:
            m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
            if not m:
                continue
            start = int(m.group(1))
            count = int(m.group(2)) if m.group(2) else 1
            if count == 0:
                continue
            for ln in range(start, start + count):
                added[cur_file].add(ln)
    return added


def parse_report(report_path: str, root: str) -> list[tuple[str, int, str, str]]:
    tree = ET.parse(report_path)
    out: list[tuple[str, int, str, str]] = []
    prefix = root.rstrip("/") + "/"
    for f in tree.getroot().findall("file"):
        name = f.get("name") or ""
        if name.startswith(prefix):
            name = name[len(prefix):]
        for e in f.findall("error"):
            line = int(e.get("line", "0"))
            src = e.get("source", "") or ""
            rule = src.split(".")[-1] or "unknown"
            msg = e.get("message", "") or ""
            out.append((name, line, rule, msg))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="develop", help="base ref (default: develop)")
    parser.add_argument("--skip-build", action="store_true", help="reuse existing strict.xml")
    parser.add_argument(
        "--working-tree",
        action="store_true",
        help="diff against working tree (include uncommitted) instead of HEAD",
    )
    parser.add_argument("--show-noise", action="store_true",
                        help="also print total report size for sanity")
    args = parser.parse_args()

    root = repo_root()
    report = os.path.join(root, "framework/build/reports/checkstyle/strict.xml")

    if not args.skip_build:
        run_gradle(root)
    if not os.path.exists(report):
        print(f"Report not found: {report}", file=sys.stderr)
        return 2

    print(f"[2/3] Reading diff against {args.base}"
          f"{' (working tree)' if args.working_tree else ' ...HEAD'} ...", flush=True)
    diff_text = get_diff(root, args.base, args.working_tree)
    added = parse_diff(diff_text)

    print("[3/3] Filtering violations to diff lines ...", flush=True)
    violations = parse_report(report, root)

    if args.show_noise:
        print(f"\n[debug] report has {len(violations)} total violations across "
              f"{len({v[0] for v in violations})} files")

    print(f"\n=== Diff-filtered Checkstyle ===")
    if not added:
        print(f"No Java changes in diff vs {args.base}.")
        return 0
    print(f"Files with changes:    {len(added)}")
    print(f"Added/modified lines:  {sum(len(v) for v in added.values())}")

    matched = [
        (vf, vl, vr, vm)
        for vf, vl, vr, vm in violations
        if vl in added.get(vf, ())
    ]
    print(f"Violations on those lines: {len(matched)}\n")

    if not matched:
        print("PASS — no diff-line violations. PR would pass the Check.")
        return 0

    print("FAIL — violations on changed lines:\n")
    by_rule: dict[str, list[tuple[str, int, str]]] = defaultdict(list)
    for vf, vl, vr, vm in matched:
        by_rule[vr].append((vf, vl, vm))
    for rule, items in sorted(by_rule.items(), key=lambda x: -len(x[1])):
        print(f"  [{rule}]  ({len(items)} hit{'s' if len(items) > 1 else ''})")
        for vf, vl, vm in items:
            print(f"    {vf}:{vl}")
            print(f"      {vm}")
        print()
    return 1


if __name__ == "__main__":
    sys.exit(main())
