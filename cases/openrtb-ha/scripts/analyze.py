#!/usr/bin/env python3
"""Summarize loadgen reports and failover drills. Standard library only.

  analyze.py table    <report.json> [...]   one markdown row per run
  analyze.py failover <drill-dir>           RTO, client-visible errors, re-join window, billing
  analyze.py snapshot <port> [port ...]     bidder CPU and served-request counters, as JSON

A drill directory holds report.json (loadgen), drill.json (kill/restart epoch ms) and
billing-check.txt (ledger reconciliation). The summary is printed and written to summary.md.
"""
import json
import math
import pathlib
import sys

SLO_BAD_FRACTION = 0.01  # a second is "healthy" when under 1% of its requests are late or errors


def load_json(path):
    return json.loads(pathlib.Path(path).read_text())


def bad_fraction(t):
    return (t["late"] + t["error"]) / t["total"] if t["total"] else 0.0


SERVED_URIS = ("/bid", "/burl", "/nurl")


def snapshot(ports):
    """Sum CPU seconds and served-request counts across bidder instances, as JSON on stdout.

    The bidder's own process_cpu_seconds_total is used, so the load generator's CPU is excluded.
    """
    import urllib.request

    cpu_seconds = 0.0
    served = 0.0
    for port in ports:
        url = f"http://localhost:{port}/actuator/prometheus"
        body = urllib.request.urlopen(url, timeout=5).read().decode()
        for line in body.splitlines():
            # Micrometer exports nanoseconds (process_cpu_time_ns_total), not Prometheus seconds.
            if line.startswith("process_cpu_time_ns_total "):
                cpu_seconds += float(line.split()[1]) / 1e9
            elif line.startswith("http_server_requests_seconds_count{") and any(
                f'uri="{u}"' in line for u in SERVED_URIS
            ):
                served += float(line.rsplit(" ", 1)[1])
    print(json.dumps({"cpuSeconds": cpu_seconds, "servedRequests": served}))


def server_cpu_us_per_request(run_dir):
    """Server CPU microseconds per served request over one run, or None if not measured."""
    path = pathlib.Path(run_dir) / "server.json"
    if not path.exists():
        return None
    snap = load_json(path)
    cpu = snap["after"]["cpuSeconds"] - snap["before"]["cpuSeconds"]
    served = snap["after"]["servedRequests"] - snap["before"]["servedRequests"]
    return cpu * 1e6 / served if served else None


def table(paths):
    print(
        "| run | format | cold | rps | requests | bid | nobid | late | error | p50 µs | p99 µs"
        " | p999 µs | request B | bid response B | server CPU µs/req | max dispatch lag ms |"
    )
    print("|" + "---|" * 16)
    for p in paths:
        r = load_json(p)
        o, m, lat, pay = r["options"], r["measured"], r["latencyUs"], r["payload"]
        cpu = server_cpu_us_per_request(pathlib.Path(p).parent)
        cpu_text = f"{cpu:.0f}" if cpu is not None else "n/a"
        print(
            f"| {pathlib.Path(p).parent.name} | {o['format']} | {o['coldConnections']} | {o['rps']}"
            f" | {m['requests']} | {m['bid']} | {m['nobid']} | {m['late']} | {m['error']}"
            f" | {lat['p50']} | {lat['p99']} | {lat['p999']}"
            f" | {pay['requestBytesAvg']:.0f} | {pay['bidResponseBytesAvg']:.0f}"
            f" | {cpu_text} | {r['generator']['maxDispatchLagUs'] / 1000:.0f} |"
        )


def failover(drill_dir):
    d = pathlib.Path(drill_dir)
    r = load_json(d / "report.json")
    drill = load_json(d / "drill.json")
    timeline = r["timeline"]
    origin = r["startEpochMs"]
    kill_s = (drill["killEpochMs"] - origin) / 1000
    restart_s = (drill["restartEpochMs"] - origin) / 1000
    kill_sec = math.floor(kill_s)
    measured = [t for t in timeline if t["phase"] == "measure"]

    # Client-visible errors: the 5xx or transport failures a caller would see.
    error_secs = [t["sec"] for t in timeline if t["error"] > 0 and t["sec"] >= kill_sec]
    # SLO recovery: the first second from which three consecutive seconds are healthy.
    by_sec = {t["sec"]: t for t in timeline}
    recovery = None
    for sec in range(kill_sec, len(timeline) - 2):
        if all(bad_fraction(by_sec[s]) < SLO_BAD_FRACTION for s in (sec, sec + 1, sec + 2)):
            recovery = sec
            break
    # Re-join window: unhealthy seconds after the victim came back.
    rejoin_secs = [
        t["sec"] for t in timeline if t["sec"] >= restart_s and bad_fraction(t) >= SLO_BAD_FRACTION
    ]
    # Baseline: the unhealthy fraction before the kill, for comparison.
    pre = [t for t in measured if t["sec"] < kill_sec]
    pre_total = sum(t["total"] for t in pre)
    pre_bad = sum(t["late"] + t["error"] for t in pre)

    billing = r["billing"]
    ledger = {}
    check = d / "billing-check.txt"
    if check.exists():
        for line in check.read_text().splitlines():
            if "=" in line and not line.startswith("spend"):
                key, _, value = line.partition("=")
                ledger[key.strip()] = value.strip()
    ledger_rows = int(ledger.get("ledger_rows_in_run", -1))
    match = ledger_rows == billing["distinctBilledBids"]

    lines = [
        f"# Failover drill: {d.name}",
        "",
        f"- Victim: {drill['victim']} ({drill['killMode']}) at +{kill_s:.2f}s, "
        f"restarted at +{restart_s:.2f}s",
        f"- Load: {r['options']['rps']} rps, {r['options']['warmupSec']}s warm-up, "
        f"{r['options']['durationSec']}s measured",
        "",
        "## Client-visible impact",
        f"- Requests: {r['measured']['requests']}, errors: {r['measured']['error']}"
        f", late (past {r['options']['deadlineMs']} ms deadline): {r['measured']['late']}",
        f"- Seconds with a client-visible error: {error_secs or 'none'}",
        f"- Recovery to < {SLO_BAD_FRACTION:.0%} late-or-error: "
        + (
            f"second {recovery}, {recovery - kill_s:.2f}s after the kill"
            if recovery is not None
            else "not reached"
        ),
        f"- Re-join window (seconds >= restart with >= {SLO_BAD_FRACTION:.0%} unhealthy): "
        f"{rejoin_secs or 'none'}",
        f"- Pre-kill unhealthy fraction: {pre_bad / pre_total:.3%}" if pre_total else "",
        f"- p99 over the measured window: {r['latencyUs']['p99']} us,"
        f" p999: {r['latencyUs']['p999']} us",
        "",
        "## Billing reconciliation",
        f"- Notices attempted: {billing['burlSent']}, attempts: {billing.get('burlAttempts', 'n/a')},"
        f" retried: {billing.get('burlRetried', 'n/a')}, unresolved: {billing['burlErr']}",
        f"- Distinct billed bids (loadgen): {billing['distinctBilledBids']}",
        f"- Ledger rows written in run: {ledger_rows}",
        f"- Duplicate bid ids in ledger: {ledger.get('duplicate_bid_ids', 'n/a')}",
        f"- Duplicate notices sent: {billing['dupSent']}, acknowledged: {billing['dupOk']}",
        f"- Result: {'MATCH' if match else 'MISMATCH'}",
    ]
    text = "\n".join(line for line in lines if line != "") + "\n"
    print(text)
    (d / "summary.md").write_text(text)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, args = argv[0], argv[1:]
    if cmd == "table" and args:
        table(args)
    elif cmd == "failover" and len(args) == 1:
        failover(args[0])
    elif cmd == "snapshot" and args:
        snapshot(args)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
