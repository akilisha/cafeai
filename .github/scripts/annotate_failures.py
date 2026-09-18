#!/usr/bin/env python3
"""Turn a failed Gradle build into GitHub error annotations.

Reads build.log (the captured Gradle output) and every JUnit XML report under build/test-results,
and prints `::error` workflow commands, so the failures show on the run page and on pull requests
without opening the raw log. GitHub shows at most 10 error annotations per step, so individual
tests are capped and a final annotation lists everything.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX_INDIVIDUAL = 8


def esc_data(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def esc_prop(s: str) -> str:
    return esc_data(s).replace(":", "%3A").replace(",", "%2C")


def error(title: str, message: str) -> None:
    print(f"::error title={esc_prop(title)}::{esc_data(message)}")


failures = []  # (module, "Class.method", message)
for path in glob.glob("**/build/test-results/**/*.xml", recursive=True):
    module = path.split("/build/")[0]
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        bad = case.find("failure")
        if bad is None:
            bad = case.find("error")
        if bad is None:
            continue
        name = f"{case.get('classname', '?').rsplit('.', 1)[-1]}.{case.get('name', '?')}"
        msg = (bad.get("message") or bad.text or "").strip()
        failures.append((module, name, msg))

# Gradle-level failures: compilation errors and failed tasks, which have no JUnit report.
task_lines = []
if os.path.exists("build.log"):
    with open("build.log", encoding="utf-8", errors="replace") as f:
        for line in f:
            if re.search(r"> Task \S+ FAILED|error: |Execution failed for task|What went wrong", line):
                task_lines.append(line.rstrip())

if not failures and not task_lines:
    error("Build failed", "No test failures or failed Gradle tasks were found in the reports; see the raw log.")
    sys.exit(0)

for module, name, msg in failures[:MAX_INDIVIDUAL]:
    error(f"{module}: {name}", msg[:800])

summary = []
if failures:
    summary.append(f"{len(failures)} failing test(s):")
    summary += [f"  {m} :: {n}" for m, n, _ in failures]
if task_lines:
    summary.append("Gradle:")
    summary += [f"  {l}" for l in task_lines[:25]]
error("Build failure summary", "\n".join(summary)[:3500])
