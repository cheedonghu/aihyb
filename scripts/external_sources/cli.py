"""Explicit public fetch and local-only draft conversion commands."""
from __future__ import annotations

import argparse

from .adapters import convert
from .common import ImportProblem, local_path, read_limited, require, timestamp, write_outputs
from .snapshots import fetch, read_snapshot
from .sources import HARDWARE, PRIMARY


def _parser():
    parser = argparse.ArgumentParser(description="Collect fixed public data or convert local sources to drafts.")
    commands = parser.add_subparsers(dest="command", required=True)
    collect = commands.add_parser("fetch", help="Fetch an entire snapshot from fixed, public HTTPS sources")
    collect.add_argument("adapter", choices=PRIMARY)
    collect.add_argument("--snapshot-dir", required=True, type=local_path, help="External or Git-ignored snapshot directory")
    collect.add_argument("--with-hardware", action="store_true", help="Include all fixed official Qwen HTML and HF records (open-models)")
    convert = commands.add_parser("convert", help="Convert a local snapshot or explicitly supplied files offline")
    convert.add_argument("adapter", choices=PRIMARY)
    inputs = convert.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--snapshot-dir", type=local_path, help="Replay local snapshot.json with hash verification")
    inputs.add_argument("--input", type=local_path, help="Local ModelDial overview JSON or Epoch benchmark ZIP")
    convert.add_argument("--supplement", metavar="NAME=PATH", action="append", default=[],
                         help="Local Qwen speed HTML / HF JSON with its canonical filename as NAME (repeatable, open-models --input)")
    convert.add_argument("--retrieved-at", help="RFC 3339 source retrieval time (required with --input)")
    convert.add_argument("--generated-at", required=True, help="RFC 3339 draft generation time; reuse on replay")
    convert.add_argument("--output-dir", required=True, type=local_path, help="External or Git-ignored draft directory")
    return parser


def _inputs(args):
    if args.snapshot_dir is not None:
        require(args.retrieved_at is None and not args.supplement,
                "--snapshot-dir takes retrieval time and supplements from the manifest")
        return read_snapshot(args.adapter, args.snapshot_dir)
    retrieved_at = timestamp(args.retrieved_at, "--retrieved-at (required with --input)")
    require(not args.supplement or args.adapter == "open-models", "--supplement requires open-models --input")
    raw = read_limited(args.input, PRIMARY[args.adapter].limit)
    limits = {source.name: source.limit for source in HARDWARE}
    supplements = {}
    for item in args.supplement:
        name, separator, path = item.partition("=")
        require(separator and path and name in limits and name not in supplements,
                f"Unknown, duplicate or malformed --supplement (expected canonical NAME=PATH): {item}")
        supplements[name] = read_limited(path, limits[name])
    return raw, supplements, retrieved_at, None


def _convert(args):
    generated_at = timestamp(args.generated_at, "--generated-at")
    raw, supplements, retrieved_at, source_times = _inputs(args)
    outputs = convert(args.adapter, raw, retrieved_at=retrieved_at, generated_at=generated_at,
                      supplements=supplements, source_times=source_times)
    count = write_outputs(outputs, args.output_dir, input_path=args.input)
    print(f"Draft JSON only: {count} files created, {len(outputs) - count} unchanged.")


def main(argv=None):
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "fetch":
            count = fetch(args.adapter, args.snapshot_dir, with_hardware=args.with_hardware)
            print(f"Source snapshot complete: {count} files created in {args.snapshot_dir}.")
        else:
            _convert(args)
    except (ImportProblem, OSError, RecursionError) as error:
        parser.exit(2, f"Import refused: {error}\n")
    return 0
