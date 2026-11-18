"""Read measured memory from the documented Qwen Transformers benchmark tables."""
from html.parser import HTMLParser
import re

from .common import ImportProblem, require
from .sources import QWEN_BENCHMARK, QWEN_MODELS


class _BenchmarkPage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.sections = []
        self.texts = {}
        self.tables = {}
        self.wanted = {name.lower() + '-transformers' for name in QWEN_MODELS}
        self.table = self.row = self.cell = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'section':
            self.sections.append(attrs.get('id', ''))
        section = self.sections[-1] if self.sections else ''
        if tag == 'table' and section in self.wanted:
            require(section not in self.tables, f'{section}: unexpected additional table')
            self.table = self.tables[section] = []
        elif self.table is not None:
            if tag == 'tr':
                self.row = []
                self.table.append(self.row)
            elif tag in ('th', 'td') and self.row is not None:
                span = attrs.get('rowspan', '1')
                require(re.fullmatch(r'[1-9]\d{0,2}', span) and attrs.get('colspan', '1') == '1',
                        f'{section}: unsupported cell span')
                self.cell = []
                self.row.append((int(span), self.cell))

    def handle_endtag(self, tag):
        if tag in ('th', 'td'):
            self.cell = None
        elif tag == 'tr':
            self.row = None
        elif tag == 'table':
            self.table = self.row = self.cell = None
        elif tag == 'section' and self.sections:
            self.sections.pop()

    def handle_data(self, data):
        section = self.sections[-1] if self.sections else ''
        if section in ('notes', 'hugging-face-transformers'):
            self.texts.setdefault(section, []).append(data)
        if self.cell is not None:
            self.cell.append(data)


def _table_rows(rows, model):
    require(rows, f'{model}: missing benchmark table')
    def value(cell):
        return ' '.join(''.join(cell).split())
    header = [value(cell).lower().replace(' ', '') for _, cell in rows[0]]
    columns = ['model', 'inputlength', 'quantization', 'gpunum', 'speed(tokens/s)', 'gpumemory(mb)']
    require(header in (columns, columns + ['notes']), f'{model}: unsupported benchmark columns')
    pending = {}
    for row in rows[1:]:
        values = []
        cells = iter(row)
        for column in range(len(header)):
            if column in pending:
                span, data = pending.pop(column)
            else:
                cell = next(cells, None)
                require(cell is not None, f'{model}: incomplete benchmark row')
                span, data = cell[0], value(cell[1])
            if span > 1:
                pending[column] = (span - 1, data)
            values.append(data)
        require(next(cells, None) is None and values[0] == model, f'{model}: mismatched benchmark row')
        yield values
    # Browsers clamp the vendor's oversized model-label rowspan to the table body.
    require(set(pending) <= {0}, f'{model}: incomplete measurement cells')


def profiles(raw, models):
    require(len(raw) <= QWEN_BENCHMARK.limit, 'Qwen benchmark HTML exceeds input limit')
    page = _BenchmarkPage()
    try:
        page.feed(raw.decode('utf-8'))
        page.close()
    except UnicodeError as exc:
        raise ImportProblem('Qwen benchmark must be UTF-8') from exc
    env = ' '.join(' '.join(page.texts.get('hugging-face-transformers', [])).split())
    notes = ' '.join(' '.join(page.texts.get('notes', [])).split())
    environments = re.fullmatch(r'.*?Hardware : NVIDIA H20 96GB Software for Non-AutoAWQ\s*:(.*?)Software for AutoAWQ\s*:(.*)', env)
    require(environments and all(re.findall(r'\bTransformers\s+([0-9][^\s,;]*)', section) == ['4.51.3']
                                 for section in environments.groups()),
            'Qwen Transformers hardware/software changed; review both quantization environments')
    require('batch size of 1 and the' in notes and 'when generating 2048 tokens' in notes,
            'Qwen benchmark workload changed; review the adapter')
    result = {}
    for model in models:
        records = {}
        for _, length, quant, gpu_count, _, memory, *_ in _table_rows(page.tables.get(model.lower() + '-transformers'), model):
            if length != '6144' or (gpu_count == '-' and memory == '-'):
                continue
            require(gpu_count.isascii() and gpu_count.isdecimal() and memory.isascii() and memory.isdecimal()
                    and 0 < int(memory) <= 2**31 - 1 and 0 < int(gpu_count) <= 1024,
                    f'{model}: invalid GPU count or memory')
            require(re.fullmatch(r'[A-Za-z0-9-]{1,40}', quant) and quant not in records,
                    f'{model}: duplicate or invalid quantization')
            records[quant] = dict(device='NVIDIA H20 96GB', runtime='Transformers 4.51.3',
                                  input_tokens=6144, output_tokens=2048, batch_size=1,
                                  quantization=quant, memory_mb=int(memory), gpu_count=int(gpu_count),
                                  evidence='vendor', source_id='qwen-benchmark')
        require(records, f'{model}: no measured profiles for the reference workload')
        result[model] = sorted(records.values(), key=lambda item: item['memory_mb'])
    return result
