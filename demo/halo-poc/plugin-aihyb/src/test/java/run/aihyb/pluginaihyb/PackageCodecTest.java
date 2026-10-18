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
        return Files.readString(Path.of("../package-fixtures/" + kind + ".json"));
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
