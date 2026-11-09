package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class LeaderboardRendererTest {
    final PackageCodec codec = new PackageCodec();
    final PortalRenderer renderer = new PortalRenderer(codec);

    ObjectNode fixture(String id, String title, String author) throws Exception {
        var root = (ObjectNode) codec.parse(Files.readString(Path.of("../contracts/examples/leaderboard.json")));
        root.put("content_id", id);
        ((ObjectNode) root.path("content")).put("title", title).put("summary", "虚构布局验证数据，不代表真实模型成绩或推荐。");
        ((ObjectNode) root.path("data")).put("task", "programming").put("score_unit", "custom");
        ((ObjectNode) root.path("sources").get(0)).put("author", author);
        return root;
    }

    PackageViews.PublishedContent published(ObjectNode root) {
        return new PackageViews.PublishedContent(root.path("content_id").asText(), "revision-public", "leaderboard",
            codec.toMap(root.path("content")), codec.toMap(root.path("data")), codec.toList(root.path("sources")), List.of());
    }

    @Test void sourceSnapshotsRemainSeparateAndReadableWithoutJavascript() throws Exception {
        var first = fixture("source-a", "虚构：代码质量榜", "演示来源甲");
        var second = fixture("source-b", "虚构：交互能力榜", "演示来源乙");
        var doc = Jsoup.parse(LeaderboardRenderer.render(List.of(published(first), published(second)), codec));
        assertEquals(2, doc.select("[data-ranking-board]").size());
        assertEquals(2, doc.select("[data-ranking-tab]").size());
        assertEquals(4, doc.select("[data-ranking-row]").size());
        assertTrue(doc.select("[data-ranking-board][hidden]").isEmpty());
        assertTrue(doc.selectFirst("[data-ranking-controls]").hasAttr("hidden"));
        assertEquals(3, doc.select("[data-ranking-source] option").size());
        assertTrue(doc.selectFirst("#ranking-source-a").text().contains("演示来源甲"));
        assertFalse(doc.selectFirst("#ranking-source-a").text().contains("演示来源乙"));
        assertEquals("85.5", doc.selectFirst("#ranking-source-a .ranking-score-cell strong").text());
        assertTrue(doc.select("[data-ranking-row] details summary").size() == 4);
        assertFalse(doc.select("script,iframe").size() > 0);
    }

    @Test void multipleSourcesAreSeparateFilterOptionsAndRemainOnTheSameSnapshot() throws Exception {
        var root = fixture("mixed", "虚构：多来源榜单", "演示来源甲");
        var source = ((ObjectNode) root.path("sources").get(0)).deepCopy();
        source.put("source_id", "source-second").put("author", "演示来源乙");
        root.withArray("sources").add(source);
        var doc = Jsoup.parse(LeaderboardRenderer.render(List.of(published(root)), codec));
        assertEquals(List.of("", "演示来源乙", "演示来源甲"), doc.select("[data-ranking-source] option").eachAttr("value"));
        var values = codec.parse(doc.selectFirst("[data-ranking-board]").attr("data-sources"));
        assertEquals(2, values.size());
        assertTrue(doc.selectFirst(".ranking-facts").text().contains("演示来源甲 / 演示来源乙"));
    }

    @Test void editorialOverallKeepsItsRanksAndUnknownScores() throws Exception {
        var root = fixture("editorial", "虚构：综合榜", "本站编辑");
        var data = (ObjectNode) root.path("data");
        data.put("task", "overall").put("ranking_mode", "editorial_order");
        ((ObjectNode) data.path("entries").get(0)).putNull("score");
        ((ObjectNode) data.path("entries").get(1)).put("score", "99");
        codec.validate(codec.canonical(root));
        var doc = Jsoup.parse(LeaderboardRenderer.render(List.of(published(root)), codec));
        assertEquals("未提供", doc.selectFirst(".ranking-score-cell strong").text());
        assertTrue(doc.select(".ranking-score-bar").isEmpty());
        assertEquals("1", doc.selectFirst(".ranking-position").text());
        assertTrue(doc.text().contains("参考成绩不用于自动合成总分"));
    }

    @Test void unsafeTextAndMissingScoresDoNotCreateMarkupOrSyntheticResults() throws Exception {
        var root = fixture("escaped", "虚构 <img src=x onerror=alert(1)>", "来源 <script>alert(1)</script>");
        var first = (ObjectNode) root.path("data").path("entries").get(0);
        first.put("tool_name", "模型 <script>alert(1)</script>").put("reason", "依据 <img src=x>").putNull("score");
        first.put("product_url", "javascript:alert(1)");
        var doc = Jsoup.parse(LeaderboardRenderer.render(List.of(published(root)), codec));
        assertTrue(doc.select("script,img,[onerror],a[href^=javascript]").isEmpty());
        assertTrue(doc.text().contains("模型 <script>alert(1)</script>"));
        assertEquals("未提供", doc.selectFirst(".ranking-score-cell strong").text());
        assertTrue(doc.selectFirst("[data-ranking-row]").select(".ranking-score-bar").isEmpty());
    }

    @Test void leaderboardCatalogRetainsPlainEditorialArticles() throws Exception {
        var root = fixture("board", "虚构榜单", "演示来源");
        var article = new PackageViews.PublishedContent("native-article", "revision", "leaderboard",
            java.util.Map.of("title", "榜单解读原文", "summary", "普通文章保持原链接"), java.util.Map.of(), List.of(), List.of(), "/archives/native-article");
        var doc = Jsoup.parse(renderer.listing("leaderboard", new PackageViews.Page<>(List.of(published(root), article), 2, 1, 20), null));
        assertEquals(1, doc.select("[data-ranking-board]").size());
        assertEquals(1, doc.select(".ranking-reading a[href='/archives/native-article']").size());
        assertTrue(doc.select(".portal-board-list").isEmpty());
    }

    @Test void exportFictionalRankingPreview() throws Exception {
        var labels = List.of("后端工程", "前端交互", "知识推理", "视觉表现", "代码可维护性");
        var boards = new java.util.ArrayList<PackageViews.PublishedContent>();
        for (int axis = 0; axis < labels.size(); axis++) {
            var root = fixture("preview-" + axis, "虚构演示 · " + labels.get(axis) + "榜", axis == 4 ? "演示来源乙" : "演示来源甲");
            var data = (ObjectNode) root.path("data");
            data.put("benchmark", "fictional-" + axis).put("method_version", "fictional-v1");
            var template = data.path("entries").get(0).deepCopy();
            var entries = data.putArray("entries");
            for (int model = 0; model < 12; model++) {
                var row = (ObjectNode) template.deepCopy();
                row.put("entry_id", "model-" + model).put("tool_id", "fictional-" + model)
                    .put("tool_name", "演示模型 " + (char) ('A' + model)).put("model_version", "虚构版本 v1 · " + (model % 2 == 0 ? "high" : "default"))
                    .put("score", String.valueOf(92 - model * 3 - axis)).put("rank", model + 1)
                    .put("reason", "仅用于检查榜单布局和筛选交互。这些成绩完全虚构，不可作为产品选型依据。");
                entries.add(row);
            }
            codec.validate(codec.canonical(root));
            boards.add(published(root));
        }
        var output = Path.of("build/reports/leaderboard"); Files.createDirectories(output);
        var css = Path.of("src/main/resources/theme-assets/portal.css").toAbsolutePath().toUri();
        var js = Path.of("src/main/resources/theme-assets/portal.js").toAbsolutePath().toUri();
        var fragment = renderer.listing("leaderboard", new PackageViews.Page<>(boards, boards.size(), 1, 20), null);
        Files.writeString(output.resolve("preview.html"), "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>AI好用榜 · 评分与榜单虚构预览</title><link rel='stylesheet' href='" + css + "'><script defer src='" + js + "'></script></head><body class='portal-native-layout'><header class='preview-note'>仅供布局验证 · 全部模型和成绩为虚构数据</header><main id='aihyb-portal'>" + fragment + "</main></body></html>");
    }
}
