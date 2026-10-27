from __future__ import annotations

import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


class StackError(RuntimeError):
    pass


class ComposeStack:
    def __init__(
        self,
        root: Path,
        overlay: str | None = None,
        *,
        project_name: str | None = None,
        compose_files: list[str] | None = None,
    ):
        self.root = root
        self.overlay = overlay
        self.project_name = project_name
        if compose_files is None:
            self.compose_files = [
                "-f",
                "docker-compose.yml",
                "-f",
                "docker-compose.failover-drill.yml",
            ]
            if overlay:
                self.compose_files.extend(["-f", overlay])
        else:
            self.compose_files = compose_files

    def ensure_images(self) -> bool:
        eu = "multiregion-app-eu:latest"
        us = "multiregion-app-us:latest"
        if self._image_exists(eu) and self._image_exists(us):
            return False
        self._compose("build", "app-eu")
        self._run(["docker", "tag", eu, us])
        return True

    def up(self) -> None:
        services = [
            "otel-collector",
            "postgres-us",
            "postgres-eu",
            "rabbitmq-us",
            "rabbitmq-eu",
            "app-us",
            "app-eu",
        ]
        if self.overlay:
            services = ["toxiproxy", *services]
        self.up_services(services)

    def up_services(self, services: list[str]) -> None:
        self._compose("up", "-d", "--remove-orphans", *services)

    def stop_named(self, containers: list[str]) -> None:
        if containers:
            self._run(["docker", "stop", *containers], check=False)

    def start_named(self, containers: list[str]) -> None:
        if containers:
            self._run(["docker", "start", *containers])

    def down(self) -> None:
        self._compose("down", "--volumes", "--remove-orphans", check=False)

    def stop_apps(self) -> None:
        self._run(["docker", "stop", "multiregion-app-us", "multiregion-app-eu"], check=False)

    def start_apps(self) -> None:
        self._run(["docker", "start", "multiregion-app-us", "multiregion-app-eu"])

    def kill(self, container: str) -> None:
        self._run(["docker", "kill", container])

    def restart(self, container: str) -> None:
        self._run(["docker", "restart", container])

    def start(self, container: str) -> None:
        self._run(["docker", "start", container])

    def container_status(self, container: str) -> str:
        completed = self._run(
            ["docker", "inspect", "-f", "{{.State.Status}}", container],
            check=False,
        )
        if completed.returncode != 0:
            return "missing"
        return completed.stdout.strip()

    def psql(self, container: str, sql: str) -> str:
        completed = self._run(
            [
                "docker",
                "exec",
                "-i",
                "-e",
                "PGPASSWORD=apppass",
                container,
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                "appuser",
                "-d",
                "appdb",
                "-Atq",
            ],
            input_text=sql,
            check=False,
        )
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout).strip()
            raise StackError(f"psql on {container} failed: {detail}")
        return completed.stdout.strip()

    def wait_postgres(self, container: str, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        last_error = "not ready"
        while time.monotonic() < deadline:
            completed = self._run(
                ["docker", "exec", container, "pg_isready", "-U", "appuser", "-d", "appdb"],
                check=False,
            )
            if completed.returncode == 0:
                return
            last_error = (completed.stderr or completed.stdout).strip()
            time.sleep(1)
        raise StackError(f"{container} was not ready within {timeout_seconds}s: {last_error}")

    def wait_http(self, url: str, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        last_error: Exception | None = None
        target = url.rstrip("/") + "/actuator/health"
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(target, timeout=3) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
                last_error = exc
            time.sleep(1)
        raise StackError(f"{target} was not ready within {timeout_seconds}s: {last_error}")

    def export_traces(self, destination: Path, stop_applications: bool) -> bool:
        if self.container_status("multiregion-otel-collector") != "running":
            return False
        if stop_applications:
            self._run(
                ["docker", "stop", "-t", "25", "multiregion-app-us", "multiregion-app-eu"],
                check=False,
            )
        time.sleep(5)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            destination.unlink()
        completed = self._run(
            [
                "docker",
                "cp",
                "multiregion-otel-collector:/traces/otlp-trace.json",
                str(destination),
            ],
            check=False,
        )
        return completed.returncode == 0 and destination.is_file() and destination.stat().st_size > 0

    def write_logs(self, path: Path) -> None:
        completed = self._compose("logs", "--no-color", check=False)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(completed.stdout + completed.stderr, encoding="utf-8")

    def _image_exists(self, name: str) -> bool:
        completed = self._run(["docker", "image", "inspect", name], check=False)
        return completed.returncode == 0

    def _compose(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        command = ["docker", "compose", "--ansi", "never"]
        if self.project_name:
            command.extend(["--project-name", self.project_name])
        command.extend(self.compose_files)
        command.extend(args)
        return self._run(command, check=check)

    def _run(
        self,
        command: list[str],
        check: bool = True,
        input_text: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        completed = subprocess.run(
            command,
            cwd=self.root,
            input=input_text,
            text=True,
            capture_output=True,
            check=False,
        )
        if check and completed.returncode != 0:
            detail = (completed.stderr or completed.stdout).strip()
            raise StackError(f"{' '.join(command)} failed: {detail[-4000:]}")
        return completed
