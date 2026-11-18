"""Offline tests for fetch boundaries, snapshot integrity and both CLI entry points."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import zipfile

from external_sources import cli, snapshots
from external_sources.common import ImportProblem, ROOT, digest, encode, read_limited, timestamp, write_outputs
from external_sources.sources import HARDWARE, PRIMARY, sources_for
import test_modeldial_import as fictional
import test_open_models as epoch

# Captured from the pre-refactor legacy converter on the fictional overview fixture.
LEGACY = {
    "modeldial-backend.draft.json": "c2aa2c36bcc83ca9a24fa8e630287a2f13c1a2cbdcc6c4531360f74de926bae7",
    "modeldial-frontend.draft.json": "5431ac34b7d32356f240b9c94903c0025e7bdebb4f80b7c8845775572854bf8a",
    "modeldial-knowledge.draft.json": "5a39f1ff3724d8216b1f6215182dba996a799f1e92e5c71fa828064cd3bc1bce",
    "modeldial-aesthetic.draft.json": "0ada11ac8dcbe75c15b9b79580b3d4661344fb00605dc6bece8963de5d9eecba",
    "modeldial-maintainability.draft.json": "d1b9eeb628d1ee703ce5e50c0cb1db40d8a6b0e4784e893313614b5cdd75faee",
    "modeldial-import.metadata.json": "5d2d20d28040e1bf9ae3dabf5198b6e80abe82747313a2ecb6f8648aa8d777b8",
}


def fictional_source(source):
    if source.name == "overview.json":
        return fictional.raw(fictional.overview())
    if source.name == "benchmark_data.zip":
        return epoch.source([(epoch.MODEL, "110", "Open weights")])
    if source.name == "qwen-benchmark.html":
        return epoch.sample_page()
    repo = source.name[3:-5]
    model = repo.removesuffix("-GGUF")
    return epoch.meta(repo, cardData={"license": "fictional"}, siblings=[
        {"rfilename": model + "-Q4_K_M.gguf", "size": 1234}])


class SourceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="external-sources-fictional-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.snapshot = self.root / "snapshot"
        self.drafts = self.root / "drafts"
        self.addCleanup(patch.stopall)
        patch("socket.socket", side_effect=AssertionError("Offline test attempted network access")).start()
        patch.object(snapshots, "_utc_now", return_value=fictional.RECORDED).start()
        patch("sys.stdout", new_callable=io.StringIO).start()

    def collect(self, adapter="modeldial", hardware=False):
        with patch.object(snapshots, "_download", side_effect=fictional_source) as download:
            snapshots.fetch(adapter, self.snapshot, with_hardware=hardware)
        return [call.args[0] for call in download.call_args_list]

    def assert_cli_error(self, args, expected):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as error:
            cli.main(args)
        self.assertEqual(error.exception.code, 2)
        self.assertIn(expected, stderr.getvalue())

    def test_two_entry_points_replay_legacy_bytes(self):
        self.collect()
        args = ["convert", "modeldial", "--snapshot-dir", str(self.snapshot),
                "--output-dir", str(self.drafts), "--generated-at", fictional.RECORDED]
        for command, cwd in ((["scripts/import_external.py"], ROOT), (["-m", "external_sources"], ROOT / "scripts")):
            result = subprocess.run([sys.executable, *command, *args], cwd=cwd, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual({p.name: digest(p.read_bytes()) for p in self.drafts.iterdir()}, LEGACY)
        self.assertIn("6 unchanged", result.stdout)
        raw_args = ["convert", "modeldial", "--input", str(self.snapshot / "overview.json"),
                    "--retrieved-at", fictional.RECORDED, "--generated-at", fictional.RECORDED,
                    "--output-dir", str(self.drafts)]
        self.assertEqual(cli.main(raw_args), 0)

    def test_explicit_fetch_only_and_unignored_paths_rejected_first(self):
        with patch.object(snapshots, "_download", side_effect=AssertionError("Unexpected fetch")):
            self.assert_cli_error([], "required")
            self.assert_cli_error(["fetch", "modeldial", "--with-hardware", "--snapshot-dir", str(self.snapshot)],
                                  "requires open-models")
            self.assert_cli_error(["fetch", "modeldial", "--snapshot-dir", str(ROOT / "scripts")], "Git-ignored")
        self.assertFalse(self.snapshot.exists())

    def test_open_models_without_hardware_fetches_one_source(self):
        self.assertEqual(self.collect("open-models"), [PRIMARY["open-models"]])
        raw, supplements, recorded, times = snapshots.read_snapshot("open-models", self.snapshot)
        self.assertEqual(raw, (self.snapshot / "benchmark_data.zip").read_bytes())
        self.assertEqual(times, {"benchmark_data.zip": fictional.RECORDED})
        self.assertEqual((supplements, recorded), ({}, fictional.RECORDED))

    def test_complete_hardware_snapshot_has_every_url_hash_size_and_timestamp(self):
        sources = self.collect("open-models", True)
        self.assertEqual(sources, list(sources_for("open-models", True)))
        self.assertEqual(len(sources), 10)
        manifest = json.loads((self.snapshot / snapshots.MANIFEST).read_bytes())
        for source in sources:
            payload = (self.snapshot / source.name).read_bytes()
            self.assertEqual(manifest["files"][source.name], {"url": source.url, "retrieved_at": fictional.RECORDED,
                             "bytes": len(payload), "sha256": digest(payload)})
        self.assertEqual(len(snapshots.read_snapshot("open-models", self.snapshot)[1]), 9)

    def test_manifest_preserves_each_retrieval_even_when_collection_ends_later(self):
        stamps = [f"2026-01-02T03:04:{second:02}Z" for second in range(11)]
        with patch.object(snapshots, "_utc_now", side_effect=stamps):
            sources = self.collect("open-models", True)
        _, _, primary, times = snapshots.read_snapshot("open-models", self.snapshot)
        self.assertEqual(primary, stamps[0])
        self.assertEqual(times, {s.name: stamps[i] for i, s in enumerate(sources)})
        self.assertEqual(json.loads((self.snapshot / snapshots.MANIFEST).read_bytes())["retrieved_at"], stamps[-1])

    def test_requested_hardware_is_checked_even_for_models_outside_the_ranking(self):
        for name, damaged in [('hf-Qwen3-14B.json', b'{"id":"Qwen/Qwen3-14B","siblings":[{}]}'),
                              ('qwen-benchmark.html', b'<html>Speed Benchmark<table></table></html>')]:
            def response(source):
                if source.name == 'benchmark_data.zip':
                    return epoch.source([('Unrelated model', '100', 'Open weights')])
                return damaged if source.name == name else fictional_source(source)
            args = ['fetch', 'open-models', '--with-hardware', '--snapshot-dir', str(self.snapshot)]
            with self.subTest(source=name), patch.object(snapshots, '_download', side_effect=response):
                self.assert_cli_error(args, 'Import refused')
                self.assertFalse(self.snapshot.exists())

    def test_fetch_rejects_structurally_readable_but_unconvertible_sources(self):
        broken_overview = fictional.overview()
        broken_overview["qualityScores"]["policy_id"] = "changed-policy"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr("fictional.csv", "wrong,headers\n")
        cases = [("modeldial", False, "overview.json", fictional.raw(broken_overview)),
                 ("open-models", False, "benchmark_data.zip", archive.getvalue()),
                 ("open-models", True, "qwen-benchmark.html", epoch.sample_page().replace(b"Memory (MB)", b"Memory (GB)"))]
        for adapter, hardware, bad_name, bad_raw in cases:
            def damaged(source):
                return bad_raw if source.name == bad_name else fictional_source(source)
            with self.subTest(source=bad_name), patch.object(snapshots, "_download", side_effect=damaged):
                with self.assertRaises(ImportProblem):
                    snapshots.fetch(adapter, self.snapshot, with_hardware=hardware)
                self.assertFalse(self.snapshot.exists())

    def test_failed_last_hardware_source_never_publishes_partial_collection(self):
        def fail_last(source):
            if source == HARDWARE[-1]:
                raise OSError("fictional timeout")
            return fictional_source(source)

        with patch.object(snapshots, "_download", side_effect=fail_last):
            self.assert_cli_error(["fetch", "open-models", "--with-hardware", "--snapshot-dir", str(self.snapshot)],
                                  HARDWARE[-1].url)
        self.assertFalse(self.snapshot.exists())

    def test_fixed_source_payload_checks(self):
        for source, raw in [(PRIMARY["modeldial"], b"{}"), (PRIMARY["open-models"], b"not a ZIP"),
                            (HARDWARE[0], b"<html>error</html>"), (HARDWARE[1], b'{"id":"other"}'),
                            (HARDWARE[-1], encode({"id": "Qwen/Qwen3-30B-A3B-GGUF", "siblings": [{}]}))]:
            with self.subTest(source=source.name), self.assertRaises(ImportProblem):
                snapshots._check_payload(source, raw)

    def test_fetch_conflict_never_overwrites_or_marks_snapshot_complete(self):
        self.snapshot.mkdir()
        (self.snapshot / HARDWARE[-1].name).write_bytes(b"local changes")
        with self.assertRaisesRegex(ImportProblem, "differs from existing"):
            self.collect("open-models", True)
        self.assertEqual([(p.name, p.read_bytes()) for p in self.snapshot.iterdir()], [(HARDWARE[-1].name, b"local changes")])

    def test_tamper_missing_file_extra_source_and_url_changes_fail_closed(self):
        self.collect()
        path = self.snapshot / snapshots.MANIFEST
        original = path.read_bytes()
        cases = ((lambda m: m["files"].clear(), "Incomplete"),
                 (lambda m: m["files"].update({"../escape": {}}), "unexpected"),
                 (lambda m: m["files"]["overview.json"].update(url="https://example.invalid"), "source URL"),
                 (lambda m: m["files"]["overview.json"].update(bytes=1), "mismatch"),
                 (lambda m: m["files"]["overview.json"].update(retrieved_at="yesterday"), "timestamp"),
                 (lambda m: m.update(adapter="open-models"), "another adapter"),
                 (lambda m: m.update(with_hardware=None), "hardware setting"))
        args = ["convert", "modeldial", "--snapshot-dir", str(self.snapshot), "--generated-at", fictional.RECORDED,
                "--output-dir", str(self.drafts)]
        for mutate, expected in cases:
            manifest = json.loads(original)
            mutate(manifest)
            path.write_bytes(encode(manifest))
            self.assert_cli_error(args, expected)
        path.write_bytes(original)
        source = self.snapshot / "overview.json"
        source.write_bytes(b"!" * source.stat().st_size)
        self.assert_cli_error(args, "SHA-256 mismatch")
        source.unlink()
        self.assert_cli_error(args, "existing local file")
        self.assertFalse(self.drafts.exists())

    def test_convert_forwards_snapshot_or_explicit_raw_bytes_without_requesting_network(self):
        self.collect("open-models", True)
        raw, supplements, retrieved, times = snapshots.read_snapshot("open-models", self.snapshot)
        fake = types.ModuleType("external_sources.open_models")
        fake.convert = Mock(return_value={"fictional.draft.json": b"{}"})
        args = ["convert", "open-models", "--generated-at", fictional.RECORDED, "--output-dir", str(self.drafts)]
        explicit = ["--input", str(self.snapshot / "benchmark_data.zip"), "--retrieved-at", retrieved]
        for name in supplements:
            explicit.extend(["--supplement", f"{name}={self.snapshot / name}"])
        with patch.dict(sys.modules, {fake.__name__: fake}):
            for local, expected_times in ((["--snapshot-dir", str(self.snapshot)], times), (explicit, None)):
                self.assertEqual(cli.main([*args, *local]), 0)
                fake.convert.assert_called_with(raw, retrieved_at=retrieved, generated_at=fictional.RECORDED,
                                                supplements=supplements, source_times=expected_times)
        self.assert_cli_error([*args, *explicit, "--supplement", "qwen-benchmark.html=other"], "duplicate")
        self.assert_cli_error([*args, "--snapshot-dir", str(self.snapshot), "--retrieved-at", retrieved],
                              "from the manifest")

    def test_output_names_cannot_escape_or_use_platform_aliases(self):
        for name in ("../escape.json", "sub/file.json", "sub\\file.json", "C:escape.json", "NUL", "a:stream", "foo."):
            with self.subTest(name=name), self.assertRaises(ImportProblem):
                write_outputs({name: b"fictional"}, self.drafts)
        self.assertFalse(self.drafts.exists())

    def test_corrupt_or_oversized_required_eci_member_cannot_complete_a_snapshot(self):
        raw = epoch.source([(epoch.MODEL, "110", "Open weights")])
        self.assertIn(b"Open weights", raw)
        corrupt = raw.replace(b"Open weights", b"Bad  weights", 1)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr(epoch.ECI_TABLE, b"x" * (2 * 1024 * 1024 + 1))
        for data, expected in ((corrupt, "CRC"), (archive.getvalue(), "2 MiB")):
            with self.subTest(expected=expected), patch.object(snapshots, "_download", return_value=data):
                self.assert_cli_error(["fetch", "open-models", "--snapshot-dir", str(self.snapshot)], expected)
                self.assertFalse(self.snapshot.exists())

    def test_rejects_invalid_rfc3339_offsets_before_datetime_normalization(self):
        for offset in ("+00:60", "-01:75", "+24:00", "+1:00", "+00:5"):
            with self.subTest(offset=offset), self.assertRaises(ImportProblem):
                timestamp("2026-01-02T00:00:00" + offset, "time")
        valid = "2026-01-02T01:02:03.123456-03:30"
        self.assertEqual(timestamp(valid, "time"), valid)

    def test_network_and_device_paths_refused_before_stat_or_resolution(self):
        for path in ("//example.invalid/share/file", r"\\example.invalid\share\file", r"\\.\pipe\example",
                     r"\\?\UNC\example.invalid\share\file", r"\??\UNC\example.invalid\share\file"):
            with self.subTest(path=path), patch.object(Path, "stat", side_effect=AssertionError("Unexpected stat")), \
                    patch.object(Path, "resolve", side_effect=AssertionError("Unexpected resolution")):
                for operation in (lambda: read_limited(path, 256), lambda: snapshots.read_snapshot("modeldial", path),
                                  lambda: write_outputs({"x": b"x"}, path)):
                    with self.assertRaisesRegex(ImportProblem, "UNC or device"):
                        operation()

    def test_case_aliases_refused_before_writing(self):
        with self.assertRaisesRegex(ImportProblem, "case-insensitive"):
            write_outputs({"First.json": b"original", "first.JSON": b"alias"}, self.drafts)
        self.assertFalse(self.drafts.exists())

    def test_download_refuses_partial_oversized_and_redirected_responses(self):
        source = PRIMARY["modeldial"]
        for status, url, declared in [(200, source.url, "5"), (200, source.url, str(source.limit + 1)),
                                      (206, source.url, "1"), (200, "https://example.invalid", "1")]:
            response = io.BytesIO(b"x")
            response.status, response.headers, response.geturl = status, {"Content-Length": declared}, lambda: url
            with patch("urllib.request.build_opener") as build, self.assertRaises(ImportProblem):
                build.return_value.open.return_value = response
                snapshots._download(source)
        req = Mock(full_url=source.url)
        with self.assertRaisesRegex(ImportProblem, "redirect"):
            snapshots._NoRedirect().redirect_request(req, None, 302, "", {}, "https://example.invalid")


if __name__ == "__main__":
    unittest.main()
