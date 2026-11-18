"""Offline regressions for the ECI identity join, merged measurement rows and units."""
import csv
from io import BytesIO, StringIO
import json
from unittest import TestCase
import zipfile

from external_sources.common import ImportProblem
from external_sources.open_models import convert, ECI_TABLE

AT = '2026-01-01T00:00:00Z'
MODEL = 'Qwen3-8B'


def source(rows, name=ECI_TABLE):
    table = StringIO()
    writer = csv.writer(table)
    writer.writerow(['Model', 'Display name', 'eci', 'Accessibility group'])
    writer.writerows((m, m, s, g) for m, s, g in rows)
    raw = BytesIO()
    with zipfile.ZipFile(raw, 'w') as archive:
        archive.writestr(name, table.getvalue())
    return raw.getvalue()


def result(raw, supplements=None):
    return convert(raw, retrieved_at=AT, generated_at=AT, supplements=supplements)


def sample_page():
    header = b'''<html><title>Speed Benchmark</title>
<section id="hugging-face-transformers"><li>Hardware</li>: <p>NVIDIA H20 96GB</p> Software for Non-AutoAWQ: Transformers 4.51.3 Software for AutoAWQ: Transformers 4.51.3</section>
<section id="notes">We use a <b>batch size of 1</b> and the benchmark when generating <b>2048 tokens</b>.</section>'''
    table = b'''<section id="qwen3-8b-transformers"><table><tr><th>Model</th><th>Input Length</th><th>Quantization</th><th>GPU Num</th><th>Speed (tokens/s)</th><th>GPU Memory (MB)</th></tr>
<tr><td rowspan="18">Qwen3-8B</td><td>1</td><td>FP8</td><td>1</td><td>100</td><td>500</td></tr>
<tr><td rowspan="2">6144</td><td>BF16</td><td>1</td><td>20</td><td>15000</td></tr>
<tr><td>AWQ-INT4</td><td>1</td><td>21</td><td>7100</td></tr></table></section>'''
    models = (b'Qwen3-8B', b'Qwen3-14B', b'Qwen3-32B', b'Qwen3-30B-A3B')
    return header + b''.join(table.replace(b'Qwen3-8B', m).replace(b'qwen3-8b', m.lower()) for m in models) + b'</html>'


def meta(repo, **data):
    return json.dumps(dict(id='Qwen/' + repo, sha='a' * 40, **data)).encode()


class OpenModelsTests(TestCase):
    def test_only_open_weight_versions_with_scores_compete(self):
        raw = source([('Example-C', '0', 'Open weights'), ('Example-Missing', '', 'Open weights'),
                      ('Example-Z', '130', 'Open weights'), ('Example-A', '130.00', 'Open weights'),
                      ('Example-Closed', '150', 'Closed weights'), ('Example-Other', '180', 'Other')])
        outputs = result(raw)
        self.assertEqual(outputs, result(raw))
        package = json.loads(outputs['open-models.draft.json'])
        entries = package['data']['entries']
        self.assertEqual([(e['model_version'], e['rank'], e['score']) for e in entries],
                         [('Example-A', 1, '130.00'), ('Example-Z', 1, '130'), ('Example-C', 3, '0')])
        self.assertFalse(any('hardware' in e or 'license' in e for e in entries))
        self.assertEqual(package['data']['as_of'], AT)
        self.assertEqual(json.loads(outputs['open-models.metadata.json'])['missing_scores'], ['Example-Missing'])
        self.assertIn('CC BY 4.0', package['content']['body_markdown'])

    def test_exact_version_hub_identity_and_measured_conditions(self):
        raw = source([(MODEL, '110', 'Open weights'), (MODEL + '-Distill', '101', 'Open weights')])
        files = {'qwen-benchmark.html': sample_page(), f'hf-{MODEL}.json': meta(MODEL, cardData={'license': 'example'}),
                 f'hf-{MODEL}-GGUF.json': meta(MODEL + '-GGUF', siblings=[{'rfilename': MODEL + '-Q4_K_M.gguf', 'size': 5555}])}
        outputs = result(raw, files)
        p = json.loads(outputs['open-models.draft.json'])
        base, other = p['data']['entries']
        self.assertEqual(base['weights_bytes'], 5555)
        self.assertEqual(base['license'], 'example')
        self.assertTrue(base['product_url'].endswith('a' * 40))
        self.assertEqual([r['memory_mb'] for r in base['hardware']], [7100, 15000])
        self.assertEqual(base['hardware'][0]['quantization'], 'AWQ-INT4')
        self.assertTrue(all(r['input_tokens'] == 6144 and r['output_tokens'] == 2048 and
                            r['batch_size'] == 1 and r['source_id'] in {s['source_id'] for s in p['sources']}
                            for r in base['hardware']))
        self.assertFalse(any(k in other for k in ('hardware', 'license', 'weights_bytes')))
        self.assertNotEqual(p['submission_id'], json.loads(result(raw)['open-models.draft.json'])['submission_id'])
        self.assertEqual(p['content_id'], json.loads(result(raw)['open-models.draft.json'])['content_id'])

    def test_rejects_drift_or_wrong_hardware_source(self):
        raw = source([(MODEL, '128.76', 'Open weights')])
        for files in ({f'hf-{MODEL}.json': meta('some-other-repo')},
                      {f'hf-{MODEL}-GGUF.json': meta(MODEL + '-GGUF', siblings=[{'rfilename': 'Q8.gguf', 'size': 2}])},
                      {'qwen-benchmark.html': sample_page().replace(b'96GB', b'48GB')},
                      {'qwen-benchmark.html': sample_page().replace(b'4.51.3', b'4.52.0', 1)},
                      {'qwen-benchmark.html': sample_page().replace(b'2048 tokens', b'4096 tokens')},
                      {'qwen-benchmark.html': sample_page().replace(b'Memory (MB)', b'Memory (GB)')},
                      {'qwen-benchmark.html': sample_page().replace(b'rowspan="2"', b'rowspan="3"')}):
            with self.subTest(keys=list(files)), self.assertRaises(ImportProblem):
                result(raw, files)

    def test_rejects_incomplete_or_unrepresentable_scores(self):
        good = [('Demo', '1', 'Open weights')]
        for raw in (source(good, '../eci_scores.csv'), b'not a zip', source(good + good),
                    source([('Demo', 'NaN', 'Open weights')]), source([('Demo', '1', 'Other')])):
            with self.assertRaises(ImportProblem):
                result(raw)
