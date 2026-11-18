"""Fixed anonymous HTTPS resources. No crawling, model search or weight downloads."""
from dataclasses import dataclass

from .common import require

MODELDIAL_URL = "https://modeldial.com/data/overview.json"
EPOCH_URL = "https://epoch.ai/data/benchmark_data.zip"
QWEN_BENCHMARK_URL = "https://qwen.readthedocs.io/en/latest/getting_started/speed_benchmark.html"
QWEN_MODELS = ("Qwen3-8B", "Qwen3-14B", "Qwen3-32B", "Qwen3-30B-A3B")
MIB = 1024 * 1024


@dataclass(frozen=True)
class Source:
    name: str
    url: str
    limit: int


PRIMARY = {
    "modeldial": Source("overview.json", MODELDIAL_URL, 16 * MIB),
    "open-models": Source("benchmark_data.zip", EPOCH_URL, 128 * MIB),
}
QWEN_BENCHMARK = Source("qwen-benchmark.html", QWEN_BENCHMARK_URL, 8 * MIB)
HARDWARE = (QWEN_BENCHMARK, *(
    Source(f"hf-{repo}.json", f"https://huggingface.co/api/models/Qwen/{repo}?blobs=true", 4 * MIB)
    for model in QWEN_MODELS for repo in (model, model + "-GGUF")))


def sources_for(adapter, with_hardware=False):
    require(adapter in PRIMARY, f"Unknown adapter: {adapter}")
    require(not with_hardware or adapter == "open-models", "--with-hardware requires open-models")
    return (PRIMARY[adapter], *HARDWARE) if with_hardware else (PRIMARY[adapter],)
