"""Offline tests using entirely fictional configurations and publication identities."""
import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import modeldial_import as importer


RECORDED = "2026-01-02T03:04:05Z"
PUBLICATION = "sha256:" + "a" * 64
QUALITY_SOURCE = "sha256:" + "b" * 64


def candidate(model, effort="high", connection="fictional-route", **scores):
    row = {"candidate_id": f"{connection}:{model}:{effort}", "model_id": model,
           "reasoning_effort": effort, "connection_id": connection,
           "score_policy": "five-axis", "rank": 999, "overall_score": 0}
    row.update({axis + "_score": scores.get(axis, 50) for axis in importer.AXES})
    return row


def product_evaluation(row):
    return {"product_id": row["model_id"], "evaluation_id": row["candidate_id"],
            "kind": "external_product_evaluation", "api_model_ranking_eligible": False}


def overview(rows=None):
    rows = rows or [candidate("fictional-model-a-v1")]
    qualities = [{"candidate_id": row["candidate_id"], "model_id": row["model_id"],
                  "reasoning_effort": row["reasoning_effort"],
                  "raw_scores": {"aesthetic": 99, "maintainability": 99},
                  "scores": {axis: row[axis + "_score"] for axis in ("aesthetic", "maintainability")}}
                 for row in rows]
    return {"snapshot": {"batch_id": "fictional-batch-001", "published_at": RECORDED, "entries": rows},
            "publicationKey": PUBLICATION, "viewKey": "c" * 64,
            "qualityPublication": {"policy_id": importer.POLICY,
                                   "configuration_sources": {row["candidate_id"]: QUALITY_SOURCE for row in rows}},
            "qualityScores": {"schema_version": 1, "policy_id": importer.POLICY,
                              "base_publication_key": PUBLICATION,
                              "weights": {axis: setting[2] for axis, setting in importer.AXES.items()},
                              "entries": qualities},
            "hiddenModelIds": [], "productEvaluations": [], "displayNames": {}, "evidence": {}}


def raw(data):
    return json.dumps(data, ensure_ascii=False).encode("utf-8")


def run_convert(data):
    return importer.convert(raw(data), retrieved_at=RECORDED, generated_at=RECORDED)


def package(outputs, axis="backend"):
    return json.loads(outputs[f"modeldial-{axis}.draft.json"])


def validate_schema(value, schema, path="package"):
    """Exercise the canonical schema subset used by leaderboard draft packages."""
    if "const" in schema:
        assert value == schema["const"], path
    if "enum" in schema:
        assert value in schema["enum"], path
    if "anyOf" in schema:
        for branch in schema["anyOf"]:
            try:
                validate_schema(value, branch, path)
                break
            except AssertionError:
                pass
        else:
            raise AssertionError(path + ": no anyOf branch matched")
    kind = schema.get("type")
    predicates = {"object": lambda: isinstance(value, dict), "array": lambda: isinstance(value, list),
                  "string": lambda: isinstance(value, str), "boolean": lambda: type(value) is bool,
                  "integer": lambda: type(value) is int, "null": lambda: value is None}
    if kind:
        assert predicates[kind](), path + ": invalid type"
    if isinstance(value, dict):
        assert set(schema.get("required", [])).issubset(value), path + ": missing required fields"
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            assert set(value).issubset(properties), path + ": unknown fields"
        for key, child in value.items():
            if key in properties:
                validate_schema(child, properties[key], path + "." + key)
    if isinstance(value, list):
        assert len(value) >= schema.get("minItems", 0), path
        assert len(value) <= schema.get("maxItems", float("inf")), path
        for child in value:
            validate_schema(child, schema.get("items", {}), path + "[]")
    if isinstance(value, str):
        assert len(value) >= schema.get("minLength", 0), path
        assert len(value) <= schema.get("maxLength", float("inf")), path
        if "pattern" in schema:
            assert importer.re.search(schema["pattern"], value), path + ": pattern mismatch"
        if schema.get("format") == "date-time":
            importer.timestamp(value, path)
    if type(value) is int:
        assert schema.get("minimum", value) <= value <= schema.get("maximum", value), path
    for branch in schema.get("allOf", []):
        try:
            validate_schema(value, branch["if"], path)
        except AssertionError:
            continue
        validate_schema(value, branch["then"], path)


class ConversionTests(unittest.TestCase):
    def test_five_packages_match_canonical_leaderboard_schema(self):
        outputs = run_convert(overview())
        schema = json.loads((importer.ROOT / "contracts/content-package-v1.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(len(outputs), 6)
        for axis in importer.AXES:
            item = package(outputs, axis)
            validate_schema(item, schema)
            self.assertEqual(item["kind"], "leaderboard")
            self.assertEqual(item["data"]["score_unit"], "custom")
            self.assertEqual(item["data"]["ranking_mode"], "score")
            self.assertNotEqual(item["data"]["task"], "overall")
            self.assertEqual(item["data"]["benchmark"], f"ModelDial/{axis}/{importer.POLICY}")
        self.assertEqual(package(outputs, "knowledge")["data"]["task"], "research")

    def test_axis_best_configuration_and_connections_are_independent(self):
        a = candidate("fictional-a-v1", "high", "route-a", backend=90, frontend=30)
        b = candidate("fictional-a-v1", "low", "route-b", backend=60, frontend=95)
        outputs = run_convert(overview([a, b]))
        backend = package(outputs)["data"]["entries"][0]
        frontend = package(outputs, "frontend")["data"]["entries"][0]
        self.assertEqual(backend["score"], "90")
        self.assertIn("route-a", backend["reason"])
        self.assertIn("high", backend["reason"])
        self.assertEqual(frontend["score"], "95")
        self.assertIn("route-b", frontend["reason"])
        self.assertEqual(frontend["model_version"], "fictional-a-v1")

    def test_competition_ties_ignore_source_rank_and_overall(self):
        rows = [candidate("fictional-d", backend=40), candidate("fictional-c", backend=80),
                candidate("fictional-a", backend=90), candidate("fictional-b", backend=80)]
        outputs = run_convert(overview(rows))
        entries = package(outputs)["data"]["entries"]
        self.assertEqual([entry["rank"] for entry in entries], [1, 2, 2, 4])
        self.assertEqual([entry["model_version"] for entry in entries],
                         ["fictional-a", "fictional-b", "fictional-c", "fictional-d"])

    def test_tie_configuration_uses_documented_lexical_identity(self):
        rows = [candidate("fictional-a", "high", "route-z"), candidate("fictional-a", "low", "route-a")]
        data = overview(rows)
        first = package(run_convert(data))["data"]["entries"]
        data["snapshot"]["entries"].reverse()
        second = package(run_convert(data))["data"]["entries"]
        self.assertEqual(first, second)
        self.assertIn("route-a", first[0]["reason"])

    def test_hidden_model_and_candidate_product_and_bot_filters(self):
        rows = [candidate("fictional-kept"), candidate("fictional-hidden"),
                candidate("fictional-config-hidden"), candidate("fictional-bot"),
                candidate("fictional-product"), candidate("fictional-embedded-product")]
        data = overview(rows)
        data["hiddenModelIds"] = [rows[1]["model_id"], rows[2]["candidate_id"]]
        rows[3]["product_kind"] = "bot"
        data["productEvaluations"] = [product_evaluation(rows[4])]
        rows[5]["product_kind"] = "model"
        rows[5]["productEvaluation"] = product_evaluation(rows[5])
        entries = package(run_convert(data))["data"]["entries"]
        self.assertEqual([row["model_version"] for row in entries], ["fictional-kept"])

    def test_malformed_product_evaluation_identities_are_rejected(self):
        mutations = [
            lambda p: p.pop("product_id"),
            lambda p: p.pop("evaluation_id"),
            lambda p: p.update(product_id=None),
            lambda p: p.update(evaluation_id=None),
            lambda p: p.update(product_id=""),
            lambda p: p.update(evaluation_id=[]),
            lambda p: p.pop("kind"),
            lambda p: p.update(kind="renamed_product_evaluation"),
        ]
        for inline in (False, True):
            for index, mutation in enumerate(mutations):
                with self.subTest(inline=inline, mutation=index):
                    data = overview()
                    row = data["snapshot"]["entries"][0]
                    product = product_evaluation(row)
                    mutation(product)
                    if inline:
                        row["productEvaluation"] = product
                    else:
                        data["productEvaluations"] = [product]
                    with self.assertRaisesRegex(importer.ImportProblem, "productEvaluations?\\[|productEvaluation\\."):
                        run_convert(data)

    def test_optional_product_markers_require_boolean_and_known_kind(self):
        for field, invalid_values in (("api_model_ranking_eligible", (None, "false", "true", 0, 1, {})),
                                      ("product_kind", (None, "product", "unknown", 0, [], {}))):
            for location in ("row", "index", "inline"):
                for invalid in invalid_values:
                    with self.subTest(field=field, location=location, invalid=invalid):
                        data = overview()
                        row = data["snapshot"]["entries"][0]
                        if location == "row":
                            target = row
                        elif location == "index":
                            target = product_evaluation(row)
                            data["productEvaluations"] = [target]
                        else:
                            target = product_evaluation(row)
                            row["productEvaluation"] = target
                        target[field] = invalid
                        with self.assertRaisesRegex(importer.ImportProblem, field):
                            run_convert(data)

    def test_inline_product_evaluation_must_be_an_object_with_matching_identity(self):
        for invalid in (None, [], "product", {},
                        {"kind": "external_product_evaluation", "product_id": "wrong-model", "evaluation_id": "wrong-candidate"}):
            with self.subTest(invalid=invalid):
                data = overview()
                data["snapshot"]["entries"][0]["productEvaluation"] = invalid
                with self.assertRaisesRegex(importer.ImportProblem, "productEvaluation"):
                    run_convert(data)

    def test_valid_model_markers_keep_models_and_false_eligibility_filters(self):
        rows = [candidate("fictional-kept"), candidate("fictional-ineligible")]
        for row in rows:
            row["product_kind"] = "model"
        rows[0]["api_model_ranking_eligible"] = True
        rows[1]["api_model_ranking_eligible"] = False
        entries = package(run_convert(overview(rows)))["data"]["entries"]
        self.assertEqual([row["model_version"] for row in entries], ["fictional-kept"])

    def test_hidden_rows_do_not_bypass_malformed_product_markers(self):
        data = overview([candidate("fictional-kept"), candidate("fictional-hidden")])
        hidden = data["snapshot"]["entries"][1]
        data["hiddenModelIds"] = [hidden["model_id"]]
        hidden["api_model_ranking_eligible"] = "false"
        with self.assertRaisesRegex(importer.ImportProblem, "api_model_ranking_eligible"):
            run_convert(data)

    def test_null_axis_is_skipped_but_zero_is_retained(self):
        rows = [candidate("fictional-a", backend=None), candidate("fictional-b", backend=0)]
        outputs = run_convert(overview(rows))
        self.assertEqual([row["score"] for row in package(outputs)["data"]["entries"]], ["0"])
        self.assertEqual(len(package(outputs, "knowledge")["data"]["entries"]), 2)
        metadata = json.loads(outputs["modeldial-import.metadata.json"])
        self.assertEqual(metadata["packages"]["backend"]["missing_axis_candidates"], [rows[0]["candidate_id"]])

    def test_final_quality_scores_are_used_without_recomputing_raw_subaxes(self):
        outputs = run_convert(overview([candidate("fictional-a", aesthetic=61, maintainability=73)]))
        self.assertEqual(package(outputs, "aesthetic")["data"]["entries"][0]["score"], "61")
        self.assertEqual(package(outputs, "maintainability")["data"]["entries"][0]["score"], "73")

    def test_attribution_is_public_and_source_dates_are_not_invented(self):
        item = package(run_convert(overview()))
        body = item["content"]["body_markdown"]
        for value in ("ModelDial", importer.RADAR_URL, "CC BY 4.0", importer.LICENSE_URL,
                      "fictional-batch-001", RECORDED, "格式转换", "未作独立实测核验"):
            self.assertIn(value, body)
        self.assertEqual(item["sources"][0]["original_published_at"], RECORDED)
        self.assertNotIn("verification_status", item["data"])
        self.assertNotIn("product_url", item["data"]["entries"][0])

    def test_same_input_has_byte_identical_drafts_and_hash_mapping(self):
        data = overview()
        outputs = run_convert(data)
        self.assertEqual(outputs, run_convert(data))
        metadata = json.loads(outputs["modeldial-import.metadata.json"])
        self.assertEqual(metadata["input_sha256"], importer.digest(raw(data)))
        self.assertEqual(metadata["source"]["publication_key"], PUBLICATION)
        record = metadata["packages"]["backend"]
        self.assertEqual(record["sha256"], importer.digest(outputs[record["file"]]))
        self.assertEqual(record["entries"][0]["quality_source"], QUALITY_SOURCE)
        self.assertIn("connection_id", record["entries"][0])

    def test_changed_input_changes_submission_and_new_batch_changes_content(self):
        data = overview()
        before = package(run_convert(data))
        data["displayNames"]["fictional-model-a-v1"] = "Fictional A"
        changed = package(run_convert(data))
        self.assertEqual(before["content_id"], changed["content_id"])
        self.assertNotEqual(before["submission_id"], changed["submission_id"])
        data["snapshot"]["batch_id"] = "fictional-batch-002"
        self.assertNotEqual(before["content_id"], package(run_convert(data))["content_id"])

    def test_duplicate_candidate_is_deduplicated_and_conflicts_fail(self):
        data = overview()
        data["snapshot"]["entries"].append(copy.deepcopy(data["snapshot"]["entries"][0]))
        self.assertEqual(len(package(run_convert(data))["data"]["entries"]), 1)
        data["snapshot"]["entries"][1]["backend_score"] = 51
        with self.assertRaisesRegex(importer.ImportProblem, "conflicting duplicate"):
            run_convert(data)

    def test_incomplete_and_inconsistent_source_metadata_fails_with_field(self):
        mutations = [
            (lambda d: d.pop("hiddenModelIds"), "hiddenModelIds"),
            (lambda d: d["snapshot"].pop("published_at"), "published_at"),
            (lambda d: d["snapshot"]["entries"][0].pop("connection_id"), "connection_id"),
            (lambda d: d["snapshot"]["entries"][0].pop("frontend_score"), "frontend_score"),
            (lambda d: d["qualityScores"].update(policy_id="future-unsupported-policy"), "policy_id"),
            (lambda d: d["qualityScores"].update(base_publication_key=QUALITY_SOURCE), "base_publication_key"),
            (lambda d: d["qualityScores"]["entries"][0]["scores"].update(aesthetic=1), "disagree"),
            (lambda d: d["qualityPublication"]["configuration_sources"].clear(), "source identity"),
            (lambda d: d["snapshot"]["entries"][0].update(reasoning_effort="unknown"), "reasoning_effort"),
        ]
        for mutation, message in mutations:
            with self.subTest(message=message):
                data = overview()
                mutation(data)
                with self.assertRaisesRegex(importer.ImportProblem, message):
                    run_convert(data)

    def test_no_scores_for_an_axis_rejects_the_complete_batch(self):
        data = overview([candidate("fictional-a", backend=None)])
        with self.assertRaisesRegex(importer.ImportProblem, "backend: expected 1..200"):
            run_convert(data)

    def test_decimal_precision_and_unsafe_json_rejection(self):
        data = overview([candidate("fictional-a", backend=88.125)])
        self.assertEqual(package(run_convert(data))["data"]["entries"][0]["score"], "88.125")
        for invalid in (True, -1, 101, "55", 0.1234567890123):
            with self.subTest(invalid=invalid):
                data = overview([candidate("fictional-a", backend=invalid)])
                with self.assertRaises(importer.ImportProblem):
                    run_convert(data)
        for invalid in (b'{"snapshot":{},"snapshot":{}}', b'{"score":NaN}', b'{}{}'):
            with self.assertRaises(importer.ImportProblem):
                importer.load_overview(invalid)

    def test_extreme_decimal_exponents_are_rejected_before_fixed_formatting(self):
        with patch("builtins.format", side_effect=AssertionError("Unsafe fixed expansion")) as formatter:
            with self.assertRaisesRegex(importer.ImportProblem, "decimal-string contract"):
                importer.score(importer.Decimal("1e-1000000000"), "fictional_score")
            formatter.assert_not_called()
        data = overview([candidate("fictional-a", backend="extreme-exponent")])
        dangerous_literal = raw(data).replace(b'"extreme-exponent"', b'1e-1000000000')
        with self.assertRaisesRegex(importer.ImportProblem, "decimal-string contract"):
            importer.convert(dangerous_literal, retrieved_at=RECORDED, generated_at=RECORDED)

    def test_trailing_decimal_zeros_are_normalized_without_losing_precision(self):
        cases = (("88.1250000000000000", "88.125"), ("100.0000000000000000", "100"),
                 ("0e-1000000000", "0"), ("1e-12", "0.000000000001"))
        for value, expected in cases:
            with self.subTest(value=value):
                self.assertEqual(importer.score(importer.Decimal(value), "fictional_score"), expected)

    def test_unsupported_numeric_exponents_in_json_are_refused(self):
        with self.assertRaisesRegex(importer.ImportProblem, "representable numbers"):
            importer.load_overview(b'{"score": 1e999999999999999999999999999999}')

    def test_input_byte_limit_is_checked_before_json_parsing(self):
        self.assertEqual(importer.MAX_INPUT_BYTES, 16 * 1024 * 1024)
        with patch.object(importer, "MAX_INPUT_BYTES", 128):
            with self.assertRaisesRegex(importer.ImportProblem, "overview limit"):
                importer.load_overview(b" " * 129)

    def test_timezone_is_required_and_invalid_calendar_date_is_refused(self):
        for invalid in ("2026-01-02", "2026-01-02T03:04:05", "2026-02-30T03:04:05Z"):
            with self.assertRaises(importer.ImportProblem):
                importer.convert(raw(overview()), retrieved_at=invalid, generated_at=RECORDED)

    def test_contract_entry_and_size_limits_are_enforced(self):
        with self.assertRaisesRegex(importer.ImportProblem, "1..200"):
            run_convert(overview([candidate(f"fictional-model-{i:03d}") for i in range(201)]))

    def test_same_identity_with_alias_candidates_cannot_hide_conflicting_scores(self):
        first = candidate("fictional-a")
        second = copy.deepcopy(first)
        second["candidate_id"] = "fictional-alias"
        second["backend_score"] = 51
        with self.assertRaisesRegex(importer.ImportProblem, "same model/connection/effort"):
            run_convert(overview([first, second]))

    def test_contract_size_limits_refuse_oversized_drafts(self):
        rows = [candidate(f"fictional-model-{i:03d}") for i in range(100)]
        for index, row in enumerate(rows):
            row["candidate_id"] = f"fictional-{index}-" + "x" * 470
        data = overview(rows)
        data["displayNames"] = {row["model_id"]: "Fictional display " + "x" * 180 for row in rows}
        with self.assertRaisesRegex(importer.ImportProblem, "exceeds"):
            run_convert(data)

    def test_unicode_source_strings_are_rejected_before_encoding(self):
        data = overview()
        data["displayNames"]["fictional-model-a-v1"] = "\ud800"
        invalid = json.dumps(data, ensure_ascii=True).encode("ascii")
        with self.assertRaisesRegex(importer.ImportProblem, "Unicode text"):
            importer.convert(invalid, retrieved_at=RECORDED, generated_at=RECORDED)

    def test_markdown_source_identity_is_escaped(self):
        item = package(run_convert(overview([candidate("fictional-[model]|<b>", connection="route|x")])) )
        self.assertIn(r"fictional-\[model\]\|\<b\>", item["content"]["body_markdown"])
        self.assertIn(r"route\|x", item["content"]["body_markdown"])


class OutputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="modeldial-fictional-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.outputs = run_convert(overview())

    def test_external_output_is_repeatable_and_does_not_clobber_changes(self):
        output = self.root / "drafts"
        self.assertEqual(importer.write_outputs(self.outputs, output), 6)
        self.assertEqual(importer.write_outputs(self.outputs, output), 0)
        (output / "modeldial-backend.draft.json").write_bytes(b"local edits")
        with self.assertRaisesRegex(importer.ImportProblem, "differs from existing"):
            importer.write_outputs(self.outputs, output)
        self.assertEqual((output / "modeldial-backend.draft.json").read_bytes(), b"local edits")

    def test_conflicting_existing_file_prevents_any_new_files(self):
        output = self.root / "drafts"
        output.mkdir()
        (output / "modeldial-backend.draft.json").write_bytes(b"local edits")
        with self.assertRaisesRegex(importer.ImportProblem, "differs from existing"):
            importer.write_outputs(self.outputs, output)
        self.assertEqual(len(list(output.iterdir())), 1)

    def test_concurrent_creation_of_different_output_is_refused(self):
        output = self.root / "drafts"
        original_open = Path.open
        target = output / "modeldial-backend.draft.json"

        def racing_open(path, mode="r", *args, **kwargs):
            if path == target and mode == "xb":
                with original_open(path, "wb") as handle:
                    handle.write(b"concurrent local edits")
            return original_open(path, mode, *args, **kwargs)

        with patch.object(Path, "open", racing_open):
            with self.assertRaisesRegex(importer.ImportProblem, "changed during generation"):
                importer.write_outputs(self.outputs, output)
        self.assertEqual(target.read_bytes(), b"concurrent local edits")

    def test_repository_output_requires_git_ignore_and_no_tracked_files(self):
        repository = self.root / "repo"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        (repository / ".gitignore").write_text("build/\n", encoding="utf-8")
        with self.assertRaisesRegex(importer.ImportProblem, "outside the repository or Git-ignored"):
            importer.write_outputs(self.outputs, repository / "drafts", repository=repository)
        self.assertFalse((repository / "drafts").exists())
        self.assertEqual(importer.write_outputs(self.outputs, repository / "build", repository=repository), 6)
        subprocess.run(["git", "-C", str(repository), "add", "--force", "build/modeldial-backend.draft.json"], check=True)
        with self.assertRaisesRegex(importer.ImportProblem, "must not be tracked"):
            importer.write_outputs(self.outputs, repository / "build", repository=repository)

    def test_cli_is_offline_and_requires_explicit_paths_and_timestamps(self):
        input_path = self.root / "fictional-overview.json"
        input_path.write_bytes(raw(overview()))
        args = ["--input", str(input_path), "--output-dir", str(self.root / "drafts"),
                "--retrieved-at", RECORDED, "--generated-at", RECORDED]
        with patch("socket.socket", side_effect=AssertionError("Network is forbidden in offline tests")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(importer.main(args), 0)
            self.assertEqual(importer.main(args), 0)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            importer.main([])
        self.assertEqual(error.exception.code, 2)

    def test_cli_rejects_oversized_input_before_output_creation(self):
        input_path = self.root / "fictional-large-overview.json"
        input_path.write_bytes(b" " * 1024)
        output = self.root / "drafts"
        errors = io.StringIO()
        with patch.object(importer, "MAX_INPUT_BYTES", 128), contextlib.redirect_stderr(errors):
            with self.assertRaises(SystemExit) as error:
                importer.main(["--input", str(input_path), "--output-dir", str(output),
                               "--retrieved-at", RECORDED, "--generated-at", RECORDED])
        self.assertEqual(error.exception.code, 2)
        self.assertIn("overview limit", errors.getvalue())
        self.assertFalse(output.exists())

    def test_bad_input_creates_no_output(self):
        input_path = self.root / "fictional-overview.json"
        input_path.write_bytes(b"{}")
        output = self.root / "drafts"
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            importer.main(["--input", str(input_path), "--output-dir", str(output),
                           "--retrieved-at", RECORDED, "--generated-at", RECORDED])
        self.assertEqual(error.exception.code, 2)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
