"""Convert an explicitly supplied ModelDial overview into local draft packages only."""
from __future__ import annotations

import argparse
from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

VERSION = "1.0"
MAX_INPUT_BYTES = 16 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1]
POLICY = "five-axis-b20-f25-k35-a10-m10-cube-half_up-v3"
AXES = {
    "backend": ("后端工程", "programming", 20),
    "frontend": ("前端交互", "programming", 25),
    "knowledge": ("知识推理", "research", 35),
    "aesthetic": ("视觉审美", "programming", 10),
    "maintainability": ("可维护性", "programming", 10),
}
OVERVIEW_URL = "https://modeldial.com/data/overview.json"
RADAR_URL = "https://modeldial.com/radar"
LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
HASH_PATTERN = re.compile(r"sha256:[a-f0-9]{64}")
DECIMAL_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]{1,12})?")


class ImportProblem(ValueError):
    """An unsupported or incomplete source must not produce a partial import."""


def require(condition, message):
    if not condition:
        raise ImportProblem(message)


def text(value, location, maximum=200):
    require(isinstance(value, str) and bool(value.strip()) and len(value) <= maximum
            and not any(ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF for char in value),
            f"{location}: expected nonempty Unicode text without control characters (max {maximum})")
    return value


def object_at(value, location):
    require(isinstance(value, dict), f"{location}: expected an object")
    return value


def array_at(value, location):
    require(isinstance(value, list), f"{location}: expected an array")
    return value


def timestamp(value, location):
    text(value, location, 40)
    require(bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})", value)),
            f"{location}: expected RFC 3339 timestamp with an explicit time zone")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.utcoffset() is not None, f"{location}: time zone is required")
    except ValueError as error:
        raise ImportProblem(f"{location}: invalid timestamp") from error
    return value


def hash_identity(value, location):
    require(isinstance(value, str) and bool(HASH_PATTERN.fullmatch(value)),
            f"{location}: expected sha256:<64 lowercase hex digits>")
    return value


def score(value, location):
    if value is None:
        return None
    require(type(value) in (int, Decimal), f"{location}: expected a JSON number or null")
    number = Decimal(value)
    require(number.is_finite() and 0 <= number <= 100,
            f"{location}: final axis score must be finite and between 0 and 100")
    if number.is_zero():
        return "0"
    sign, digits, exponent = number.as_tuple()
    trailing_zeros = 0
    for digit in reversed(digits):
        if digit != 0:
            break
        trailing_zeros += 1
    effective_exponent = exponent + trailing_zeros
    fraction_digits = max(-effective_exponent, 0)
    integer_digits = max(number.adjusted() + 1, 1)
    require(fraction_digits <= 12 and integer_digits + (1 + fraction_digits if fraction_digits else 0) <= 40,
            f"{location}: score cannot be represented by the existing decimal-string contract")
    normalized = Decimal((sign, digits[:len(digits) - trailing_zeros], effective_exponent))
    result = format(normalized, "f")
    require(len(result) <= 40 and bool(DECIMAL_PATTERN.fullmatch(result)),
            f"{location}: score cannot be represented by the existing decimal-string contract")
    return result


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"JSON: duplicate object key {key!r}")
        result[key] = value
    return result


def _invalid_constant(value):
    raise ImportProblem(f"JSON: nonfinite number {value} is not supported")


def load_overview(raw):
    require(len(raw) <= MAX_INPUT_BYTES,
            f"Input exceeds the {MAX_INPUT_BYTES}-byte (16 MiB) overview limit")
    try:
        return json.loads(raw.decode("utf-8-sig"), parse_float=Decimal,
                          object_pairs_hook=_unique_pairs, parse_constant=_invalid_constant)
    except ImportProblem:
        raise
    except (ValueError, InvalidOperation, RecursionError) as error:
        raise ImportProblem(f"Input must contain one UTF-8 JSON object with representable numbers: {error}") from error


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def stable_id(prefix, value):
    return prefix + "-" + digest(encode(value))[:48]


def markdown(value):
    # Escape source strings before embedding them as prose or table cells.
    for char in "\\`*_{}[]<>()|#!":
        value = value.replace(char, "\\" + char)
    return value


def product_markers(value, location):
    if "api_model_ranking_eligible" in value:
        require(type(value["api_model_ranking_eligible"]) is bool,
                f"{location}.api_model_ranking_eligible: expected a boolean")
    if "product_kind" in value:
        require(isinstance(value["product_kind"], str) and value["product_kind"] in ("model", "bot"),
                f"{location}.product_kind: expected a known kind (model or bot)")


def product_evaluation(value, location):
    object_at(value, location)
    product_markers(value, location)
    require(value.get("kind") == "external_product_evaluation",
            f"{location}.kind: expected external_product_evaluation")
    return tuple(text(value.get(field), f"{location}.{field}", 500)
                 for field in ("product_id", "evaluation_id"))


def prepare(overview):
    """Validate the observed five-axis envelope and return eligible configurations."""
    object_at(overview, "overview")
    snapshot = object_at(overview.get("snapshot"), "snapshot")
    batch = text(snapshot.get("batch_id"), "snapshot.batch_id", 200)
    published = timestamp(snapshot.get("published_at"), "snapshot.published_at")
    publication = hash_identity(overview.get("publicationKey"), "publicationKey")
    view = overview.get("viewKey")
    require(isinstance(view, str) and bool(re.fullmatch(r"[a-f0-9]{64}", view)),
            "viewKey: expected 64 lowercase hex digits")
    quality = object_at(overview.get("qualityScores"), "qualityScores")
    quality_publication = object_at(overview.get("qualityPublication"), "qualityPublication")
    require(quality.get("schema_version") == 1, "qualityScores.schema_version: only version 1 is supported")
    require(quality.get("policy_id") == POLICY and quality_publication.get("policy_id") == POLICY,
            "qualityScores/qualityPublication.policy_id: unsupported or inconsistent five-axis policy")
    require(quality.get("base_publication_key") == publication,
            "qualityScores.base_publication_key: does not match publicationKey")
    require(quality.get("weights") == {axis: settings[2] for axis, settings in AXES.items()},
            "qualityScores.weights: unsupported five-axis weights")
    configuration_sources = object_at(quality_publication.get("configuration_sources"),
                                      "qualityPublication.configuration_sources")
    for candidate, source in configuration_sources.items():
        text(candidate, "configuration_sources candidate_id", 500)
        hash_identity(source, f"configuration_sources[{candidate}]")
    hidden = set(text(value, "hiddenModelIds[]", 500)
                 for value in array_at(overview.get("hiddenModelIds"), "hiddenModelIds"))
    product_ids = set()
    for index, product in enumerate(array_at(overview.get("productEvaluations"), "productEvaluations")):
        product_ids.update(product_evaluation(product, f"productEvaluations[{index}]"))
    qualities = {}
    for row in array_at(quality.get("entries"), "qualityScores.entries"):
        object_at(row, "qualityScores.entries[]")
        candidate = text(row.get("candidate_id"), "qualityScores candidate_id", 500)
        require(candidate not in qualities or qualities[candidate] == row,
                f"qualityScores: conflicting duplicate candidate {candidate}")
        qualities[candidate] = row
    names = object_at(overview.get("displayNames"), "displayNames")
    evidence = object_at(overview.get("evidence"), "evidence")
    eligible, excluded, seen, identities = [], [], {}, {}
    for index, row in enumerate(array_at(snapshot.get("entries"), "snapshot.entries")):
        location = f"snapshot.entries[{index}]"
        object_at(row, location)
        candidate = text(row.get("candidate_id"), location + ".candidate_id", 500)
        model = text(row.get("model_id"), location + ".model_id", 200)
        product_markers(row, location)
        if "productEvaluation" in row:
            product, evaluation = product_evaluation(row["productEvaluation"], location + ".productEvaluation")
            require(product == model and evaluation == candidate,
                    f"{location}.productEvaluation: product/evaluation identity does not match the row")
        if candidate in hidden or model in hidden:
            excluded.append({"candidate_id": candidate, "reason": "hidden"})
            continue
        if (candidate in product_ids or model in product_ids
                or row.get("product_kind") == "bot"
                or "productEvaluation" in row
                or row.get("api_model_ranking_eligible") is False):
            excluded.append({"candidate_id": candidate, "reason": "product_evaluation"})
            continue
        connection = text(row.get("connection_id"), location + ".connection_id", 200)
        effort = text(row.get("reasoning_effort"), location + ".reasoning_effort", 200)
        require(effort.lower() not in ("unknown", "unspecified"),
                f"{location}.reasoning_effort: explicit configuration identity is required")
        require(row.get("score_policy") == "five-axis", f"{location}.score_policy: expected five-axis")
        scores = {}
        for axis in AXES:
            field = axis + "_score"
            require(field in row, f"{location}.{field}: missing field; use explicit null for an unavailable score")
            scores[axis] = score(row[field], location + "." + field)
        quality_row = qualities.get(candidate)
        if scores["aesthetic"] is not None or scores["maintainability"] is not None:
            require(quality_row is not None, f"{candidate}: missing qualityScores entry")
            require(quality_row.get("model_id") == model and quality_row.get("reasoning_effort") == effort,
                    f"{candidate}: inconsistent qualityScores configuration identity")
            source_scores = object_at(quality_row.get("scores"), f"{candidate}.qualityScores.scores")
            for axis in ("aesthetic", "maintainability"):
                require(axis in source_scores and score(source_scores[axis], f"{candidate}.qualityScores.{axis}") == scores[axis],
                        f"{candidate}: snapshot and qualityScores disagree for {axis}")
            require(candidate in configuration_sources, f"{candidate}: missing quality publication source identity")
        name = text(names.get(model, model), f"displayNames[{model}]", 200)
        row_evidence = object_at(evidence.get(candidate, {}), f"evidence[{candidate}]")
        protocols = {}
        for field in ("backendVersion", "frontendVersion", "qualityVersion"):
            if row_evidence.get(field) is not None:
                protocols[field] = text(row_evidence[field], f"{candidate}.{field}", 200)
        if "qualityVersion" in protocols:
            require(protocols["qualityVersion"] == POLICY, f"{candidate}: inconsistent evidence qualityVersion")
        normalized = {"candidate_id": candidate, "model_id": model, "connection_id": connection,
                      "reasoning_effort": effort, "display_name": name, "scores": scores,
                      "protocols": protocols, "quality_source": configuration_sources.get(candidate)}
        if candidate in seen:
            require(seen[candidate] == normalized, f"{candidate}: conflicting duplicate configuration")
            excluded.append({"candidate_id": candidate, "reason": "duplicate_candidate"})
            continue
        identity = (model, connection, effort)
        if identity in identities:
            require(identities[identity]["scores"] == scores,
                    f"{candidate}: conflicting scores for the same model/connection/effort identity")
        identities[identity] = normalized
        seen[candidate] = normalized
        eligible.append(normalized)
    require(bool(eligible), "snapshot.entries: no visible eligible model configurations")
    source_identity = {"batch_id": batch, "published_at": published, "publication_key": publication,
                       "view_key": view, "policy_id": POLICY}
    return source_identity, eligible, excluded


def convert(raw, *, retrieved_at, generated_at):
    """Build all five draft objects and a metadata sidecar without filesystem writes."""
    timestamp(retrieved_at, "retrieved_at")
    timestamp(generated_at, "generated_at")
    identity, rows, excluded = prepare(load_overview(raw))
    input_hash = digest(raw)
    outputs, package_metadata = {}, {}
    for axis, (label, task, _) in AXES.items():
        best = {}
        missing = []
        for row in rows:
            current = row["scores"][axis]
            if current is None:
                missing.append(row["candidate_id"])
                continue
            key = (-Decimal(current), row["connection_id"], row["reasoning_effort"], row["candidate_id"])
            previous = best.get(row["model_id"])
            if previous is None or key < previous[0]:
                best[row["model_id"]] = (key, row)
        selected = sorted((pair[1] for pair in best.values()),
                          key=lambda row: (-Decimal(row["scores"][axis]), row["model_id"],
                                           row["connection_id"], row["reasoning_effort"], row["candidate_id"]))
        require(1 <= len(selected) <= 200,
                f"{axis}: expected 1..200 models with an available score; found {len(selected)}")
        content_id = stable_id("md-" + axis, identity)
        method = POLICY + "/axis-best-v" + VERSION
        entries, mappings, table = [], [], []
        previous_score, rank = None, 0
        for position, row in enumerate(selected, 1):
            value = row["scores"][axis]
            if value != previous_score:
                rank = position
            previous_score = value
            model_identity = {field: row[field] for field in ("model_id", "connection_id", "reasoning_effort")}
            entry_id = stable_id("md-entry", {"axis": axis, **model_identity})
            entries.append({
                "entry_id": entry_id,
                "tool_id": stable_id("md-model", row["model_id"]),
                "tool_name": row["display_name"],
                "model_version": row["model_id"],
                "rank": rank, "score": value, "source_id": "modeldial-overview",
                "reason": f"来源分项成绩；思考档位：{row['reasoning_effort']}；连接：{row['connection_id']}；候选：{row['candidate_id']}。",
            })
            require(len(entries[-1]["reason"]) <= 1000, f"{axis}: configuration identity exceeds reason limit")
            mappings.append({"entry_id": entry_id, **model_identity, "candidate_id": row["candidate_id"],
                             "quality_source": row["quality_source"], "protocols": row["protocols"]})
            table.append("| " + " | ".join(markdown(str(value)) for value in
                         (rank, row["model_id"], row["reasoning_effort"], row["connection_id"], value)) + " |")
        body = (
            f"本稿整理 ModelDial 的{label}分项成绩。每个模型版本选取当前轴最高分配置，"
            "同分配置按连接 ID、原始思考档位、候选 ID 的字典序选择；模型同分采用 1、2、2、4 竞赛排名。"
            "隐藏项、产品 Bot 评测和当前轴缺项不参与；零分保留。名次由本转换器重算。\n\n"
            f"评测口径：ModelDial/{axis}/{POLICY}；转换方法：{method}。"
            "使用来源已经发布的最终分项成绩，单位为自定义评分，并非通过率；"
            "不从原始子轴重新计算成绩。不同配置的底层评测版本见下方来源映射，分差仅代表来源任务范围。\n\n"
            f"来源发布时间：{identity['published_at']}；来源批次：{markdown(identity['batch_id'])}。"
            "该时间为来源快照发布时间，不代表每个模型的测试时间。\n\n"
            f"数据来源：[ModelDial Radar]({RADAR_URL})；[overview JSON]({OVERVIEW_URL})。"
            f"采用 [CC BY 4.0]({LICENSE_URL}) 许可；[数据许可说明](https://modeldial.com/data-license)。"
            "本稿对数据进行了格式转换、筛选、按轴选配置及重新排名，分项成绩未修改。"
            "不表示 ModelDial 认可本站、分析或结论；本稿未作独立实测核验，也不生成推荐结论。\n\n"
            "ModelDial 加权综合分和旧三轴名次未用于本稿；本稿不对应本站人工排列的综合榜。\n\n"
            "| 名次 | 原始模型版本 | 原始思考档位 | 连接身份 | 分项成绩 |\n"
            "| --- | --- | --- | --- | --- |\n" + "\n".join(table) + "\n\n"
            "配置来源映射（保留来源提供的版本；缺失版本不推断）：\n\n" +
            "\n".join(f"- {markdown(row['candidate_id'])}：" +
                      ("；".join(markdown(key + "=" + value) for key, value in row["protocols"].items())
                       or "来源未提供底层评测版本") for row in selected) + "\n"
        )
        require(len(body) <= 65536, f"{axis}: public attribution body exceeds contract size")
        package = {
            "schema_version": "1.0", "content_id": content_id,
            "submission_id": "pending", "kind": "leaderboard",
            "content": {"title": f"{label}榜",
                        "summary": f"对比各模型在{label}方面的能力表现与排名。",
                        "body_markdown": body, "tags": ["ModelDial", label], "task_tags": [task]},
            "data": {"task": task, "benchmark": f"ModelDial/{axis}/{POLICY}",
                     "method_version": method, "as_of": identity["published_at"], "score_unit": "custom",
                     "higher_is_better": True, "allow_ties": True, "ranking_mode": "score", "entries": entries},
            "sources": [{"source_id": "modeldial-overview", "url": OVERVIEW_URL, "author": "ModelDial",
                         "original_published_at": identity["published_at"], "retrieved_at": retrieved_at,
                         "material_type": "dataset", "usage_note": "用户提供的离线 overview；公开署名和许可已写入正文。仅生成待审核草稿，未提交或核验。"}],
            "assets": [], "provenance": {"skill_name": "modeldial-offline-import", "skill_version": VERSION,
                                          "generated_at": generated_at, "input_sha256": input_hash},
        }
        package["submission_id"] = stable_id("md-submit", package)
        filename = f"modeldial-{axis}.draft.json"
        payload = encode(package)
        require(len(payload) <= 128 * 1024, f"{axis}: draft exceeds the 128 KiB request limit")
        outputs[filename] = payload
        package_metadata[axis] = {"file": filename, "content_id": content_id,
                                  "submission_id": package["submission_id"], "sha256": digest(payload),
                                  "entries": mappings, "missing_axis_candidates": sorted(set(missing))}
    metadata = {"converter_version": VERSION, "input_sha256": input_hash, "source": identity,
                "retrieved_at": retrieved_at, "generated_at": generated_at,
                "excluded": sorted(excluded, key=lambda row: (row["candidate_id"], row["reason"])),
                "packages": package_metadata}
    outputs["modeldial-import.metadata.json"] = encode(metadata)
    return outputs


def _ignored(path, repository):
    result = subprocess.run(["git", "-C", str(repository), "check-ignore", "--no-index", "--quiet", "--", str(path)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    require(result.returncode in (0, 1), "Could not establish Git ignore status for the output path")
    return result.returncode == 0


def write_outputs(outputs, output_dir, *, repository=ROOT, input_path=None):
    """Refuse tracked destinations, changed files and symlink output files."""
    repository, output_dir = Path(repository).resolve(), Path(output_dir).resolve()
    paths = [(output_dir / name, payload) for name, payload in outputs.items()]
    for path, payload in paths:
        require(not path.is_symlink(), f"Output is a symlink: {path}")
        if path.is_relative_to(repository):
            require(_ignored(path, repository), f"Output must be outside the repository or Git-ignored: {path}")
            tracked = subprocess.run(["git", "-C", str(repository), "ls-files", "--error-unmatch", "--", str(path)],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            require(tracked.returncode == 1, f"Output must not be tracked by Git: {path}")
        require(input_path is None or path != Path(input_path).resolve(), "Output would replace the input file")
        if path.exists():
            require(path.is_file() and path.read_bytes() == payload,
                    f"Output differs from existing file; choose a new output directory: {path}")
    output_dir.mkdir(parents=True, exist_ok=True)
    created = 0
    for path, payload in paths:
        try:
            with path.open("xb") as output:
                output.write(payload)
            created += 1
        except FileExistsError:
            require(not path.is_symlink() and path.is_file() and path.read_bytes() == payload,
                    f"Output changed during generation; choose a new output directory: {path}")
    return created


def main(argv=None):
    parser = argparse.ArgumentParser(description="Convert a downloaded ModelDial overview into five local draft JSON packages; no network or Halo access.")
    parser.add_argument("--input", required=True, type=Path, help="Explicit path to a downloaded overview JSON")
    parser.add_argument("--output-dir", required=True, type=Path, help="Explicit external or Git-ignored output directory")
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
