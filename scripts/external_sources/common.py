"""Strict source decoding, reproducible identities and protected local outputs."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path, PureWindowsPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]


class ImportProblem(ValueError):
    """An unsupported or incomplete source must not produce a partial import."""


def require(condition, message):
    if not condition:
        raise ImportProblem(message)


def text(value, location, maximum=200):
    require(isinstance(value, str) and bool(value.strip()) and len(value) <= maximum
            and not any(ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF for char in value),
            f"{location}: expected nonempty Unicode text without control characters (max {maximum})")
    return value


def object_at(value, location):
    require(isinstance(value, dict), f"{location}: expected an object")
    return value


def array_at(value, location):
    require(isinstance(value, list), f"{location}: expected an array")
    return value


def timestamp(value, location):
    text(value, location, 40)
    require(bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)", value, re.ASCII)),
            f"{location}: expected RFC 3339 timestamp with an explicit time zone")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.utcoffset() is not None, f"{location}: time zone is required")
    except ValueError as error:
        raise ImportProblem(f"{location}: invalid timestamp") from error
    return value


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"JSON: duplicate object key {key!r}")
        result[key] = value
    return result


def _invalid_constant(value):
    raise ImportProblem(f"JSON: nonfinite number {value} is not supported")


def load_json(raw):
    try:
        return json.loads(raw.decode("utf-8-sig"), parse_float=Decimal,
                          object_pairs_hook=_unique_pairs, parse_constant=_invalid_constant)
    except ImportProblem:
        raise
    except (ValueError, InvalidOperation, RecursionError) as error:
        raise ImportProblem(f"Input must contain one UTF-8 JSON object with representable numbers: {error}") from error


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def stable_id(prefix, value):
    return prefix + "-" + digest(encode(value))[:48]


def markdown(value):
    for char in "\\`*_{}[]<>()|#!":
        value = value.replace(char, "\\" + char)
    return value


def local_path(path):
    """Reject explicit network and device namespaces before any filesystem operation."""
    spelling = str(path).replace("\\", "/").lower()
    require(not spelling.startswith(("//", "/??/", "/device/", "/global??/")),
            "Expected a local path, not a UNC or device path")
    return Path(path)


def read_limited(path, limit):
    path = local_path(path)
    require(path.is_file(), f"Expected an existing local file: {path}")
    with path.open("rb") as stream:
        raw = stream.read(limit + 1)
    require(len(raw) <= limit, f"Input exceeds the {limit}-byte limit: {path}")
    return raw


def _git_status(repository, *args):
    return subprocess.run(["git", "--literal-pathspecs", "-C", str(repository), *args],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False).returncode


def output_paths(names, output_dir, *, repository=ROOT):
    """Resolve and check every destination before writing the first file."""
    repository, output_dir = local_path(repository), local_path(output_dir)
    repository, output_dir = repository.resolve(), output_dir.resolve()
    paths, folded_names = {}, set()
    for name in names:
        require(isinstance(name, str) and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", name)
                and not name.endswith(".") and not PureWindowsPath(name).is_reserved(),
                f"Expected a plain output filename: {name!r}")
        require(name.casefold() not in folded_names, f"Conflicting case-insensitive output filename: {name}")
        folded_names.add(name.casefold())
        path = output_dir / name
        require(not path.is_symlink(), f"Output is a symlink: {path}")
        if path.is_relative_to(repository):
            # check-ignore does not support literal pathspec magic; it accepts a pathname.
            ignored = subprocess.run(["git", "-C", str(repository), "check-ignore", "--no-index", "--quiet", "--", str(path)],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False).returncode
            require(ignored in (0, 1), "Could not establish Git ignore status for the output path")
            require(ignored == 0, f"Output must be outside the repository or Git-ignored: {path}")
            require(_git_status(repository, "ls-files", "--error-unmatch", "--", str(path)) == 1,
                    f"Output must not be tracked by Git: {path}")
        paths[name] = path
    return paths


def _matches(path, payload):
    return (not path.is_symlink() and path.is_file() and path.stat().st_size == len(payload)
            and read_limited(path, len(payload)) == payload)


def write_outputs(outputs, output_dir, *, repository=ROOT, input_path=None):
    """Refuse tracked destinations, changed files and symlink output files."""
    if input_path is not None:
        input_path = local_path(input_path).resolve()
    paths = output_paths(outputs, output_dir, repository=repository)
    for name, path in paths.items():
        require(input_path is None or path != input_path, "Output would replace the input file")
        if path.exists():
            require(_matches(path, outputs[name]),
                    f"Output differs from existing file; choose a new output directory: {path}")
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    created = 0
    for name, path in paths.items():
        try:
            with path.open("xb") as output:
                output.write(outputs[name])
            created += 1
        except FileExistsError:
            require(_matches(path, outputs[name]),
                    f"Output changed during generation; choose a new output directory: {path}")
    return created
