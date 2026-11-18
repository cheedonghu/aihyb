"""Synchronize one complete adapter snapshot to its stable public board identities."""
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
import sys
from uuid import uuid4

from .adapters import convert
from .common import ImportProblem, digest, encode, load_json, require, stable_id
from .modeldial import AXES
from .publications import VisibleWriteError, atomic_write, data_root, plain_path, publish
from .snapshots import fetch, read_snapshot
from .sources import HARDWARE, PRIMARY


def _fingerprint(adapter, boards):
    semantic = deepcopy(boards)
    for board in semantic.values():
        del board['submission_id']
        del board['provenance']['generated_at'], board['provenance']['input_sha256']
        for source in board['sources']:
            del source['retrieved_at']
        # Epoch only supplies a retrieval date. ModelDial's as_of is an upstream publication date.
        if adapter == 'open-models':
            del board['data']['as_of']
            for entry in board['data']['entries']:
                # Decimal.normalize() can round under the active decimal context.
                score = format(Decimal(entry['score']), 'f')
                entry['score'] = score.rstrip('0').rstrip('.') if '.' in score else score
    return digest(encode(semantic))


def _bundle(adapter, snapshot):
    raw, supplements, retrieved, times = read_snapshot(adapter, snapshot, check_payload=True)
    if adapter == 'open-models':
        require(set(supplements) == {s.name for s in HARDWARE}, 'Sync requires a complete hardware snapshot')
    generated = datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')
    outputs = convert(adapter, raw, retrieved_at=retrieved, generated_at=generated,
                      supplements=supplements, source_times=times)
    names = [f'modeldial-{axis}' for axis in AXES] if adapter == 'modeldial' else ['open-models']
    boards = {}
    for name in names:
        board = load_json(outputs[f'{name}.draft.json'])
        board_id = 'auto-' + name
        board['content_id'] = board_id
        del board['submission_id']
        board['submission_id'] = stable_id('auto-submit', board)
        boards[board_id] = board
    inputs = {PRIMARY[adapter].name: raw, **supplements}
    observations = {name: {'retrieved_at': times[name], 'sha256': digest(payload)} for name, payload in inputs.items()}
    bundle = {'version': 1, 'adapter': adapter, 'retrieved_at': retrieved,
              'fingerprint': _fingerprint(adapter, boards), 'boards': boards}
    return bundle, observations


def sync(adapter, directory, snapshot_dir=None):
    require(adapter in PRIMARY, 'Unknown sync adapter')
    root = data_root(directory)
    work = plain_path(root / '_sync' / adapter)
    reports = plain_path(work / 'reports')
    run = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid4().hex
    report_path = reports / (run + '.json')
    if snapshot_dir is not None:
        snapshot = plain_path(snapshot_dir)
        require(not root.is_relative_to(snapshot) and not work.is_relative_to(snapshot)
                and not (root / adapter).is_relative_to(snapshot), 'Snapshot and sync output directories overlap')
    else:
        snapshot = plain_path(work / 'snapshots' / run)
    reports.mkdir(parents=True, exist_ok=True)
    record = {'adapter': adapter, 'mode': 'fetch' if snapshot_dir is None else 'replay', 'snapshot_dir': str(snapshot)}
    try:
        directory = plain_path(root / adapter)
        if snapshot_dir is None:
            fetch(adapter, snapshot, with_hardware=adapter == 'open-models', archive_attempt=True)
        bundle, observations = _bundle(adapter, snapshot)
        record.update(retrieved_at=bundle['retrieved_at'], fingerprint=bundle['fingerprint'])
        try:
            status, destination = publish(directory, work, bundle, observations)
        except VisibleWriteError as error:
            if error.path.parent != directory:
                raise
            status, destination = 'published-durability-unconfirmed', error.path
            record['warning'] = str(error)
    except (ImportProblem, OSError, RecursionError) as error:
        try:
            atomic_write(report_path, encode(dict(record, status='failed', error=str(error))))
        except OSError as report_error:
            raise ImportProblem(f'{error} (also failed to save {report_path}: {report_error})') from error
        raise ImportProblem(f'{error} (report: {report_path})') from error
    try:
        atomic_write(report_path, encode(dict(record, status=status, file=destination.name)))
    except OSError as error:
        print(f'Sync {status} at {destination}, but could not save report {report_path}: {error}', file=sys.stderr)
        report_path = None
    return status, destination, report_path
