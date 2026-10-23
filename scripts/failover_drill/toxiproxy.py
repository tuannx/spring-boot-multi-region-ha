from __future__ import annotations

import json
import time
import urllib.error
import urllib.request


class ToxiproxyError(RuntimeError):
    pass


def upstream_kill_needs_proxy_reset(fault: str, latency_ms: int) -> bool:
    return fault == "docker-kill-primary" and latency_ms > 0


def proxy_body(name: str, listen: str, upstream: str, enabled: bool) -> dict:
    return {
        "name": name,
        "listen": listen,
        "upstream": upstream,
        "enabled": enabled,
    }


def latency_toxic_body(latency_ms: int, jitter_ms: int, toxicity: float) -> dict:
    if type(latency_ms) is not int or latency_ms <= 0:
        raise ValueError("latency_ms must be a positive integer")
    if jitter_ms != 0:
        raise ValueError("jitter_ms must be 0 so the delay does not vary between connections")
    if toxicity != 1.0:
        raise ValueError("toxicity must be 1 so every connection takes the delay")
    return {
        "name": "slow-db",
        "type": "latency",
        "stream": "downstream",
        "toxicity": toxicity,
        "attributes": {
            "latency": latency_ms,
            "jitter": jitter_ms,
        },
    }


class ToxiproxyClient:
    def __init__(self, api_url: str):
        self.api_url = api_url.rstrip("/")

    def wait_ready(self, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        last_error = "not ready"
        while time.monotonic() < deadline:
            try:
                self._request("GET", "/version")
                return
            except ToxiproxyError as exc:
                last_error = str(exc)
            time.sleep(0.5)
        raise ToxiproxyError(f"Toxiproxy API was not ready within {timeout_seconds}s: {last_error}")

    def ensure_proxy(self, name: str, listen: str, upstream: str) -> None:
        current = self._request("GET", f"/proxies/{name}", allow_missing=True)
        body = proxy_body(name, listen, upstream, True)
        if current is None:
            self._request("POST", "/proxies", body, expected=(201,))
            return
        self._request("POST", f"/proxies/{name}", body)

    def enable(self, name: str) -> None:
        self._request("POST", f"/proxies/{name}", {"enabled": True})

    def disable(self, name: str) -> None:
        self._request("POST", f"/proxies/{name}", {"enabled": False})

    def is_enabled(self, name: str) -> bool:
        payload = self._request("GET", f"/proxies/{name}")
        if not isinstance(payload, dict) or "enabled" not in payload:
            raise ToxiproxyError(f"Toxiproxy proxy {name} did not report enabled")
        return bool(payload["enabled"])

    def clear_toxics(self, name: str) -> None:
        payload = self._request("GET", f"/proxies/{name}/toxics")
        if not isinstance(payload, list):
            raise ToxiproxyError(f"Toxiproxy toxics for {name} were not a list")
        for toxic in payload:
            toxic_name = str(toxic["name"])
            self._request("DELETE", f"/proxies/{name}/toxics/{toxic_name}", expected=(204,))

    def add_latency(self, name: str, latency_ms: int, jitter_ms: int, toxicity: float) -> dict:
        body = latency_toxic_body(latency_ms, jitter_ms, toxicity)
        self._request("POST", f"/proxies/{name}/toxics", body, expected=(200, 201))
        return body

    def _request(
        self,
        method: str,
        path: str,
        payload: dict | None = None,
        expected: tuple[int, ...] = (200,),
        allow_missing: bool = False,
    ) -> dict | list | None:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(self.api_url + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                status = response.status
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            if allow_missing and exc.code == 404:
                return None
            detail = raw.strip()[:500]
            raise ToxiproxyError(f"Toxiproxy {method} {path} failed with HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
            raise ToxiproxyError(f"Toxiproxy {method} {path} failed: {exc}") from exc
        if status not in expected:
            raise ToxiproxyError(f"Toxiproxy {method} {path} returned HTTP {status}")
        if not raw:
            return None
        parsed = json.loads(raw)
        if not isinstance(parsed, (dict, list)):
            raise ToxiproxyError(f"Toxiproxy {method} {path} returned {type(parsed).__name__}")
        return parsed
