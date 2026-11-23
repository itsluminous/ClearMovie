#!/usr/bin/env python3
"""Checks that the instrumented test shards of one API level ran every test.

    scripts/check-instrumented-shards.py <shards-dir> <shard-count> <api-level>

CI's instrumented job runs it after downloading the report artifact of every
shard of one API level into <shards-dir>, one folder per shard, named
instrumented-reports-api-<api-level>-shard-<n>. It fails unless:
  1. every shard 1..<shard-count> left its JUnit XML, and no other shard did;
  2. no shard reported a failure or an error, or a non-zero exit code;
  3. shard 1 left the full list of tests (scripts/ci-instrumented-shard.sh);
  4. the shards together ran exactly that list, each test exactly once.
It prints the test count, skipped count, class list and each shard's share,
and adds them to the job summary.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def read_suite(path):
    """The (class, test) names in one JUnit XML file, and its skipped tests."""
    root = ET.parse(path).getroot()
    tests, skipped, bad = [], set(), []
    for case in root.iter("testcase"):
        name = (case.get("classname", ""), case.get("name", ""))
        tests.append(name)
        if case.find("skipped") is not None:
            skipped.add(name)
        if case.find("failure") is not None or case.find("error") is not None:
            bad.append(name)
    return tests, skipped, bad


def one_xml(folder, what):
    found = glob.glob(os.path.join(folder, "**", "TEST-*.xml"), recursive=True)
    if len(found) != 1:
        fail(f"{what}: expected one TEST-*.xml under {folder}, found {len(found)}.")
    return found[0]


def main():
    if len(sys.argv) != 4:
        fail("Usage: check-instrumented-shards.py <shards-dir> <shard-count> <api-level>")
    shards_dir, count_text, api = sys.argv[1:]
    # At least 2: download-artifact puts a lone matching artifact straight into
    # <shards-dir> instead of a folder named after it, so one shard cannot be
    # told apart from a run where only one shard's report was uploaded.
    if not count_text.isdigit() or int(count_text) < 2:
        fail(f"Shard count {count_text!r} is not a whole number of at least 2.")
    count = int(count_text)

    prefix = f"instrumented-reports-api-{api}-shard-"
    entries = os.listdir(shards_dir) if os.path.isdir(shards_dir) else []
    if "outputs" in entries or "reports" in entries:
        fail(f"API {api}: only one shard's report was downloaded (download-artifact unpacks a lone "
             f"artifact without its folder); expected {count}. Check which shard left none, "
             "then re-run the failed jobs.")
    present = sorted(d for d in entries if d.startswith(prefix))
    expected = [f"{prefix}{n}" for n in range(1, count + 1)]
    if present != sorted(expected):
        fail(f"API {api}: expected the reports of shards {expected}, found {present}. "
             "A shard that left no report did not finish; re-run the failed jobs.")

    ran = {}
    skipped = set()
    rows = []
    for n in range(1, count + 1):
        folder = os.path.join(shards_dir, f"{prefix}{n}")
        results = os.path.join(folder, "outputs", "androidTest-results")
        for code_file in glob.glob(os.path.join(results, "**", "test-result-exit-code.txt"), recursive=True):
            code = open(code_file, encoding="utf-8").read().strip()
            if code != "0":
                fail(f"API {api} shard {n}: test exit code {code!r} ({code_file}).")
        tests, shard_skipped, bad = read_suite(one_xml(results, f"API {api} shard {n}"))
        if bad:
            fail(f"API {api} shard {n}: {len(bad)} tests failed, first {bad[0][0]}.{bad[0][1]}.")
        for name in tests:
            if name in ran:
                fail(f"API {api}: {name[0]}.{name[1]} ran in shard {ran[name]} and shard {n}.")
            ran[name] = n
        skipped |= shard_skipped
        rows.append((n, len(tests), len(shard_skipped)))

    listing = os.path.join(shards_dir, f"{prefix}1", "outputs", "androidTest-list")
    listed, _, _ = read_suite(one_xml(listing, f"API {api} test list"))
    if len(set(listed)) != len(listed):
        fail(f"API {api}: the test list names a test twice.")
    missing = sorted(set(listed) - set(ran))
    extra = sorted(set(ran) - set(listed))
    if missing:
        fail(f"API {api}: {len(missing)} listed tests ran in no shard, first {missing[0][0]}.{missing[0][1]}.")
    if extra:
        fail(f"API {api}: {len(extra)} tests ran but were not listed, first {extra[0][0]}.{extra[0][1]}.")

    classes = sorted({c for c, _ in ran})
    lines = [
        f"### Instrumented tests, API {api}",
        f"{len(ran)} tests ran ({len(skipped)} skipped) in {len(classes)} classes "
        f"across {count} shards, exactly the {len(listed)} tests listed.",
        "",
        "| Shard | Tests | Skipped |",
        "| --- | --- | --- |",
        *[f"| {n} | {t} | {s} |" for n, t, s in rows],
        "",
        "<details><summary>Classes</summary>",
        "",
        *[f"- {c}" for c in classes],
        "",
        "</details>",
    ]
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
