package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PackageCodecTest {
    final PackageCodec codec = new PackageCodec();
    final PackageRenderer renderer = new PackageRenderer();

    String fixture(String kind) throws Exception {
        return Files.readString(Path.of("../contracts/examples/" + kind + ".json"));
    }

    @Test void allFourKindsAndCanonicalReplay() throws Exception {
        for (var kind : PackageCodec.KINDS) {
            var data = codec.validate(fixture(kind));
            assertEquals(kind, data.get("kind").asText());
            assertEquals(codec.canonical(data), codec.canonical(codec.validate(codec.canonical(data))));
        }
    }

    @Test void rejectTrailingJsonDuplicateKeysAndEmptyInput() throws Exception {
        assertThrows(PackageProblem.class, () -> codec.validate(fixture("review") + " {}"));
        assertThrows(PackageProblem.class, () -> codec.validate("{\"kind\":\"review\",\"kind\":\"tutorial\"}"));
        assertThrows(PackageProblem.class, () -> codec.validate("   "));
    }

    @Test void rejectRankOverflowAndWrongScoreOrdering() throws Exception {
        JsonNode data = codec.parse(fixture("leaderboard"));
        ((ObjectNode) data.get("data").get("entries").get(0)).put("rank", 4294967297L);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(data)));
        var ordered = codec.parse(fixture("leaderboard"));
        ((ObjectNode) ordered.get("data").get("entries").get(0)).put("score", "1");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(ordered)));
    }

    @Test void rejectBadSourcesCycleAndExtraPublishFlag() throws Exception {
        var review = codec.parse(fixture("review"));
        ((ObjectNode) review.get("data").get("conclusions").get(0)).put("source_id", "missing");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(review)));
        var price = codec.parse(fixture("pricing"));
        ((ObjectNode) price.get("data").get("plans").get(0)).put("billing_cycle", "annual");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(price)));
        var extra = (ObjectNode) codec.parse(fixture("review"));
        extra.put("published", true);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(extra)));
    }

    @Test void openModelRecordsRequireTypedFieldsAndExistingSources() throws Exception {
        var root = codec.validate(fixture("leaderboard-open-models"));
        var entry = (ObjectNode) root.at("/data/entries/1");
        var record = (ObjectNode) entry.at("/hardware/0");
        var keys = new java.util.ArrayList<String>();
        record.fieldNames().forEachRemaining(keys::add);
        for (var key : keys) {
            var original = record.remove(key);
            assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)), key);
            record.set(key, original);
        }
        for (var key : java.util.List.of("gpu_count", "input_tokens", "output_tokens", "batch_size", "memory_mb")) {
            var original = record.get(key);
            record.put(key, 0);
            assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)), key);
            record.set(key, original);
        }
        record.put("gpu_count", 1.5);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        record.put("gpu_count", 1).put("memory_mb", "8000");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        record.put("memory_mb", 8000.1).put("evidence", "verified");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        record.put("evidence", "vendor").put("source_id", "unknown-hardware-source");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        record.put("source_id", "measurements").put("extra", true);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        record.remove("extra");
        record.put("runtime", " ");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        record.put("runtime", "example");
        entry.put("weights_bytes", 0);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        entry.put("weights_bytes", 9007199254740992L);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        entry.remove("weights_bytes");
        entry.put("license", "  ");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(root)));
        entry.remove("license");
        entry.putArray("hardware");
        assertDoesNotThrow(() -> codec.validate(codec.canonical(root)));
    }

    @Test void unknownQuotaRemainsUnknownAndMarkdownCannotExecuteHtml() throws Exception {
        var price = codec.validate(fixture("pricing"));
        assertTrue(price.get("data").get("plans").get(1).get("free_quota").get("amount").isNull());
        var output = renderer.markdown("<script>alert(1)</script>\n\n[bad](javascript:alert(1))\n\n|A|B|\n|---|---|\n|1|2|");
        assertFalse(output.contains("<script"));
        assertFalse(output.contains("href=\"javascript:"));
        assertTrue(output.contains("<table>"));
        var converted = codec.serialize(codec.toMap(price.get("data")));
        assertTrue(converted.contains("\"amount\":null"));
    }
}
