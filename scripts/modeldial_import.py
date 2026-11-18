"""Compatibility entry point for the original offline ModelDial command and API."""
import argparse
from pathlib import Path
import sys

if __package__:
    from .external_sources import modeldial as _adapter
    from .external_sources.common import ROOT, local_path, write_outputs
    from .external_sources.modeldial import *  # Re-export the legacy converter API.
else:
    from external_sources import modeldial as _adapter
    from external_sources.common import ROOT, local_path, write_outputs
    from external_sources.modeldial import *


def load_overview(raw):
    _adapter.check_overview_size(raw, MAX_INPUT_BYTES)
    return load_json(raw)


def convert(raw, *, retrieved_at, generated_at):
    _adapter.check_overview_size(raw, MAX_INPUT_BYTES)
    return _adapter.convert(raw, retrieved_at=retrieved_at, generated_at=generated_at)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Convert a downloaded ModelDial overview into five local draft JSON packages; no network or Halo access.")
    parser.add_argument("--input", required=True, type=local_path, help="Explicit path to a downloaded overview JSON")
    parser.add_argument("--output-dir", required=True, type=local_path, help="Explicit external or Git-ignored output directory")
    parser.add_argument("--retrieved-at", required=True, help="Actual source retrieval time, RFC 3339 with time zone")
    parser.add_argument("--generated-at", required=True, help="Recorded draft generation time; reuse it for an identical replay")
    args = parser.parse_args(argv)
    try:
        require(args.input.is_file(), "--input must be an existing local JSON file")
        with args.input.open("rb") as input_file:
            raw = input_file.read(MAX_INPUT_BYTES + 1)
        outputs = convert(raw, retrieved_at=args.retrieved_at, generated_at=args.generated_at)
        count = write_outputs(outputs, args.output_dir, input_path=args.input)
    except (ImportProblem, OSError, RecursionError) as error:
        parser.exit(2, f"Import refused: {error}\n")
    print(f"Draft JSON only: {count} files created, {len(outputs) - count} unchanged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
