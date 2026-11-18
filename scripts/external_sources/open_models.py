"""Create one reviewable open-weight leaderboard from a frozen Epoch ECI table."""
import csv
from decimal import Decimal
import io
import re
import zipfile
import zlib

from .common import ImportProblem, digest, encode, load_json, require, stable_id, text, timestamp
from .qwen_hardware import profiles
from .sources import EPOCH_URL, HARDWARE, PRIMARY, QWEN_BENCHMARK_URL, QWEN_MODELS

ECI_TABLE = 'epoch_capabilities_index/eci_scores.csv'
ECI_INFO = 'https://epoch.ai/benchmarks/use-this-data'
CC_BY = 'https://creativecommons.org/licenses/by/4.0/'
VERSION = '1.0'


def _score(value, column):
    require(isinstance(value, str) and len(value) <= 40 and re.fullmatch(r'(0|[1-9][0-9]*)(\.[0-9]{1,12})?', value),
            f'ECI {column}: invalid decimal score {value!r}')
    return Decimal(value)


def _read_eci(raw):
    require(len(raw) <= PRIMARY['open-models'].limit, 'Epoch ZIP exceeds size limit')
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            matches = [item for item in archive.infolist() if item.filename == ECI_TABLE]
            require(len(matches) == 1, 'Expected one exact Epoch ECI table in archive')
            require(matches[0].file_size <= 2 * 1024 * 1024, 'ECI table exceeds 2 MiB')
            require(matches[0].compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), 'Unsupported ECI compression')
            table = archive.read(matches[0])
    except (zipfile.BadZipFile, RuntimeError, zlib.error, OSError, EOFError) as exc:
        raise ImportProblem(f'Invalid Epoch ZIP: {exc}') from exc
    included, missing, seen = [], [], set()
    try:
        rows = csv.DictReader(io.StringIO(table.decode('utf-8-sig'), newline=''), strict=True)
        columns = rows.fieldnames or []
        require(len(set(columns)) == len(columns) and
                {'Model', 'Display name', 'eci', 'Accessibility group'} <= set(columns), 'Unexpected Epoch ECI columns')
        for row in rows:
            require(None not in row and None not in row.values(), f'ECI row {rows.line_num}: malformed column count')
            if row['Accessibility group'] != 'Open weights':
                continue
            model = text(row['Model'], 'ECI model version')
            require(model not in seen, f'Duplicate ECI model version: {model}')
            seen.add(model)
            if not row['eci']:
                missing.append(model)
                continue
            _score(row['eci'], 'eci')
            included.append((model, text(row['Display name'], 'ECI display name'), row['eci']))
    except (UnicodeError, csv.Error) as exc:
        raise ImportProblem(f'Invalid ECI CSV: {exc}') from exc
    require(1 <= len(included) <= 200, f'Expected 1..200 scored open models, found {len(included)}')
    included.sort(key=lambda row: row[0])
    included.sort(key=lambda row: Decimal(row[2]), reverse=True)
    return included, sorted(missing), digest(table)


def _source(key, url, author, at, note, kind='dataset'):
    return dict(source_id=key, url=url, author=author, retrieved_at=at, original_published_at=None,
                material_type=kind, usage_note=note)


def _hub_record(repo, raw):
    obj = load_json(raw)
    require(isinstance(obj, dict) and obj.get('id') == 'Qwen/' + repo, f'Hugging Face repository mismatch: {repo}')
    sha = obj.get('sha')
    require(isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{40}', sha), f'Missing Hub revision: {repo}')
    return obj, 'https://huggingface.co/Qwen/' + repo + '/tree/' + sha


def _enrich(models, files, sources, times):
    additions = {model: {} for model in models}
    expected = {item.name: item.limit for item in HARDWARE}
    for name, raw in files.items():
        require(name in expected and isinstance(raw, bytes) and len(raw) <= expected[name], f'Invalid supplement: {name}')
    matched = [m for m in QWEN_MODELS if m in additions]
    if 'qwen-benchmark.html' in files:
        measurements = profiles(files['qwen-benchmark.html'], QWEN_MODELS)
        if matched:
            sources.append(_source('qwen-benchmark', QWEN_BENCHMARK_URL, 'Qwen Team', times['qwen-benchmark.html'],
                                   '厂商公开的推理实测，按设备、框架和工作负载摘录显存数值。', 'webpage'))
        for model in matched:
            additions[model]['hardware'] = measurements[model]
    for model in QWEN_MODELS:
        for repo in (model, model + '-GGUF'):
            filename = 'hf-' + repo + '.json'
            if filename not in files:
                continue
            obj, url = _hub_record(repo, files[filename])
            fields = {}
            if repo == model:
                card = obj.get('cardData') or {}
                require(isinstance(card, dict), f'{repo}: invalid model card')
                if card.get('license'):
                    fields['license'] = text(card['license'], 'model license', 80)
                fields['product_url'] = url
            else:
                siblings = obj.get('siblings')
                require(isinstance(siblings, list) and all(isinstance(row, dict) for row in siblings),
                        f'{repo}: invalid files list')
                weights = [row.get('size') for row in siblings if row.get('rfilename') == model + '-Q4_K_M.gguf']
                require(len(weights) == 1 and type(weights[0]) is int and 0 < weights[0] <= 2**53 - 1,
                        f'{repo}: expected one exact Q4_K_M file with its size')
                fields['weights_bytes'] = weights[0]
            if model in additions:
                additions[model].update(fields)
                sources.append(_source('hf-' + repo.lower().replace('.', '-'), url, 'Qwen / ' + repo, times[filename],
                                       '官方模型仓库，固定到本次取得资料的提交。', 'webpage'))
    return additions


def convert(raw, *, retrieved_at, generated_at, supplements=None, source_times=None):
    timestamp(retrieved_at, 'retrieved_at')
    timestamp(generated_at, 'generated_at')
    rows, missing, table_hash = _read_eci(raw)
    files = supplements or {}
    inputs = {'benchmark_data.zip': digest(raw), **{name: digest(data) for name, data in sorted(files.items())}}
    sources = [_source('epoch-eci', ECI_INFO, 'Epoch AI', retrieved_at, 'ECI 指数快照。署名、许可与筛选说明见正文。')]
    times = source_times if source_times is not None else dict.fromkeys(inputs, retrieved_at)
    require(set(times) == set(inputs) and times['benchmark_data.zip'] == retrieved_at, 'Incomplete source retrieval times')
    for filename, when in times.items():
        timestamp(when, filename + '.retrieved_at')
    additions = _enrich([row[0] for row in rows], files, sources, times)
    entries = []
    previous_score = None
    for i, (model, name, score) in enumerate(rows, 1):
        number = Decimal(score)
        if number != previous_score:
            rank = i
        previous_score = number
        entries.append(dict(entry_id=stable_id('eci-row', model), tool_id=stable_id('eci-model', model),
                            model_version=model, tool_name=name, score=score, rank=rank, source_id='epoch-eci',
                            reason='按本期 Epoch 能力指数降序排列，同分并列。', **additions[model]))
    body = (f'能力分采用 [Epoch AI]({ECI_INFO}) 发布的 Epoch Capabilities Index（ECI），'
            f'来自同一份[成绩包]({EPOCH_URL})。依照 [CC BY 4.0]({CC_BY}) 署名使用。'
            '本站筛选有指数的开放权重模型，按原始数值降序重排，同分并列。榜单日期为资料取得时间。'
            '能力指数对应来源所列的模型版本。各量化版本的能力表现需要单独测量。\n\n'
            '显存档位按单卡、单请求、输入 6,144 个 token 并生成 2,048 个 token 的实测用量筛选。'
            '选择模型时可展开配置核对量化方式、测试设备和框架。GGUF Q4_K_M 大小表示下载空间。'
            '不同显卡的兼容性和生成速度仍需在对应设备验证。\n\n'
            '家用设备的生成速度和首字延迟可参考 [LocalScore 单机实测](https://www.localscore.ai/)。')
    if any('hardware' in row for row in entries):
        body += (f'\n\n显存数据摘自 [Qwen Team 公开实测表]({QWEN_BENCHMARK_URL})，'
                 '按型号和量化方式摘录对应行的显存数值。原文版权归 Qwen Team，具体使用条款请查阅来源文档。')
    package = dict(schema_version='1.0', kind='leaderboard', content_id=stable_id('epoch-open', table_hash),
                   content=dict(title='开放权重模型榜', summary='比较可本地部署模型的能力，按单卡实测显存筛选。',
                                body_markdown=body, tags=['开放权重', '本地部署']),
                   data=dict(task='open-models', benchmark='Epoch Capabilities Index',
                             method_version='Epoch ECI / open-weights-v' + VERSION, as_of=retrieved_at,
                             score_unit='index', higher_is_better=True, ranking_mode='score', allow_ties=True,
                             entries=entries), sources=sources, assets=[],
                   provenance=dict(skill_name='epoch-open-models-import', skill_version=VERSION, generated_at=generated_at,
                                   input_sha256=digest(encode(inputs))))
    package['submission_id'] = stable_id('epoch-submit', package)
    payload = encode(package)
    require(len(payload) <= 128 * 1024, 'Draft exceeds the 128 KiB request limit')
    metadata = dict(inputs=inputs, source_times=times, eci_table_sha256=table_hash, retrieved_at=retrieved_at, generated_at=generated_at,
                    missing_scores=missing, ranked_models=len(entries), measured_models=sum('hardware' in e for e in entries),
                    content_id=package['content_id'], submission_id=package['submission_id'], draft_sha256=digest(payload))
    return {'open-models.draft.json': payload, 'open-models.metadata.json': encode(metadata)}
