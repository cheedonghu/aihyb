"""Generate the versioned transport schema and clearly fictional PoC packages."""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'contracts'
FIXTURES = ROOT / 'package-fixtures'
TARGET.mkdir(exist_ok=True)
FIXTURES.mkdir(exist_ok=True)

def text(limit=200, **kwargs):
    return {'type': 'string', 'minLength': 1, 'maxLength': limit, **kwargs}

def obj(properties, required=None):
    return {'type': 'object', 'additionalProperties': False, 'properties': properties,
            'required': required if required is not None else list(properties)}

def arr(items, minimum=0, maximum=100):
    return {'type': 'array', 'items': items, 'minItems': minimum, 'maxItems': maximum}

def nullable(value):
    return {'anyOf': [value, {'type': 'null'}]}

identifier = text(80, pattern=r'^[a-z0-9][a-z0-9-]{0,79}$')
decimal = text(40, pattern=r'^(0|[1-9][0-9]*)(\.[0-9]{1,12})?$')
date = text(40, format='date-time')
source = obj({
    'source_id': identifier, 'url': nullable(text(2048, format='uri')),
    'author': nullable(text()), 'original_published_at': nullable(date),
    'retrieved_at': date, 'material_type': {'enum': ['webpage', 'file', 'dataset', 'self_test']},
    'usage_note': text(1000),
})
asset = obj({
    'asset_id': identifier, 'object_key': text(512), 'content_type': text(100),
    'sha256': text(64, pattern='^[a-f0-9]{64}$'), 'alt': text(500),
    'visibility': {'enum': ['private', 'public']},
})
content = obj({'title': text(200), 'summary': text(2000),
               'body_markdown': {'type': 'string', 'maxLength': 65536},
               'tags': arr(text(80), maximum=20)})
review = obj({
    'task': identifier, 'tool_id': identifier, 'tool_name': text(), 'tool_version': text(),
    'content_origin': {'enum': ['external_summary', 'original_test', 'official_explanation']},
    'conclusions': arr(obj({'text': text(2000), 'source_id': identifier,
                            'evidence': text(2000)}), minimum=1, maximum=30),
})
tutorial = obj({
    'task': identifier, 'tool_id': identifier, 'tool_name': text(), 'tool_version': text(),
    'prerequisites': arr(text(1000), maximum=30),
    'verification_status': {'enum': ['pending', 'source_only', 'reproduced']},
    'last_verified_at': nullable(date),
})
leaderboard = obj({
    'task': identifier, 'benchmark': text(), 'method_version': text(), 'as_of': date,
    'score_unit': {'enum': ['percent', 'elo', 'index', 'seconds', 'custom']},
    'higher_is_better': {'type': 'boolean'}, 'allow_ties': {'type': 'boolean'},
    'ranking_mode': {'enum': ['source_rank', 'score', 'editorial_order']},
    'entries': arr(obj({'entry_id': identifier, 'tool_id': identifier, 'tool_name': text(),
                        'model_version': text(), 'rank': {'type': 'integer', 'minimum': 1, 'maximum': 2147483647},
                        'score': nullable(decimal), 'source_id': identifier,
                        'reason': text(1000)}), minimum=1, maximum=200),
})
pricing = obj({
    'task': identifier, 'tool_id': identifier, 'tool_name': text(),
    'plans': arr(obj({
        'plan_id': identifier, 'name': text(), 'offer_kind': {'enum': ['subscription', 'api']},
        'amount': nullable(decimal), 'currency': text(3, pattern='^[A-Z]{3}$'),
        'price_unit': {'enum': ['month', 'year', 'million_input_tokens',
                               'million_output_tokens', 'request', 'image']},
        'billing_cycle': {'enum': ['monthly', 'annual', 'usage']},
        'region': text(80), 'channel': text(80), 'eligibility': text(1000),
        'free_quota': obj({'amount': nullable(decimal), 'unit': text(80),
                           'reset_period': text(80), 'condition': text(1000)}),
        'limits': arr(text(1000), maximum=30), 'source_id': identifier, 'verified_at': date,
    }), minimum=1, maximum=100),
})
data_shapes = {'review': review, 'tutorial': tutorial,
               'leaderboard': leaderboard, 'pricing': pricing}
schema = {
    '$schema': 'https://json-schema.org/draft/2020-12/schema',
    '$id': 'urn:aihyb:content-package:1.0', 'title': 'AIHYB content package 1.0',
    **obj({
        'schema_version': {'const': '1.0'}, 'content_id': identifier, 'submission_id': identifier,
        'base_revision': identifier, 'kind': {'enum': list(data_shapes)},
        'content': content, 'data': {'type': 'object'},
        'sources': arr(source, minimum=1, maximum=50), 'assets': arr(asset, maximum=50),
        'provenance': obj({'skill_name': text(), 'skill_version': text(80),
                           'generated_at': date, 'input_sha256': nullable(text(64, pattern='^[a-f0-9]{64}$'))}),
    }, ['schema_version', 'content_id', 'submission_id', 'kind', 'content', 'data',
        'sources', 'assets', 'provenance']),
    'allOf': [{'if': {'properties': {'kind': {'const': kind}}, 'required': ['kind']},
               'then': {'properties': {'data': shape,
                                      **({'content': {'properties': {'body_markdown': {'minLength': 1}}}}
                                         if kind in ['review', 'tutorial'] else {})}}}
              for kind, shape in data_shapes.items()],
}

moment = '2026-10-13T15:30:00Z'
base = {
    'schema_version': '1.0', 'content_id': '', 'submission_id': '', 'kind': '',
    'content': {'title': '', 'summary': '虚构 PoC 样例，不是真实产品建议。',
                'body_markdown': '', 'tags': ['PoC', '虚构数据']},
    'data': {}, 'sources': [{'source_id': 'source-poc', 'url': 'https://example.com/poc',
                             'author': 'PoC fixture', 'original_published_at': None,
                             'retrieved_at': moment, 'material_type': 'dataset',
                             'usage_note': '完全虚构的自建测试材料。'}],
    'assets': [], 'provenance': {'skill_name': 'poc-fixture', 'skill_version': '1.0',
                                'generated_at': moment, 'input_sha256': None},
}
examples = {}
examples['review'] = {'content': {'title': '演示测评：办公助手 A 制作 PPT',
    'body_markdown': '## 测试内容\n\n以下为虚构测评摘要。\n\n|项目|发现|\n|---|---|\n|结构|需人工检查|\n\n## 结论\n\n保留人工核验。[查看原始材料](https://example.com/poc)\n'},
    'data': {'task': 'presentation', 'tool_id': 'office-a', 'tool_name': '办公助手 A（虚构）',
             'tool_version': 'poc-v1', 'content_origin': 'external_summary',
             'conclusions': [{'text': '需要人工检查结构。', 'source_id': 'source-poc',
                              'evidence': '虚构测试材料：结构需人工检查。'}]}}
examples['tutorial'] = {'content': {'title': '演示教程：整理表格数据',
    'body_markdown': '## 前置条件\n\n准备虚构 CSV。\n\n## 步骤\n\n1. 上传样例。\n2. 复制提示词。\n\n```text\n请列出空值，不要修改原数据。\n```\n\n## 常见问题\n\n核对列名后重试。\n'},
    'data': {'task': 'spreadsheet', 'tool_id': 'office-a', 'tool_name': '办公助手 A（虚构）',
             'tool_version': 'poc-v1', 'prerequisites': ['一份虚构 CSV 样例'],
             'verification_status': 'pending', 'last_verified_at': None}}
examples['leaderboard'] = {'content': {'title': '演示榜单：文生图任务',
    'body_markdown': '## 评分方法\n\n完全虚构的单一基准，不能用于真实推荐。\n'},
    'data': {'task': 'image', 'benchmark': 'poc-image-only', 'method_version': '1.0',
             'as_of': moment, 'score_unit': 'percent', 'higher_is_better': True,
             'allow_ties': False, 'ranking_mode': 'score', 'entries': [
                 {'entry_id': 'image-a', 'tool_id': 'image-a', 'tool_name': '图像助手 A（虚构）',
                  'model_version': 'poc-v1', 'rank': 1, 'score': '85.5', 'source_id': 'source-poc',
                  'reason': '同一虚构基准下表现较高。'},
                 {'entry_id': 'image-b', 'tool_id': 'image-b', 'tool_name': '图像助手 B（虚构）',
                  'model_version': 'poc-v1', 'rank': 2, 'score': '79.0', 'source_id': 'source-poc',
                  'reason': '需要核验的虚构次序。'}]}}
examples['pricing'] = {'content': {'title': '演示价格：办公助手 A 会员',
    'body_markdown': '## 套餐说明\n\n金额、额度与限制来自下面的结构化记录，均为虚构。\n'},
    'data': {'task': 'office', 'tool_id': 'office-a', 'tool_name': '办公助手 A（虚构）', 'plans': [
        {'plan_id': 'office-a-month', 'name': '月付基础版（虚构）', 'offer_kind': 'subscription',
         'amount': '49.00', 'currency': 'CNY', 'price_unit': 'month', 'billing_cycle': 'monthly',
         'region': 'CN', 'channel': 'official-web', 'eligibility': '虚构新用户条件',
         'free_quota': {'amount': '10', 'unit': 'tasks', 'reset_period': 'month', 'condition': '虚构额度'},
         'limits': ['虚构限制：需人工核验'], 'source_id': 'source-poc', 'verified_at': moment},
        {'plan_id': 'office-a-year', 'name': '年付基础版（虚构）', 'offer_kind': 'subscription',
         'amount': '468.00', 'currency': 'CNY', 'price_unit': 'year', 'billing_cycle': 'annual',
         'region': 'CN', 'channel': 'official-web', 'eligibility': '按年实际扣款，金额不是月价',
         'free_quota': {'amount': None, 'unit': 'tasks', 'reset_period': 'month', 'condition': '未知'},
         'limits': ['虚构限制：不能把未知额度当零'], 'source_id': 'source-poc', 'verified_at': moment},
    ]}}

for kind, example in examples.items():
    package = copy.deepcopy(base)
    package.update({'kind': kind, 'content_id': 'poc-' + kind,
                    'submission_id': 'poc-' + kind + '-a', 'data': example['data']})
    package['content'].update(example['content'])
    (FIXTURES / (kind + '.json')).write_text(json.dumps(package, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

schema_bytes = json.dumps(schema, ensure_ascii=False, indent=2) + '\n'
(TARGET / 'content-package-v1.schema.json').write_text(schema_bytes, encoding='utf-8')
resource_dir = ROOT / 'plugin-aihyb/src/main/resources/contracts'
resource_dir.mkdir(parents=True, exist_ok=True)
(resource_dir / 'content-package-v1.schema.json').write_text(schema_bytes, encoding='utf-8')
print('Generated schema 1.0 and four fictional packages.')
