package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A fully validated generation. Its sanitized trees never escape the reader without a copy. */
record AutomaticBoardBatch(String revision, Instant retrievedAt, Map<String, JsonNode> boards) {
    static final String OPEN = "open-models";
    static final String DIAL = "modeldial";
    static final String OPEN_ID = "auto-open-models";
    static final String DIAL_PREFIX = "auto-modeldial-";
    static final List<String> DIAL_IDS = List.of("backend", "frontend", "knowledge", "aesthetic", "maintainability")
        .stream().map(axis -> DIAL_PREFIX + axis).toList();
    static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final String POLICY = "five-axis-b20-f25-k35-a10-m10-cube-half_up-v3";
    private static final Pattern HUB_SOURCE = Pattern.compile(
        "https://huggingface\\.co/Qwen/(Qwen3-(?:8B|14B|32B|30B-A3B)(?:-GGUF)?)/tree/[a-f0-9]{40}");
    private static final Pattern TIMESTAMP = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})");
    private static final PackageRenderer MARKDOWN = new PackageRenderer();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(42).maxStringLength(PackageCodec.MAX_BYTES).build()).build())
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static String adapterFor(String id) {
        if (OPEN_ID.equals(id)) return OPEN;
        return id != null && DIAL_IDS.contains(id) ? DIAL : null;
    }

    static AutomaticBoardBatch parse(String adapter, String revision, String json, PackageCodec codec) throws IOException {
        var batch = JSON.readTree(json);
        require(batch != null && batch.isObject() && batch.size() == 5
            && batch.path("version").isIntegralNumber() && "1".equals(batch.path("version").asText())
            && adapter.equals(batch.path("adapter").textValue())
            && SHA256.matcher(batch.path("fingerprint").asText("")).matches() && batch.path("fingerprint").isTextual(),
            "Invalid generation envelope");
        var retrieved = instant(batch.path("retrieved_at"));
        var boards = batch.path("boards");
        var expected = OPEN.equals(adapter) ? List.of(OPEN_ID) : DIAL_IDS;
        require(boards.isObject() && boards.size() == expected.size(), "Unexpected board set");
        var accepted = new LinkedHashMap<String, JsonNode>();
        JsonNode previous = null;
        for (String id : expected) {
            require(boards.has(id), "Missing board " + id);
            JsonNode board;
            try {
                board = codec.validate(boards.get(id).toString());
            } catch (PackageProblem problem) {
                throw new IllegalArgumentException("Invalid standard package for " + id, problem);
            }
            validateBoard(id, board, retrieved);
            if (previous != null) {
                require(previous.at("/data/as_of").equals(board.at("/data/as_of"))
                    && previous.at("/provenance/input_sha256").equals(board.at("/provenance/input_sha256")),
                    "Mixed source snapshots in generation");
            }
            previous = board;
            board.path("sources").forEach(source -> ((ObjectNode) source).remove("usage_note"));
            accepted.put(id, board);
        }
        return new AutomaticBoardBatch(revision, retrieved, Collections.unmodifiableMap(accepted));
    }

    private static void validateBoard(String id, JsonNode board, Instant retrieved) {
        require(id.equals(board.path("content_id").textValue()) && "leaderboard".equals(board.path("kind").textValue())
            && !ContentSection.news(board) && board.path("assets").isEmpty(), "Expected an asset-free leaderboard " + id);
        // PackageCodec already rejects unbound asset references and external images. The renderer also
        // supports a bundled sample image and video shortcodes, which have no place in a data module.
        var body = org.jsoup.Jsoup.parseBodyFragment(MARKDOWN.markdown(board.at("/content/body_markdown").asText()));
        require(body.select("img").isEmpty() && body.select("p").stream().noneMatch(p -> p.text().startsWith("[video:")),
            "Unexpected embedded media");
        var data = board.path("data");
        boolean open = OPEN_ID.equals(id);
        var task = open ? OPEN : id.endsWith("-knowledge") ? "research" : "programming";
        var benchmark = open ? "Epoch Capabilities Index" : "ModelDial/" + id.substring(DIAL_PREFIX.length()) + "/" + POLICY;
        var method = open ? "Epoch ECI / open-weights-v1.0" : POLICY + "/axis-best-v1.0";
        require(task.equals(data.path("task").textValue()) && benchmark.equals(data.path("benchmark").textValue())
            && method.equals(data.path("method_version").textValue())
            && (open ? "index" : "custom").equals(data.path("score_unit").textValue())
            && "score".equals(data.path("ranking_mode").textValue())
            && data.path("higher_is_better").asBoolean() && data.path("allow_ties").asBoolean(),
            "Unexpected scoring method or task for " + id);
        require(board.at("/provenance/input_sha256").isTextual(), "Missing source snapshot digest");
        var asOf = instant(data.path("as_of"));
        require(!open || retrieved.equals(asOf), "Unexpected Epoch cutoff time");
        var primary = open ? "epoch-eci" : "modeldial-overview";
        for (var entry : data.path("entries")) {
            require(primary.equals(entry.path("source_id").textValue()), "Unexpected score source for " + id);
            for (var hardware : entry.path("hardware")) {
                require(open && "qwen-benchmark".equals(hardware.path("source_id").textValue()), "Unexpected hardware source");
            }
        }
        for (var source : board.path("sources")) {
            var key = source.path("source_id").asText();
            var url = source.path("url").asText();
            if (key.equals(primary)) {
                var expected = open ? "https://epoch.ai/benchmarks/use-this-data" : "https://modeldial.com/data/overview.json";
                require(expected.equals(url) && "dataset".equals(source.path("material_type").asText())
                    && retrieved.equals(instant(source.path("retrieved_at")))
                    && (open || asOf.equals(instant(source.path("original_published_at")))), "Invalid primary source");
            } else {
                require(open && validSupplement(key, url) && "webpage".equals(source.path("material_type").asText()),
                    "Invalid supplementary source");
            }
        }
    }

    private static boolean validSupplement(String id, String url) {
        if ("qwen-benchmark".equals(id)) {
            return "https://qwen.readthedocs.io/en/latest/getting_started/speed_benchmark.html".equals(url);
        }
        var hub = HUB_SOURCE.matcher(url);
        return hub.matches() && id.equals("hf-" + hub.group(1).toLowerCase(java.util.Locale.ROOT));
    }

    PackageViews.PublishedContent view(String id, PackageCodec codec) {
        var board = boards.get(id);
        var content = codec.toMap(board.get("content"));
        content.put("published_at", retrievedAt.toString());
        return new PackageViews.PublishedContent(PackageViews.PublishedContent.MODULE_PREFIX + id, revision, "leaderboard",
            content, codec.toMap(board.get("data")), codec.toList(board.get("sources")), List.of(), "/aihyb/boards/" + id);
    }

    private static Instant instant(JsonNode value) {
        var text = value.asText("");
        require(TIMESTAMP.matcher(text).matches(), "Invalid timestamp in generation");
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("Invalid timestamp in generation", error);
        }
    }

    private static void require(boolean valid, String problem) {
        if (!valid) throw new IllegalArgumentException(problem);
    }
}
