# Arcade Agent Architecture Analysis

This project uses Arcade Agent 0.1.1 to analyze production Java code under
`app/src/main/java`. Local audits, MCP calls, and CI must use that exact source
root, Java, and the deterministic `pkg` recovery algorithm so before/after
results are comparable.

## Canonical Comparison Baseline

The canonical local baseline is
`.arcade/baselines/main-full.json`. It is a full `arcade-self-analysis` snapshot
enriched by `scripts/architecture-audit.sh` at commit
`08ac92545db3673ded5c4ef965ee42398cace4f8` with these fixed inputs:

| Input | Value |
|-------|-------|
| Source | `app/src/main/java` |
| Language | `java` |
| Recovery algorithm | `pkg` |
| Arcade Agent | `0.1.1` |

This snapshot is the **before** side of subsequent architecture work. Generate
the **after** snapshot with the same inputs and compare the two full snapshots;
do not regenerate the baseline after changing source code just to obtain a
favorable comparison.

The baseline records the previously verified hexagonal refactor at these key
values:

| Metric | Baseline |
|--------|---------:|
| BalancedArchitectureScore | 0.5235 |
| PrincipleAlignmentScore | 0.8729 |
| ComponentBalance | 0.5480 |
| HubBalance | 0.6471 |
| SmellDiscipline | 1.0000 |
| Architectural smells | 0 |

The boundaries represented by those values are executable, not package layout
alone: queue application code depends on domain and ports; Spring scheduling
lives in the configuration adapter; JDBC and RabbitMQ implement outbound
contracts; and ArchUnit tests reject reverse dependencies.

## Reproducible Local A/B Audit

Use the repository wrapper instead of assembling commands manually. The output
path is required so an audit cannot silently replace the tracked baseline.

Create a current full snapshot without comparing it:

```bash
scripts/architecture-audit.sh \
  --output-json /tmp/spring-mrha-architecture/current.json \
  --output-html /tmp/spring-mrha-architecture/current.html
```

If `--output-html` is omitted, the wrapper writes an HTML report beside the
required JSON output instead of leaving Arcade Agent's default report in the
repository root.

Compare the current source with the canonical baseline:

```bash
scripts/architecture-audit.sh \
  --output-json /tmp/spring-mrha-architecture/current.json \
  --output-html /tmp/spring-mrha-architecture/current.html \
  --compare \
  --comparison-md /tmp/spring-mrha-architecture/comparison.md \
  --comparison-html /tmp/spring-mrha-architecture/comparison.html
```

`--compare` selects `.arcade/baselines/main-full.json`. Use
`--baseline /path/to/another-full-self-analysis.json` to compare with another
intentionally selected, wrapper-enriched full snapshot. Raw
`arcade-self-analysis` JSON is suitable as the current side of a comparison,
but it is not accepted as a baseline because it lacks the wrapper provenance
fields. The wrapper bootstraps the pinned local
environment through `scripts/arcade-mcp.sh` when needed and refuses to compare
unless baseline metadata matches all canonical inputs: source, Arcade Agent
version, language, recovery algorithm, and repository name. It also records the current
Git commit and ref by setting `GITHUB_SHA` and `GITHUB_REF` when those values are
not already supplied by CI.

If `app/src/main/java` has uncommitted changes, the wrapper prints a warning.
The analysis includes those working-tree changes, while the recorded SHA still
identifies their base commit; use a clean worktree for durable baseline
evidence.

### Full snapshot versus structural baseline

Arcade Agent has two JSON artifact types with different schemas and consumers:

| Artifact | Producer | Consumer | Purpose |
|----------|----------|----------|---------|
| Wrapper-enriched full self-analysis snapshot | `scripts/architecture-audit.sh` | `arcade-compare-baseline` | Same-condition A/B metrics, smells, entities, components, score drivers, and comparable-input provenance |
| Structural diff baseline | `arcade-arch-diff --update-baseline` | `arcade-arch-diff` | Structural drift checks for that command only |

The structural diff baseline is **not** a valid input to
`arcade-compare-baseline`. Do not replace
`.arcade/baselines/main-full.json` with the architecture-only output of
`arcade-arch-diff --update-baseline`.

Only refresh the canonical full baseline when the team intentionally accepts a
new reference architecture. First generate the candidate with
`scripts/architecture-audit.sh` into a temporary path, review its commit SHA and
comparison, and then replace the tracked baseline in a separate, explicit step.
Do not promote raw CLI or MCP output to the baseline path.

## GitHub Actions

The workflow at `.github/workflows/arcade-agent-analysis.yml` runs on pull
requests, pushes to `main`, and manual dispatch. It uses the pinned composite
action and Python package version `0.1.1` with the same production source root:

```yaml
# v0.1.1
uses: lemduc/arcade-agent/actions/analyze@3d7f6130b22050979d2d18084a63bc6a932b9789
with:
  arcade-agent-version: "0.1.1"
  source-path: app/src/main/java
  language: java
  repo-name: spring-boot-multi-region-ha
  primary-algorithm: pkg
```

On pull requests, the action posts or updates an architecture drift comment when
the workflow has permission to write PR comments. On pushes to `main`, it stores
the current analysis as a GitHub Actions baseline artifact for future PR
comparisons. That workflow artifact is lifecycle-managed by GitHub Actions; the
tracked full snapshot above is the stable local A/B reference.

## Direct CLI Run

Use Python 3.12 because Arcade Agent requires Python 3.12 or newer. If direct
CLI access is needed, keep the same inputs used by the wrapper:

```bash
mkdir -p /tmp/spring-mrha-architecture
python3.12 -m venv /tmp/arcade-agent-venv
/tmp/arcade-agent-venv/bin/python -m pip install --upgrade pip
/tmp/arcade-agent-venv/bin/python -m pip install "arcade-agent[languages]==0.1.1"

/tmp/arcade-agent-venv/bin/arcade-self-analysis \
  --source app/src/main/java \
  --language java \
  --repo-name spring-boot-multi-region-ha \
  --algorithm pkg \
  --output-json /tmp/spring-mrha-architecture/current.json \
  --output-html /tmp/spring-mrha-architecture/current.html

/tmp/arcade-agent-venv/bin/arcade-compare-baseline \
  /tmp/spring-mrha-architecture/current.json \
  .arcade/baselines/main-full.json \
  --repo-name spring-boot-multi-region-ha \
  --output /tmp/spring-mrha-architecture/comparison.md
```

## MCP Setup

The project includes `.mcp.json` so MCP-aware agents can start Arcade Agent
from the repository root. The MCP command is:

```bash
bash scripts/arcade-mcp.sh
```

The wrapper creates `.arcade/mcp-venv/` on first use and installs the pinned MCP
package:

```text
arcade-agent[mcp,languages]==0.1.1
```

The `.mcp.json` environment values document the project defaults:

| Env | Value |
|-----|-------|
| `ARCADE_AGENT_PROJECT_SOURCE` | `app/src/main/java` |
| `ARCADE_AGENT_PROJECT_LANGUAGE` | `java` |
| `ARCADE_AGENT_PROJECT_REPO_NAME` | `spring-boot-multi-region-ha` |

Those environment values are context for clients and operators; Arcade MCP
tools do **not** automatically inject them into every call. Pass source,
language, recovery algorithm, session IDs, and target explicitly as required by
each tool. A consistent call sequence is:

```text
parse(source_path="app/src/main/java", language="java")
recover(dep_graph="<parse session_id>", algorithm="pkg")
detect_smells(architecture="<recover session_id>", dep_graph="<parse session_id>")
compute_metrics(architecture="<recover session_id>", dep_graph="<parse session_id>")
context_for_task(dep_graph="<parse session_id>", task="change request text")
dependency_cone(dep_graph="<parse session_id>", target="app/src/main/java/...")
api_surface(dep_graph="<parse session_id>", scope="com.multiregion.platform.failover")
```

For raw MCP `parse`, passing `app` instead of the production source root also
parses `src/test`. That changes the recovered graph and invalidates comparisons
with the canonical baseline. (`arcade-self-analysis` has a separate ingest step
that excludes tests, but explicit production paths avoid relying on that
difference.)
