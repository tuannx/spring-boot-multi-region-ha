# Arcade Agent Architecture Analysis

This project uses `arcade-agent` to analyze the Java architecture under `app/`.

## GitHub Actions

The workflow at `.github/workflows/arcade-agent-analysis.yml` runs on pull
requests, pushes to `main`, and manual dispatch. It uses the pinned composite
action and Python package version `0.1.1`:

```yaml
# v0.1.1
uses: lemduc/arcade-agent/actions/analyze@3d7f6130b22050979d2d18084a63bc6a932b9789
with:
  arcade-agent-version: "0.1.1"
  source-path: app
  language: java
```

On pull requests, the action posts or updates an architecture drift comment when
the workflow has permission to write PR comments. On pushes to `main`, it stores
the current analysis as a GitHub Actions baseline artifact for future PR
comparisons.

## Local Run

Use Python 3.12 because `arcade-agent` requires Python 3.12 or newer.

```bash
python3.12 -m venv /tmp/arcade-agent-venv
/tmp/arcade-agent-venv/bin/python -m pip install --upgrade pip
/tmp/arcade-agent-venv/bin/python -m pip install "arcade-agent[languages]==0.1.1"

/tmp/arcade-agent-venv/bin/arcade-self-analysis \
  --source app \
  --language java \
  --repo-name spring-boot-multi-region-ha \
  --algorithm pkg \
  --output-json arcade_analysis_results.json \
  --output-html arcade_analysis_report.html

/tmp/arcade-agent-venv/bin/arcade-log-analysis-summary arcade_analysis_results.json
```

## MCP Setup

The project includes `.mcp.json` so MCP-aware agents can start `arcade-agent`
from the repository root. The MCP command is:

```bash
bash scripts/arcade-mcp.sh
```

The wrapper creates `.arcade/mcp-venv/` on first use and installs the pinned MCP
package:

```bash
arcade-agent[mcp,languages]==0.1.1
```

The project MCP env values document the default target for agent calls:

| Env | Value |
|-----|-------|
| `ARCADE_AGENT_PROJECT_SOURCE` | `app` |
| `ARCADE_AGENT_PROJECT_LANGUAGE` | `java` |
| `ARCADE_AGENT_PROJECT_REPO_NAME` | `spring-boot-multi-region-ha` |

Useful MCP call sequence for this repository:

```text
parse(source_path="app", language="java")
recover(dep_graph="<parse session_id>", algorithm="pkg")
detect_smells(architecture="<recover session_id>", dep_graph="<parse session_id>")
compute_metrics(architecture="<recover session_id>", dep_graph="<parse session_id>")
context_for_task(dep_graph="<parse session_id>", task="change request text")
dependency_cone(dep_graph="<parse session_id>", target="app/src/main/java/...")
api_surface(dep_graph="<parse session_id>", target="app/src/main/java/...")
```

Local generated analysis files are ignored by git. If a git-tracked local
baseline is wanted later, generate it explicitly with:

```bash
/tmp/arcade-agent-venv/bin/arcade-arch-diff \
  --source app \
  --language java \
  --update-baseline
```
