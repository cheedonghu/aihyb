"""Fetch fixed resources only on request; replay a complete, hash-checked snapshot."""
from __future__ import annotations

from datetime import datetime, timezone
import http.client
import io
import urllib.request
import zipfile

from .adapters import convert
from .common import (ImportProblem, digest, encode, load_json, local_path, object_at, output_paths,
                     read_limited, require, timestamp, write_outputs)
from .sources import sources_for

MANIFEST = "snapshot.json"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ImportProblem(f"Unexpected redirect (HTTP {code}) from fixed source {req.full_url}")


def _download(source):
    request = urllib.request.Request(source.url, headers={"User-Agent": "aihyb-external-sources/1.0",
                                                          "Accept-Encoding": "identity"})
    opener = urllib.request.build_opener(_NoRedirect())
    with opener.open(request, timeout=45) as response:
        require(response.status == 200, f"Expected HTTP 200, received {response.status}")
        require(response.geturl() == source.url, "Unexpected response URL")
        declared = response.headers.get("Content-Length")
        if declared is not None:
            require(declared.isascii() and declared.isdecimal() and len(declared) <= 20
                    and int(declared) <= source.limit, "Invalid or excessive Content-Length")
        raw = response.read(source.limit + 1)
        require(0 < len(raw) <= source.limit, f"Empty response or exceeds {source.limit}-byte limit")
        require(declared is None or len(raw) == int(declared), "Incomplete HTTP response")
        return raw


def _check_payload(source, raw):
    # Reject common 200-status error pages and responses for the wrong repository.
    if source.name == "benchmark_data.zip":
        try:
            with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                require(any(not info.is_dir() for info in archive.infolist()), "Empty benchmark ZIP")
        except zipfile.BadZipFile as error:
            raise ImportProblem("Invalid benchmark ZIP") from error
    elif source.name == "qwen-benchmark.html":
        page = raw.decode("utf-8-sig").lower()
        require("<html" in page and "speed benchmark" in page and "<table" in page,
                "Expected the Qwen speed benchmark HTML tables")
    else:
        data = object_at(load_json(raw), source.name)
        if source.name == "overview.json":
            snapshot = object_at(data.get("snapshot"), "snapshot")
            require(isinstance(snapshot.get("entries"), list) and snapshot["entries"],
                    "Expected a ModelDial overview with snapshot entries")
        else:
            repository = source.name.removeprefix("hf-").removesuffix(".json")
            require(data.get("id") == f"Qwen/{repository}", f"Expected metadata for Qwen/{repository}")
            files = data.get("siblings")
            require(isinstance(files, list) and files, "Missing HF file metadata")
            if repository.endswith("-GGUF"):
                require(any(isinstance(f, dict) and str(f.get("rfilename")).lower().endswith(".gguf")
                            and type(f.get("size")) is int and f["size"] > 0 for f in files),
                        "Missing GGUF filenames and sizes")


def _utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def fetch(adapter, destination, *, with_hardware=False):
    sources = sources_for(adapter, with_hardware)
    output_paths([s.name for s in sources] + [MANIFEST], destination)
    payloads, records = {}, {}
    for source in sources:
        try:
            raw = _download(source)
            _check_payload(source, raw)
        except (ValueError, OSError, http.client.HTTPException, RecursionError) as error:
            raise ImportProblem(f"Could not collect {source.url}: {error}") from error
        payloads[source.name] = raw
        records[source.name] = {"url": source.url, "retrieved_at": _utc_now(),
                                "bytes": len(raw), "sha256": digest(raw)}
    primary_name = sources[0].name
    source_times = {name: record["retrieved_at"] for name, record in records.items()}
    completed_at = _utc_now()
    convert(adapter, payloads[primary_name], retrieved_at=source_times[primary_name], generated_at=completed_at,
            supplements={s.name: payloads[s.name] for s in sources[1:]}, source_times=source_times)
    # The manifest is the completion marker. Never create it before the last payload exists.
    payloads[MANIFEST] = encode({"version": 1, "adapter": adapter, "with_hardware": with_hardware,
                                 "retrieved_at": completed_at, "files": records})
    return write_outputs(payloads, destination)


def read_snapshot(adapter, directory):
    directory = local_path(directory)
    manifest_path = directory / MANIFEST
    require(not manifest_path.is_symlink(), f"Snapshot manifest is a symlink: {manifest_path}")
    manifest = object_at(load_json(read_limited(manifest_path, 64 * 1024)), MANIFEST)
    require(type(manifest.get("version")) is int and manifest["version"] == 1, "Unsupported snapshot version")
    require(manifest.get("adapter") == adapter, "Snapshot belongs to another adapter")
    with_hardware = manifest.get("with_hardware")
    require(type(with_hardware) is bool, "Invalid snapshot hardware setting")
    sources = sources_for(adapter, with_hardware)
    timestamp(manifest.get("retrieved_at"), "snapshot.retrieved_at")
    records = object_at(manifest.get("files"), "snapshot.files")
    require(set(records) == {s.name for s in sources}, "Incomplete or unexpected snapshot file list")
    payloads, source_times = {}, {}
    for source in sources:
        record = object_at(records[source.name], source.name)
        require(record.get("url") == source.url, f"Unexpected source URL for {source.name}")
        source_times[source.name] = timestamp(record.get("retrieved_at"), f"{source.name}.retrieved_at")
        path = directory / source.name
        require(not path.is_symlink(), f"Snapshot file is a symlink: {path}")
        raw = read_limited(path, source.limit)
        require(type(record.get("bytes")) is int and len(raw) == record["bytes"]
                and digest(raw) == record.get("sha256"), f"Snapshot size or SHA-256 mismatch: {source.name}")
        payloads[source.name] = raw
    return payloads.pop(sources[0].name), payloads, source_times[sources[0].name], source_times
