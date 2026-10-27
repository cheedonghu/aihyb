package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class ModuleBodyRendererTest {
    final PackageCodec codec = new PackageCodec();
    final PackageRenderer renderer = new PackageRenderer();
    ObjectNode fixture(String kind) throws Exception {
        return (ObjectNode) codec.validate(Files.readString(Path.of("../contracts/examples/" + kind + ".json")));
    }
    org.jsoup.nodes.Document render(ObjectNode payload) {
        return Jsoup.parseBodyFragment(renderer.nativeBody(payload,
            renderer.markdown(payload.path("content").path("body_markdown").asText()), "published-a"));
    }
    @Test void allKindsShareOneReportWithoutDuplicateTitleAndHaveValidSectionLinks() throws Exception {
        for (var kind : List.of("review", "leaderboard", "pricing", "tutorial")) {
            var document = render(fixture(kind));
            assertEquals(1, document.select(".aihyb-content-report[data-report-kind=" + kind + "]").size());
            assertTrue(document.select("h1").isEmpty());
            assertEquals(4, document.select(".report-facts dd").size());
            assertEquals(1, document.select(".report-source-card").size());
            for (var link : document.select("a[href^='#report-']")) {
                assertNotNull(document.getElementById(link.attr("href").substring(1)), link.outerHtml());
            }
        }
    }
    @Test void leaderboardKeepsRawScoresDirectionMethodAndPerEntrySource() throws Exception {
        var payload = fixture("leaderboard"); var before = codec.canonical(payload);
        var document = render(payload);
        assertEquals(List.of("85.5", "79.0"), document.select(".report-raw-score").eachText());
        assertEquals(List.of("1", "2"), document.select(".report-rank-number").eachText());
        assertTrue(document.text().contains("poc-image-only"));
        assertTrue(document.text().contains("成绩越高越好"));
        assertEquals(2, document.select(".report-rank-card a[href='#report-source-source-poc']").size());
        assertEquals(before, codec.canonical(payload));
    }
    @Test void editorialOrderWithMissingScoresDoesNotBecomeAnInventedScoreOrWinner() throws Exception {
        var payload = fixture("leaderboard"); var data = (ObjectNode) payload.get("data");
        data.put("ranking_mode", "editorial_order"); data.put("higher_is_better", false);
        for (var entry : data.path("entries")) { ((ObjectNode) entry).putNull("score"); }
        codec.validate(codec.canonical(payload));
        var document = render(payload);
        assertEquals(List.of("未提供", "未提供"), document.select(".report-raw-score").eachText());
        assertTrue(document.text().contains("编辑排序"));
        assertTrue(document.text().contains("成绩越低越好"));
        assertFalse(document.text().contains("按原始成绩排名"));
    }
    @Test void pricingRetainsEveryOfferAndAnnualChargeAndUnknownQuotaWithoutObjectSelection() throws Exception {
        var payload = fixture("pricing");
        ((ObjectNode) payload.path("data").path("plans").get(1)).put("currency", "USD");
        codec.validate(codec.canonical(payload));
        var document = render(payload);
        assertEquals(2, document.select(".report-plan-card").size());
        assertEquals(2, document.select(".pricing-table tbody tr").size());
        assertEquals(2, document.select(".pricing-table").size());
        assertTrue(document.select(".report-plan-price").eachText().contains("468.00 USD / 年"));
        assertTrue(document.text().contains("未知 次任务"));
        assertEquals(List.of("状态待核验", "状态待核验"), document.select(".report-plan-state").eachText());
        assertTrue(document.text().contains("同任务使用记录待补充"));
        assertTrue(document.select("input[type=checkbox]").isEmpty());
    }
    @Test void usageAndTutorialStepsRemainOriginalAndVerificationIsNotPromoted() throws Exception {
        var pricing = fixture("pricing-ui-a");
        assertEquals("12%", render(pricing).selectFirst(".usage-percent").text());
        var tutorial = fixture("tutorial");
        var document = render(tutorial);
        assertEquals(List.of("上传样例。", "复制提示词。"), document.select(".report-step-list>li").eachText());
        assertTrue(document.selectFirst(".report-status").text().contains("待复现"));
        assertEquals("请列出空值，不要修改原数据。", document.selectFirst("pre code").text());
        ((ObjectNode) tutorial.path("data")).put("verification_status", "source_only");
        document = render(tutorial);
        assertTrue(document.selectFirst(".report-verification-note").text().contains("仅来源材料"));
        assertFalse(document.selectFirst(".report-verification-note").text().contains("已复现"));
    }
    @Test void everyKindKeepsPrivateAssetsAndSourceNotesPrivateAndEscapesStructuredText() throws Exception {
        for (var kind : List.of("review", "leaderboard", "pricing", "tutorial")) {
            var payload = fixture(kind);
            ((ObjectNode) payload.path("sources").get(0)).put("usage_note", "PRIVATE_SOURCE_NOTE").put("author", "<script>alert(1)</script>");
            var asset = payload.withArray("assets").addObject();
            asset.put("asset_id", "private-image").put("visibility", "private").put("content_type", "image/png").put("alt", "PRIVATE_FILENAME");
            var html = renderer.nativeBody(payload, "<p><img src='/aihyb-asset/private-image'></p>", "published-a");
            assertFalse(html.contains("PRIVATE_SOURCE_NOTE"));
            assertFalse(html.contains("PRIVATE_FILENAME"));
            assertFalse(html.contains("/file/published-a/private-image"));
            assertFalse(html.contains("<script>"));
            assertTrue(html.contains("&lt;script&gt;"));
        }
    }
}
