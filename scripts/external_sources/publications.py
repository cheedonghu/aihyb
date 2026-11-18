"""Versioned local publications and a short, process-owned writer lock per adapter."""
from contextlib import contextmanager
from datetime import datetime
import os
from pathlib import Path
import re
import stat
import subprocess
from uuid import uuid4

from .common import (ImportProblem, ROOT, digest, encode, load_json, local_path, object_at,
                     read_limited, require, timestamp)

VERSION_FILE = re.compile(r'([0-9]{20})-([a-f0-9]{64})\.json')
MAX_BUNDLE = 1024 * 1024


def plain_path(path):
    """Refuse redirected destinations, including Windows junctions and dangling links."""
    path = local_path(path).absolute()
    for part in (*reversed(path.parents), path):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(info.st_mode) and not (
            getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT),
            f'Expected a plain local path without links: {part}')
    resolved = path.resolve()
    # Win32 realpath can return the extended spelling when another process creates
    # the directory during resolution. Only unwrap an exact match for our checked DOS path.
    if os.name == 'nt' and str(resolved).startswith('\\\\?\\'):
        drive_path = Path(str(resolved)[4:])
        if re.fullmatch('[A-Za-z]:', drive_path.drive) and drive_path == Path(os.path.normpath(path)):
            resolved = drive_path
    return local_path(resolved)


def data_root(path):
    root = plain_path(path)
    require(root.parent != root and not ROOT.is_relative_to(root), 'Choose a dedicated local data directory')
    require('.git' not in {part.casefold() for part in root.parts}, 'Git metadata cannot be a data directory')
    if root.is_relative_to(ROOT):
        # An ignored directory cannot re-include descendants. Still refuse force-added files.
        git = ['git', '-C', str(ROOT)]
        options = dict(stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        ignored = subprocess.run([*git, 'check-ignore', '--quiet', '--no-index', '--', root.as_posix() + '/'], **options)
        tracked = subprocess.run([*git, '--literal-pathspecs', 'ls-files', '--error-unmatch', '--', str(root)], **options)
        require(ignored.returncode == 0 and tracked.returncode == 1,
                f'Data directory must be outside the repository or entirely Git-ignored and untracked: {root}')
    return root


@contextmanager
def writer_lock(path):
    # Keep the inode permanently. Unlinking a lock while a waiter has it open splits ownership.
    with plain_path(path).open('a+b') as stream:
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX)
        # Closing releases the kernel lock on both normal exit and process death.
        yield


class VisibleWriteError(OSError):
    def __init__(self, path, cause):
        super().__init__(f'{path} is visible but directory durability is unconfirmed: {cause}')
        self.path = path


def _sync_directory(path):
    if os.name != 'nt':
        directory = os.open(path, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)


def atomic_write(path, raw):
    temp = path.with_name(f'.{uuid4().hex}.tmp')
    stream = temp.open('xb')
    try:
        with stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        try:
            _sync_directory(path.parent)
        except OSError as error:
            raise VisibleWriteError(path, error) from error
    finally:
        temp.unlink(missing_ok=True)


def _latest(directory):
    versions = {}
    for path in directory.iterdir():
        match = VERSION_FILE.fullmatch(path.name)
        if match:
            sequence = int(match[1])
            require(sequence not in versions, f'Ambiguous published sequence: {sequence}')
            versions[sequence] = path
    return versions[max(versions)] if versions else None


def _instant(value):
    return datetime.fromisoformat(timestamp(value, 'source time').replace('Z', '+00:00'))


def _head(path, adapter, board_ids):
    raw = read_limited(plain_path(path), MAX_BUNDLE)
    require(digest(raw) == VERSION_FILE.fullmatch(path.name)[2], f'Published bundle SHA-256 mismatch: {path}')
    item = object_at(load_json(raw), 'published bundle')
    require(type(item.get('version')) is int and item['version'] == 1 and item.get('adapter') == adapter
            and isinstance(item.get('fingerprint'), str) and re.fullmatch(r'[a-f0-9]{64}', item['fingerprint'])
            and set(object_at(item.get('boards'), 'published boards')) == board_ids, f'Invalid published bundle: {path}')
    for board_id, board in item['boards'].items():
        require(object_at(board, board_id).get('content_id') == board_id, 'Invalid published content identity')
    return item


def _check_observations(marker, incoming, unchanged):
    require(marker.is_file(), f'Missing source watermark; restore the matching data-directory backup: {marker}')
    observed = object_at(load_json(read_limited(marker, 16 * 1024)), 'source watermark')
    require(set(observed) == set(incoming), 'Incomplete source watermark')
    merged = observed.copy()
    for name, record in incoming.items():
        old = object_at(observed[name], name)
        require(isinstance(old.get('sha256'), str) and re.fullmatch(r'[a-f0-9]{64}', old['sha256']),
                f'Invalid source watermark for {name}')
        before, after = _instant(old.get('retrieved_at')), _instant(record['retrieved_at'])
        if after > before:
            merged[name] = record
        elif not unchanged:
            require(after == before and record['sha256'] == old['sha256'],
                    f'Older or conflicting source retrieval for {name} would replace a newer observation')
    if unchanged and merged != observed:
        # Even a no-op advances each participating source's latest known retrieval independently.
        atomic_write(marker, encode(merged))
    elif not unchanged:
        require(merged != observed, 'Conflicting board from identical source observation times')


def publish(directory, work, bundle, observations):
    """Compare and commit under one lock; downloading and conversion hold no lock."""
    with writer_lock(work / 'writer.lock'):
        directory.mkdir(parents=True, exist_ok=True)
        markers = plain_path(work / 'observations')
        markers.mkdir(exist_ok=True)
        latest = _latest(directory)
        if latest is not None:
            current = _head(latest, bundle['adapter'], set(bundle['boards']))
            unchanged = current['fingerprint'] == bundle['fingerprint']
            _check_observations(plain_path(markers / latest.name), observations, unchanged)
            if unchanged:
                return 'unchanged', latest
            if bundle['adapter'] == 'modeldial':
                board_id = 'auto-modeldial-backend'
                old_date = object_at(current['boards'][board_id].get('data'), board_id).get('as_of')
                require(_instant(bundle['boards'][board_id]['data']['as_of']) >= _instant(old_date),
                        'Older ModelDial publication date would replace newer source data')
        sequence = int(latest.name[:20]) + 1 if latest else 1
        require(sequence < 10**20, 'Publication sequence exhausted')
        raw = encode(bundle)
        require(len(raw) <= MAX_BUNDLE, 'Publication exceeds bundle size limit')
        destination = directory / f'{sequence:020d}-{digest(raw)}.json'
        # Prepare the state before making its exact bundle visible. A crash leaves either
        # an unreferenced marker or a complete new head with its own source watermarks.
        atomic_write(plain_path(markers / destination.name), encode(observations))
        atomic_write(destination, raw)
        return 'published', destination
