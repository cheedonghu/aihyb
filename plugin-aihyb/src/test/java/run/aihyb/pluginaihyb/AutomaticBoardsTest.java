package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class AutomaticBoardsTest {
    private static final String OPEN = "auto-open-models";
    private static final String AXES = "auto-modeldial-";
    private static final String POLICY = "five-axis-b20-f25-k35-a10-m10-cube-half_up-v3";
    private static final String RETRIEVED = "2026-11-10T10:00:00+08:00";
    private static final String AS_OF = "2026-11-09T05:00:00Z";
    private static final PackageCodec CODEC = new PackageCodec();
    @TempDir Path root;

    private ObjectNode board(String id, String title) throws IOException {
        var board = (ObjectNode) CODEC.parse(Files.readString(Path.of("../contracts/examples/leaderboard.json")));
        var open = OPEN.equals(id);
        var primary = open ? "epoch-eci" : "modeldial-overview";
        board.put("content_id", id);
        ((ObjectNode) board.get("content")).put("title", title);
        var data = (ObjectNode) board.get("data");
        data.put("task", open ? "open-models" : id.endsWith("-knowledge") ? "research" : "programming")
            .put("benchmark", open ? "Epoch Capabilities Index" : "ModelDial/" + id.substring(AXES.length()) + "/" + POLICY)
            .put("method_version", open ? "Epoch ECI / open-weights-v1.0" : POLICY + "/axis-best-v1.0")
            .put("score_unit", open ? "index" : "custom").put("allow_ties", true).put("as_of", open ? RETRIEVED : AS_OF);
        data.get("entries").forEach(entry -> ((ObjectNode) entry).put("source_id", primary));
        ((ObjectNode) board.get("provenance")).put("input_sha256", "c".repeat(64));
        ((ObjectNode) board.at("/sources/0")).put("original_published_at", open ? null : AS_OF)
            .put("source_id", primary).put("retrieved_at", RETRIEVED)
            .put("url", open ? "https://epoch.ai/benchmarks/use-this-data" : "https://modeldial.com/data/overview.json")
            .put("usage_note", "private fixture note");
        return board;
    }

    private ObjectNode batch(String adapter, String title) throws IOException {
        var envelope = (ObjectNode) CODEC.tree(Map.of("version", 1, "retrieved_at", RETRIEVED, "adapter", adapter,
            "fingerprint", "a".repeat(64)));
        var boards = envelope.putObject("boards");
        if (adapter.equals("open-models")) boards.set(OPEN, board(OPEN, title));
        else for (String axis : List.of("backend", "frontend", "knowledge", "aesthetic", "maintainability")) {
            boards.set(AXES + axis, board(AXES + axis, title));
        }
        return envelope;
    }

    private Path write(String adapter, int sequence, String json) throws IOException {
        var dir = Files.createDirectories(root.resolve(adapter));
        return Files.writeString(dir.resolve("%020d-%s.json".formatted(sequence, PackageCodec.hash(json))), json);
    }

    private AutomaticBoards reader() {
        return new AutomaticBoards(root, CODEC, java.time.Duration.ZERO);
    }

    @Test void exactNamespaceAndAbsentDirectoryAreEmpty() {
        var disabled = new AutomaticBoards(CODEC, "  ");
        assertEquals(List.of(), disabled.published().block());
        assertNull(disabled.payload(OPEN).block());
        var missing = reader();
        assertEquals(List.of(), missing.published().block());
        assertNull(missing.byId(AXES + "backend").block());
        for (String id : new String[]{null, "", "auto-modeldial", "auto-modeldial-overall", "../" + OPEN, OPEN + "-test"}) {
            assertFalse(AutomaticBoards.owns(id));
            assertNull(missing.payload(id).block());
        }
        assertTrue(AutomaticBoards.owns(OPEN));
        assertTrue(AutomaticBoards.owns(AXES + "maintainability"));
    }

    @Test void listAndDetailShareAPublicProjectionAndCannotMutateTheCache() throws Exception {
        var dial = batch("modeldial", "five axes");
        var open = batch("open-models", "open weights");
        write("modeldial", 9, dial.toString());
        write("open-models", 1, open.toString());
        var reader = reader();
        var listed = reader.published().block();
        assertEquals(6, listed.size());
        for (var view : listed) {
            assertTrue(view.content_id().startsWith("module:"));
            var id = view.content_id().substring("module:".length());
            assertEquals(view, reader.byId(id).block());
            assertEquals("/aihyb/boards/" + id, view.url());
            assertEquals(Instant.parse("2026-11-10T02:00:00Z"), view.publicationTime());
            assertEquals(OPEN.equals(id) ? RETRIEVED : AS_OF, view.data().get("as_of"));
            assertEquals(List.of(), view.assets());
            assertFalse(view.sources().getFirst().containsKey("usage_note"));
            assertEquals(2, ((List<?>) view.data().get("entries")).size());
        }
        var detail = reader.payload(OPEN).block();
        assertFalse(detail.at("/sources/0").has("usage_note"));
        ((ObjectNode) detail.path("content")).put("title", "mutation");
        reader.byId(OPEN).block().content().put("title", "another mutation");
        assertEquals("open weights", reader.payload(OPEN).block().at("/content/title").asText());
    }

    @Test void newestValidSequenceWinsAndTruncationFallsBackEvenOnColdStart() throws Exception {
        var first = batch("modeldial", "first");
        write("modeldial", 1, first.toString());
        var second = batch("modeldial", "second");
        var reader = reader();
        assertAllFrom(reader, "first");
        var file = write("modeldial", 10, second.toString());
        assertAllFrom(reader, "second");
        Files.writeString(file, "{\"version\":");
        assertAllFrom(reader, "first");
        assertAllFrom(reader(), "first");
        Files.writeString(file, second.toString());
        assertAllFrom(reader, "second");
        write("modeldial", 10, batch("modeldial", "conflicting sequence").toString());
        assertAllFrom(reader, "first");
    }

    private void assertAllFrom(AutomaticBoards reader, String title) {
        var views = reader.published().block();
        assertEquals(5, views.size());
        assertEquals(List.of(title), views.stream().map(v -> v.content().get("title")).distinct().toList());
        assertEquals(1, views.stream().map(PackageViews.PublishedContent::revision).distinct().count());
    }

    @Test void mixedIncompleteOrMisidentifiedAxesInvalidateTheWholeBatch() throws Exception {
        var valid = batch("modeldial", "old");
        write("modeldial", 1, valid.toString());
        int seq = 1;
        var mutations = List.<Consumer<ObjectNode>>of(
            boards -> boards.remove(AXES + "knowledge"),
            boards -> boards.set("extra", boards.get(AXES + "backend")),
            boards -> ((ObjectNode) boards.get(AXES + "frontend")).put("content_id", AXES + "backend"),
            boards -> ((ObjectNode) boards.get(AXES + "aesthetic").get("data")).put("as_of", RETRIEVED),
            boards -> ((ObjectNode) boards.get(AXES + "frontend").get("provenance")).put("input_sha256", "d".repeat(64)),
            boards -> ((ObjectNode) boards.get(AXES + "frontend").get("provenance")).putNull("input_sha256"),
            boards -> ((ObjectNode) boards.get(AXES + "knowledge").at("/sources/0")).put("retrieved_at", AS_OF),
            boards -> ((ObjectNode) boards.get(AXES + "backend").get("data")).put("method_version", "mixed"),
            boards -> ((ObjectNode) boards.get(AXES + "knowledge").get("data")).put("task", "programming"),
            boards -> ((ObjectNode) boards.get(AXES + "backend").get("data")).put("benchmark", "ModelDial/frontend/" + POLICY),
            boards -> ((ObjectNode) boards.get(AXES + "backend").at("/sources/0")).put("url", "https://example.com/other")
        );
        var reader = reader();
        for (var change : mutations) {
            var bad = batch("modeldial", "invalid");
            change.accept((ObjectNode) bad.get("boards"));
            write("modeldial", ++seq, bad.toString());
            assertAllFrom(reader, "old");
        }
        assertEquals("old", reader().payload(AXES + "backend").block().at("/content/title").asText());
    }

    @Test void invalidEnvelopeSchemaMediaPlacementAndFilesRemainIsolated() throws Exception {
        var valid = batch("open-models", "visible");
        var other = batch("modeldial", "intact");
        write("modeldial", 1, other.toString());
        write("open-models", 1, valid.toString());
        int seq = 1;
        var reader = reader();
        var mutations = List.<Consumer<ObjectNode>>of(
            b -> b.put("version", "1"), b -> b.put("version", 2), b -> b.put("retrieved_at", "2026-10-09"),
            b -> b.put("fingerprint", "Z".repeat(64)), b -> b.put("adapter", "modeldial"),
            b -> open(b).put("kind", "pricing"),
            b -> ((ObjectNode) open(b).get("content")).put("section", "news").put("news_date", "2026-11-10"),
            b -> ((ArrayNode) open(b).get("assets")).addObject().put("asset_id", "image").put("object_key", "a/b.png")
                .put("content_type", "image/png").put("sha256", "a".repeat(64)).put("alt", "image").put("visibility", "private"),
            b -> ((ObjectNode) open(b).get("content")).put("body_markdown", "[vi**de**o:youtube:12345678901]"),
            b -> ((ObjectNode) open(b).get("content")).put("body_markdown", "![demo][fixture]\n\n[fixture]: /aihyb/poc-sample&#46;svg"),
            b -> ((ObjectNode) open(b).at("/data/entries/0")).put("rank", 0),
            b -> ((ObjectNode) open(b).get("data")).put("ranking_mode", "source_rank"),
            b -> ((ObjectNode) open(b).at("/sources/0")).put("url", "https://epoch.ai.example.com/x")
        );
        for (var change : mutations) {
            var bad = batch("open-models", "unacceptable");
            change.accept(bad);
            write("open-models", ++seq, bad.toString());
            assertEquals("visible", reader.byId(OPEN).block().content().get("title"));
        }
        var badText = batch("open-models", "invalid").toString();
        write("open-models", ++seq, badText + " {}");
        write("open-models", ++seq, badText.replaceFirst("\\{", "{\"version\":1,"));
        write("open-models", ++seq, badText + " ".repeat(AutomaticBoards.MAX_BATCH_BYTES));
        var dir = root.resolve("open-models");
        Files.writeString(dir.resolve("9-" + PackageCodec.hash(badText) + ".json"), badText);
        Files.writeString(dir.resolve("%020d-%s.json".formatted(++seq, "0".repeat(64))), badText);
        assertEquals("visible", reader().byId(OPEN).block().content().get("title"));
        assertEquals(6, reader.published().block().size());
        assertEquals("intact", reader.byId(AXES + "aesthetic").block().content().get("title"));
    }

    private ObjectNode open(ObjectNode batch) {
        return (ObjectNode) batch.get("boards").get(OPEN);
    }

    @Test void repeatedRequestsReuseValidatedBodiesOffTheEventThread() throws Exception {
        var count = new AtomicInteger();
        var instrumented = new PackageCodec() {
            @Override public JsonNode validate(String raw) {
                assertFalse(Schedulers.isInNonBlockingThread());
                count.incrementAndGet();
                return super.validate(raw);
            }
        };
        var reader = new AutomaticBoards(root, instrumented, java.time.Duration.ZERO);
        var request = reader.byId(OPEN);
        write("open-models", 1, batch("open-models", "cached").toString());
        var invalid = batch("open-models", "invalid");
        ((ObjectNode) open(invalid).get("data")).put("task", "research");
        write("open-models", 2, invalid.toString());
        assertEquals("cached", Mono.defer(() -> request).subscribeOn(Schedulers.parallel()).block().content().get("title"));
        assertEquals(2, count.get());
        for (int i = 0; i < 3; i++) {
            assertEquals(1, reader.published().block().size());
            assertNotNull(reader.payload(OPEN).block());
        }
        assertEquals(2, count.get());
    }

    @Test void refreshWindowServesOneSnapshotAcrossRequests() throws Exception {
        var reader = new AutomaticBoards(root, CODEC, java.time.Duration.ofHours(1));
        write("open-models", 1, batch("open-models", "cached").toString());
        assertEquals("cached", reader.byId(OPEN).block().content().get("title"));
        write("open-models", 2, batch("open-models", "newer").toString());
        assertEquals("cached", reader.payload(OPEN).block().at("/content/title").asText());
        assertEquals("newer", reader().published().block().getFirst().content().get("title"));
    }

    @Test void temporaryScanFailureKeepsKnownDataButRemovalAndEmptyDirectoryWithdrawIt() throws Exception {
        var file = write("open-models", 1, batch("open-models", "cached").toString());
        var reader = reader();
        assertNotNull(reader.byId(OPEN).block());
        var dir = file.getParent();
        var saved = Files.move(dir, root.resolve("original-directory"));
        Files.createFile(dir);
        assertNotNull(reader.byId(OPEN).block());
        Files.delete(dir);
        assertNull(reader.byId(OPEN).block());
        Files.move(saved, dir);
        assertNotNull(reader.byId(OPEN).block());
        Files.delete(file);
        assertNull(reader.byId(OPEN).block());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void invalidCalendarDateIsNegativelyCached(org.springframework.boot.test.system.CapturedOutput logs) throws Exception {
        var invalid = batch("open-models", "invalid").put("retrieved_at", "2026-02-30T00:00:00Z");
        write("open-models", 1, invalid.toString());
        var reader = reader();
        assertNull(reader.payload(OPEN).block());
        assertNull(reader.payload(OPEN).block());
        assertEquals(1, logs.getAll().lines().filter(line -> line.contains("Invalid timestamp in generation")).count());
    }

    @Test void doesNotFollowFileOrDirectorySymlinks() throws Exception {
        var other = Files.createDirectories(root.resolve("outside"));
        var dir = Files.createDirectories(root.resolve("open-models"));
        var valid = batch("open-models", "linked").toString();
        var name = "%020d-%s.json".formatted(99, PackageCodec.hash(valid));
        var target = Files.writeString(other.resolve(name), valid, StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(dir.resolve(name), target);
        } catch (IOException | UnsupportedOperationException failure) {
            Assumptions.abort("Symbolic links unavailable on test filesystem: " + failure.getClass().getSimpleName());
        }
        assertNull(reader().byId(OPEN).block());
        var hidden = Files.createDirectories(root.resolve("nested"));
        Files.createSymbolicLink(hidden.resolve("open-models"), other);
        assertNull(new AutomaticBoards(hidden, CODEC).byId(OPEN).block());
    }
}
