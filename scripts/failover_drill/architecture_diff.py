from __future__ import annotations

import re

_USING = re.compile(r"^\s*using:\s*['\"]?([^'\"\s#]+)")
_RUN_FOLDED = re.compile(r"^(?P<indent>\s*)(?:-\s*)?run:\s*(\|[>+]?|[>+])\s*$")
_RUN_INLINE = re.compile(r"^\s*(?:-\s*)?run:\s*(\S.*?)\s*$")


def composite_shell(
    action_yaml: str,
    *,
    trace: str,
    report: str,
    comment: str,
    action_path: str,
) -> str | None:
    commands = _composite_commands(action_yaml)
    if commands is None:
        return None
    rendered = [
        _substitute(command, trace=trace, report=report, comment=comment, action_path=action_path)
        for command in commands
    ]
    body = "\n\n".join(command.rstrip() for command in rendered if command.strip())
    return f"set -euo pipefail\n{body}\n" if body else "set -euo pipefail\n"


def _composite_commands(action_yaml: str) -> list[str] | None:
    lines = action_yaml.splitlines()
    using = next((match.group(1) for line in lines if (match := _USING.search(line))), None)
    if using != "composite":
        return None
    commands: list[str] = []
    index = 0
    while index < len(lines):
        folded = _RUN_FOLDED.match(lines[index])
        if folded:
            base = len(folded.group("indent"))
            block: list[str] = []
            index += 1
            while index < len(lines):
                line = lines[index]
                if line.strip() and _indent(line) <= base:
                    break
                block.append(line)
                index += 1
            commands.append(_dedent(block))
            continue
        inline = _RUN_INLINE.match(lines[index])
        if inline:
            commands.append(inline.group(1).strip().strip("'\""))
        index += 1
    return commands


def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _dedent(block: list[str]) -> str:
    indents = [_indent(line) for line in block if line.strip()]
    trim = min(indents) if indents else 0
    trimmed = [line[trim:] if line.startswith(" " * trim) else line for line in block]
    return "\n".join(trimmed).strip("\n")


def _substitute(command: str, *, trace: str, report: str, comment: str, action_path: str) -> str:
    replacements = {
        "${{ inputs.trace }}": trace,
        "${{ inputs.report }}": report,
        "${{ inputs.comment }}": comment,
        "${{ github.action_path }}": action_path,
    }
    for token, value in replacements.items():
        command = command.replace(token, value)
    return command
