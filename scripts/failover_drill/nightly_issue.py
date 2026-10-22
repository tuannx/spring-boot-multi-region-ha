from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

from failover_drill.nightly_report import render_nightly_markdown

MARKER = "<!-- nightly-failover-drill -->"
TITLE = "[DR] Nightly failover drill"


@dataclass(frozen=True)
class IssuePlan:
    action: str
    number: int | None
    title: str
    body: str
    close_numbers: tuple[int, ...]

    def to_dict(self) -> dict:
        return {
            "action": self.action,
            "number": self.number,
            "title": self.title,
            "body": self.body,
            "close_numbers": list(self.close_numbers),
        }


def render_issue_body(report: dict) -> str:
    markdown = render_nightly_markdown(report).rstrip()
    document = json.dumps(report, indent=2, sort_keys=True)
    return (
        f"{MARKER}\n\n"
        f"{markdown}\n\n"
        "<details>\n"
        "<summary>drill-report.json</summary>\n\n"
        "```json\n"
        f"{document}\n"
        "```\n"
        "</details>\n"
    )


def render_recovery_comment(report: dict) -> str:
    run_url = report.get("workflow_run_url") or "the workflow run"
    sha = report.get("commit_sha") or "unknown"
    return (
        f"{MARKER}\n\n"
        f"The nightly failover drill passed for `{sha}`.\n\n"
        f"{run_url}\n\n"
        "These numbers are a local mock (containers + Toxiproxy), not real Aurora Global.\n"
    )


def plan_tracking_issue(report: dict, open_issues: list[dict]) -> IssuePlan:
    matching = _matching_issues(open_issues)
    chosen = matching[0] if matching else None
    extras = tuple(issue["number"] for issue in matching[1:])
    passed = _nightly_passed(report)
    if passed:
        if chosen is None:
            return IssuePlan("none", None, TITLE, "", extras)
        return IssuePlan("close", int(chosen["number"]), TITLE, render_recovery_comment(report), extras)
    body = render_issue_body(report)
    if chosen is None:
        return IssuePlan("create", None, TITLE, body, extras)
    return IssuePlan("update", int(chosen["number"]), TITLE, body, extras)


def _matching_issues(open_issues: list[dict]) -> list[dict]:
    matching = []
    for issue in open_issues:
        title = str(issue.get("title") or "")
        body = str(issue.get("body") or "")
        if title == TITLE or MARKER in body:
            if "number" not in issue:
                raise ValueError("open issue is missing number")
            matching.append(issue)
    matching.sort(key=lambda issue: int(issue["number"]))
    return matching


def _nightly_passed(report: dict) -> bool:
    nightly = report.get("nightly_gate")
    if isinstance(nightly, dict):
        return bool(nightly.get("passed"))
    return bool((report.get("gate") or {}).get("passed"))


def cli(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Plan the single nightly failover tracking issue.")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--issues", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    args = parser.parse_args(argv)
    report = json.loads(args.report.read_text(encoding="utf-8"))
    issues = json.loads(args.issues.read_text(encoding="utf-8"))
    if not isinstance(issues, list):
        raise ValueError("issues JSON must be a list")
    plan = plan_tracking_issue(report, issues)
    args.plan.write_text(json.dumps(plan.to_dict(), indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(cli())
