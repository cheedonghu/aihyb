"""Offline sync regressions, including real multi-process publication and lock recovery."""
from concurrent.futures import ThreadPoolExecutor
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from external_sources import cli, publications, snapshots
from external_sources.common import ImportProblem, ROOT, digest, encode
from external_sources.modeldial import AXES
from external_sources.sources import sources_for
from external_sources.sync import sync
from test_external_sources import fictional_source
import test_modeldial_import as dial
import test_open_models as epoch


FIRST = '2026-03-01T13:00:00+01:00'
SECOND = '2026-03-01T12:00:01Z'
THIRD = '2026-03-01T12:00:02Z'


class SyncTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='aihyb-sync-test-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.data = self.root / 'boards'
        self.schema = json.loads((ROOT / 'contracts/content-package-v1.schema.json').read_bytes())
        self.addCleanup(patch.stopall)
        patch('socket.socket', side_effect=AssertionError('Unexpected network access')).start()

    def snapshot(self, name, data=None, at=FIRST):
        directory = self.root / name
        with patch.object(snapshots, '_download', return_value=dial.raw(data or dial.overview())), \
                patch.object(snapshots, '_utc_now', return_value=at):
            snapshots.fetch('modeldial', directory)
        return directory

    def replay(self, source):
        return sync('modeldial', self.data, source)

    def check_version(self, path, sequence):
        raw = path.read_bytes()
        self.assertEqual(path.name, f'{sequence:020d}-{digest(raw)}.json')
        bundle = json.loads(raw)
        self.assertEqual(set(bundle), {'version', 'adapter', 'retrieved_at', 'fingerprint', 'boards'})
        self.assertEqual(bundle['version'], 1)
        self.assertRegex(bundle['fingerprint'], r'^[a-f0-9]{64}$')
        for board_id, board in bundle['boards'].items():
            self.assertEqual(board_id, board['content_id'])
            dial.validate_schema(board, self.schema)
        return bundle

    def reports(self, adapter):
        return [json.loads(path.read_bytes()) for path in (self.data / '_sync' / adapter / 'reports').glob('*.json')]

    def test_full_hardware_archives_even_when_only_download_counts_and_raw_bytes_change(self):
        adapter = 'open-models'
        def counter(source):
            raw = fictional_source(source)
            if source.name.startswith('hf-'):
                return encode(dict(json.loads(raw), downloads=3456, likes=28))
            return raw

        sources = list(sources_for(adapter, True))
        for at, response, status in [(FIRST, fictional_source, 'published'), (SECOND, counter, 'unchanged')]:
            with patch.object(snapshots, '_download', side_effect=response) as downloads, \
                    patch.object(snapshots, '_utc_now', side_effect=[at] + [THIRD] * 10):
                actual, path, report = sync(adapter, self.data)
            self.assertEqual(actual, status)
            self.assertEqual([call.args[0] for call in downloads.call_args_list], sources)
            info = json.loads(report.read_bytes())
            archive = Path(info['snapshot_dir'])
            self.assertEqual(archive.parent, self.data / '_sync' / adapter / 'snapshots')
            self.assertEqual({p.name for p in archive.iterdir()}, {s.name for s in sources} | {'snapshot.json'})
            self.assertEqual(snapshots.read_snapshot(adapter, archive)[2], at)
            self.assertEqual(json.loads((archive / 'snapshot.json').read_bytes())['retrieved_at'], THIRD)
        bundle = self.check_version(path, 1)
        self.assertEqual(set(bundle['boards']), {'auto-open-models'})
        self.assertEqual(bundle['retrieved_at'], FIRST)
        reports = self.reports(adapter)
        self.assertEqual(len({r['snapshot_dir'] for r in reports}), 2)
        self.assertEqual(len({r['fingerprint'] for r in reports}), 1)
        self.assertEqual(len(list(path.parent.iterdir())), 1)
        board = bundle['boards']['auto-open-models']
        self.assertTrue(board['data']['entries'][0]['hardware'])
        self.assertEqual(board['sources'][0]['retrieved_at'], FIRST)
        self.assertEqual(board['sources'][1]['retrieved_at'], THIRD)

    def test_source_overlap_and_exact_decimal_semantics_include_unchanged_observations(self):
        def collect(primary, hardware, score, memory=b'7100'):
            def response(source):
                if source.name == 'benchmark_data.zip':
                    return epoch.source([(epoch.MODEL, score, 'Open weights')])
                if source.name == 'qwen-benchmark.html':
                    return epoch.sample_page().replace(b'7100', memory)
                return fictional_source(source)
            with patch.object(snapshots, '_download', side_effect=response), \
                    patch.object(snapshots, '_utc_now', side_effect=[primary] + [hardware] * 10):
                return sync('open-models', self.data)

        _, first, _ = collect(FIRST, SECOND, '1000000000000000000000000110.00')
        self.assertEqual(collect(SECOND, THIRD, '1000000000000000000000000110')[0], 'unchanged')
        with self.assertRaisesRegex(ImportProblem, 'Older or conflicting'):
            collect(THIRD, SECOND, '1000000000000000000000000111', b'7000')
        # Same instant, different hardware bytes: even though the primary advanced, refuse.
        with self.assertRaisesRegex(ImportProblem, 'Older or conflicting'):
            collect(THIRD, THIRD, '1000000000000000000000000111', b'7000')
        _, changed, _ = collect(THIRD, '2026-03-01T12:00:03Z', '1000000000000000000000000111', b'7000')
        latest = self.check_version(changed, 2)
        self.assertNotEqual(self.check_version(first, 1)['fingerprint'], latest['fingerprint'])

    def test_replay_validates_payload_shape_and_records_html_attribute_errors(self):
        for index, name in enumerate(['hf-Qwen3-8B.json', 'qwen-benchmark.html']):
            snapshot = self.root / f'forged-{index}'
            with patch.object(snapshots, '_download', side_effect=fictional_source):
                snapshots.fetch('open-models', snapshot, with_hardware=True)
            target = snapshot / name
            if name.endswith('.json'):
                obj = json.loads(target.read_bytes())
                del obj['siblings']
                payload = encode(obj)
            else:
                payload = target.read_bytes().replace(b'rowspan="2"', b'rowspan')
            target.write_bytes(payload)
            manifest = json.loads((snapshot / snapshots.MANIFEST).read_bytes())
            manifest['files'][name].update(bytes=len(payload), sha256=digest(payload))
            (snapshot / snapshots.MANIFEST).write_bytes(encode(manifest))
            with self.assertRaises(ImportProblem):
                sync('open-models', self.data, snapshot)
        self.assertEqual([r['status'] for r in self.reports('open-models')], ['failed', 'failed'])

    def test_modeldial_changes_batch_date_and_scores_as_one_complete_bundle(self):
        overview = dial.overview()
        original = self.snapshot('one', overview)
        first_status, first, _ = self.replay(original)
        self.assertEqual(first_status, 'published')
        first_raw = first.read_bytes()
        initial = self.check_version(first, 1)
        self.assertEqual(set(initial['boards']), {'auto-modeldial-' + axis for axis in AXES})
        self.assertEqual(self.replay(original)[:2], ('unchanged', first))
        # The view key changes the converter's snapshot-derived content IDs, not the board semantics.
        overview.update(viewKey='e' * 64, unrendered=['extra field'])
        self.assertEqual(self.replay(self.snapshot('two', overview, SECOND))[:2], ('unchanged', first))
        overview['snapshot']['batch_id'] = 'fictional-second'
        second_status, second, _ = self.replay(self.snapshot('three', overview, THIRD))
        updated = self.check_version(second, 2)
        self.assertEqual(second_status, 'published')
        self.assertNotEqual(initial['fingerprint'], updated['fingerprint'])
        overview['snapshot']['published_at'] = SECOND
        _, third, _ = self.replay(self.snapshot('four', overview, '2026-03-02T00:00:00Z'))
        self.check_version(third, 3)
        overview['snapshot']['entries'][0]['backend_score'] = 72
        _, fourth, _ = self.replay(self.snapshot('five', overview, '2026-03-03T00:00:00Z'))
        changed = self.check_version(fourth, 4)
        self.assertEqual(changed['boards']['auto-modeldial-backend']['data']['entries'][0]['score'], '72')
        self.assertEqual(first.read_bytes(), first_raw)
        self.assertFalse((self.data / '_sync/modeldial/snapshots').exists())

    def test_stale_source_and_retrieval_fences_include_unchanged_observations(self):
        _, current, _ = self.replay(self.snapshot('one'))
        self.assertEqual(self.replay(self.snapshot('unchanged', at=THIRD))[0], 'unchanged')
        changed = dial.overview()
        changed['snapshot']['batch_id'] = 'other'
        for name, at in [('older', FIRST), ('delayed', SECOND), ('equal', THIRD)]:
            with self.subTest(name=name), self.assertRaisesRegex(ImportProblem, 'Older or conflicting'):
                self.replay(self.snapshot(name, changed, at))
        changed['snapshot']['published_at'] = '2025-07-07T00:00:00Z'
        with self.assertRaisesRegex(ImportProblem, 'Older ModelDial publication'):
            self.replay(self.snapshot('outdated-source', changed, '2027-01-01T00:00:00Z'))
        self.assertEqual(list(current.parent.iterdir()), [current])
        self.assertEqual(sum(r['status'] == 'failed' for r in self.reports('modeldial')), 4)

    def test_failures_preserve_latest_version_and_received_raw_material(self):
        _, current, _ = self.replay(self.snapshot('one'))
        raw = current.read_bytes()
        with patch.object(snapshots, '_download', return_value=b'broken response'), self.assertRaises(ImportProblem):
            sync('modeldial', self.data)
        failed = next(r for r in self.reports('modeldial') if r['status'] == 'failed')
        archive = Path(failed['snapshot_dir'])
        self.assertEqual((archive / 'overview.json').read_bytes(), b'broken response')
        self.assertFalse((archive / snapshots.MANIFEST).exists())
        snapshot = self.snapshot('damaged')
        (snapshot / 'overview.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ImportProblem, 'SHA-256 mismatch'):
            self.replay(snapshot)
        self.assertEqual(current.read_bytes(), raw)
        self.assertEqual(sum(r['status'] == 'failed' for r in self.reports('modeldial')), 2)
        self.assertEqual(list(current.parent.iterdir()), [current])

    def test_full_hardware_snapshot_required_to_replace_the_public_board(self):
        snapshot = self.root / 'primary-only'
        with patch.object(snapshots, '_download', side_effect=fictional_source):
            snapshots.fetch('open-models', snapshot)
        with self.assertRaisesRegex(ImportProblem, 'complete hardware'):
            sync('open-models', self.data, snapshot)
        self.assertFalse((self.data / 'open-models').exists())

    def test_corrupt_head_and_watermark_preserve_existing_history(self):
        _, oldest, _ = self.replay(self.snapshot('one'))
        other = dial.overview()
        other['snapshot']['batch_id'] = 'other'
        second = self.snapshot('two', other, SECOND)
        _, newest, _ = self.replay(second)
        original = newest.read_bytes()
        newest.write_bytes(b'corrupted')
        with self.assertRaisesRegex(ImportProblem, 'Published bundle SHA-256 mismatch'):
            self.replay(second)
        self.assertEqual(newest.read_bytes(), b'corrupted')
        self.check_version(oldest, 1)
        newest.write_bytes(original)
        marker = self.data / '_sync/modeldial/observations' / newest.name
        marker.unlink()
        with self.assertRaisesRegex(ImportProblem, 'Missing source watermark'):
            self.replay(second)
        marker.write_bytes(b'{')
        with self.assertRaisesRegex(ImportProblem, 'JSON'):
            self.replay(second)
        self.assertEqual(set(newest.parent.iterdir()), {oldest, newest})

    def test_data_root_protects_repository_metadata_unignored_and_tracked_ignored_paths(self):
        repo = self.root / 'repo'
        repo.mkdir()
        subprocess.run(['git', 'init', '--quiet', str(repo)], check=True)
        (repo / '.gitignore').write_text('build/\n', encoding='utf-8')
        forced = repo / 'build/tracked'
        forced.mkdir(parents=True)
        (forced / 'manual.txt').write_text('preserve', encoding='utf-8')
        subprocess.run(['git', '-C', str(repo), 'add', '--force', 'build/tracked/manual.txt'], check=True)
        with patch.object(publications, 'ROOT', repo):
            for target in [repo, repo / 'scripts', forced, repo / '.git/build']:
                with self.subTest(target=target), self.assertRaises(ImportProblem):
                    sync('modeldial', target)
                self.assertFalse((target / '_sync').exists())
            self.assertEqual(publications.data_root(repo / 'build' / 'safe'), repo / 'build' / 'safe')
        self.assertEqual((forced / 'manual.txt').read_text(), 'preserve')

    def test_failed_atomic_replace_leaves_the_previous_version_readable(self):
        _, current, _ = self.replay(self.snapshot('one'))
        changed = dial.overview()
        changed['snapshot']['batch_id'] += '-new'
        second = self.snapshot('two', changed, SECOND)
        real_replace = publications.os.replace
        def fail_publish(src, dst):
            if dst.parent == current.parent:
                self.assertTrue(src.name.endswith('.tmp'))
                self.assertFalse(dst.exists())
                self.assertEqual(digest(src.read_bytes()), dst.name[21:-5])
                raise OSError('fictional interruption')
            return real_replace(src, dst)

        with patch.object(publications.os, 'replace', side_effect=fail_publish), self.assertRaisesRegex(ImportProblem, 'interruption'):
            self.replay(second)
        self.check_version(current, 1)
        self.assertEqual(list(current.parent.iterdir()), [current])
        self.check_version(self.replay(second)[1], 2)

    @unittest.skipUnless(os.name == 'nt', 'Windows extended drive spelling')
    def test_only_unwrap_checked_drive_path_prefixed_by_resolve(self):
        prefix = '\\\\?\\'
        with patch.object(Path, 'resolve', return_value=Path(prefix + str(self.data))):
            self.assertEqual(publications.plain_path(self.data), self.data)
            with self.assertRaisesRegex(ImportProblem, 'UNC or device'):
                publications.plain_path(prefix + str(self.data))
        for redirected in [prefix + str(self.root / 'different'), prefix + r'UNC\server\share']:
            with patch.object(Path, 'resolve', return_value=Path(redirected)), self.assertRaises(ImportProblem):
                publications.plain_path(self.data)

    def test_success_report_failure_and_directory_sync_failure_describe_visible_commit(self):
        snapshot = self.snapshot('one')
        args = ['sync', 'modeldial', '--data-dir', str(self.data), '--snapshot-dir', str(snapshot)]
        error = io.StringIO()
        with patch('external_sources.sync.atomic_write', side_effect=OSError('report disk full')), \
                contextlib.redirect_stderr(error), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(cli.main(args), 0)
        self.assertIn('Sync published', error.getvalue())
        self.assertIn('report disk full', error.getvalue())
        first = next((self.data / 'modeldial').iterdir())
        self.check_version(first, 1)
        changed = dial.overview()
        changed['snapshot']['batch_id'] += '-new'
        args[-1] = str(self.snapshot('two', changed, SECOND))
        real_sync = publications._sync_directory
        def fail_after_replace(directory):
            if directory == first.parent:
                raise OSError('directory fsync failed')
            real_sync(directory)

        output = io.StringIO()
        with patch.object(publications, '_sync_directory', side_effect=fail_after_replace), contextlib.redirect_stdout(output):
            self.assertEqual(cli.main(args), 3)
        self.assertIn('published-durability-unconfirmed', output.getvalue())
        report, = self.reports('modeldial')
        self.assertEqual(report['status'], 'published-durability-unconfirmed')
        self.assertIn('directory fsync failed', report['warning'])
        self.check_version(first.parent / report['file'], 2)

    def test_cross_process_cli_and_recovery_after_lock_owner_dies(self):
        snapshot = self.snapshot('one')
        args = [sys.executable, str(ROOT / 'scripts/import_external.py'), 'sync', 'modeldial',
                '--data-dir', str(self.data), '--snapshot-dir', str(snapshot)]
        with ThreadPoolExecutor(max_workers=5) as pool:
            results = list(pool.map(lambda _: subprocess.run(args, capture_output=True, text=True, timeout=30), range(5)))
        self.assertEqual([r.returncode for r in results], [0] * 5, [r.stderr for r in results])
        self.assertEqual(sum('Sync published' in r.stdout for r in results), 1)
        self.assertEqual(sum('Sync unchanged' in r.stdout for r in results), 4)
        self.check_version(next((self.data / 'modeldial').iterdir()), 1)
        lock = self.data / '_sync/modeldial/writer.lock'
        script = ('from pathlib import Path\nfrom external_sources.publications import writer_lock\nimport os, sys\n'
                  'with writer_lock(Path(sys.argv[1])):\n    os._exit(17)')
        result = subprocess.run([sys.executable, '-c', script, str(lock)], cwd=ROOT / 'scripts', timeout=10)
        self.assertEqual(result.returncode, 17)
        result = subprocess.run(args, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Sync unchanged', result.stdout)
        self.assertEqual(len(self.reports('modeldial')), 6)


if __name__ == '__main__':
    unittest.main()
