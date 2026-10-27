package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class PortalRendererTest {
    final PackageCodec codec = new PackageCodec();
    final PortalRenderer renderer = new PortalRenderer(codec);
    String fixture(String kind) throws Exception { return Files.readString(Path.of("../contracts/examples/" + kind + ".json")); }
    PackageViews.PublishedContent published(String kind) throws Exception { return published(codec.validate(fixture(kind))); }
    PackageViews.PublishedContent published(com.fasterxml.jackson.databind.JsonNode node) {
        return new PackageViews.PublishedContent(node.path("content_id").asText(), "revision-public", node.path("kind").asText(),
            codec.toMap(node.get("content")), codec.toMap(node.get("data")), codec.toList(node.get("sources")), List.of());
    }

    @Test void everyPublishedPlanIsVisibleWithoutSelectingObjects() throws Exception {
        var one = published("pricing");
        var two = usageFixture("b", 28);
        var document = Jsoup.parse(PricingRenderer.render(PricingRenderer.records(List.of(one, published(two))), false));
        assertEquals(5, document.select(".pricing-table tbody tr").size());
        assertTrue(document.select("input[type=checkbox]").isEmpty());
        assertTrue(document.text().contains("额度 / 重置方式"));
        assertTrue(document.text().contains("中国大陆"));
        assertTrue(document.text().contains("468.00 CNY / 年"));
        assertTrue(document.text().contains("未知"));
        assertEquals("28%", document.selectFirst(".usage-percent").text());
        assertTrue(document.text().contains("5 小时额度池"));
    }

    @Test void missingObservationsRemainExplicitlyUnknownAndTextIsEscaped() throws Exception {
        var node = (ObjectNode) codec.parse(fixture("pricing"));
        ((ObjectNode) node.get("data")).put("tool_name", "<script>alert(1)</script>");
        var html = PricingRenderer.render(PricingRenderer.records(List.of(published(node))), false);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("同任务使用记录待补充"));
        assertFalse(html.contains("usage-percent"));
        assertTrue(html.contains("方案额度重置待补充"));
    }

    @Test void taskUsageRequiresExistingPlanSourceAndValidPercentage() throws Exception {
        var valid = usageFixture("a", 12);
        codec.validate(codec.canonical(valid));
        var usage = (ObjectNode) valid.get("data").get("task_usage").get(0);
        usage.put("consumed_percent", 100.01);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(valid)));
        usage.put("consumed_percent", -1);
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(valid)));
        usage.put("consumed_percent", 12);
        usage.put("plan_id", "missing");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(valid)));
        usage.put("plan_id", "office-a-month");
        usage.put("source_id", "missing");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(valid)));
    }

    @Test void validUppercaseHttpSourceKeepsItsClickableLink() throws Exception {
        var node = (ObjectNode) codec.parse(fixture("pricing"));
        ((ObjectNode) node.get("sources").get(0)).put("url", "HTTPS://example.com/source");
        codec.validate(codec.canonical(node));
        var html = Jsoup.parse(PricingRenderer.render(PricingRenderer.records(List.of(published(node))), false));
        assertFalse(html.select("a[href='HTTPS://example.com/source']").isEmpty());
    }

    @Test void differentConditionsAndCurrenciesNeverBecomeOneComparableGroup() throws Exception {
        var first = usageFixture("a", 12); var second = usageFixture("b", 28);
        ((ObjectNode) second.get("data").get("task_usage").get(0)).put("conditions", "Different document and prompt");
        ((ObjectNode) second.get("data").get("plans").get(0)).put("currency", "USD");
        var document = Jsoup.parse(PricingRenderer.render(PricingRenderer.records(List.of(published(first), published(second))), false));
        assertEquals(2, document.select(".task-condition").size());
        assertEquals(2, document.select(".pricing-table").size());
        assertTrue(document.text().contains("不能只凭百分比"));
    }

    @Test void catalogRetainsPaginationAndHomeContainsAllFourModules() throws Exception {
        var contents = new ArrayList<PackageViews.PublishedContent>();
        for (var kind : List.of("review", "leaderboard", "pricing", "tutorial")) { contents.add(published(kind)); }
        var home = Jsoup.parse(renderer.home(contents));
        assertEquals(4, home.select(".module-item").size());
        assertEquals(1, home.select("h1").size());
        var listing = Jsoup.parse(renderer.listing("tutorial", new PackageViews.Page<>(List.of(published("tutorial")), 3, 1, 1), "spreadsheet"));
        assertTrue(listing.select(".portal-pagination a[href*=page=2]").first().attr("href").contains("task=spreadsheet"));
        assertTrue(listing.text().contains("待复现核验"));
    }

    @Test void featuredStoryUsesFirstPublishedImageUrlAndEscapesItsAlt() throws Exception {
        var node = (ObjectNode) codec.validate(fixture("review"));
        var asset = node.withArray("assets").addObject();
        asset.put("asset_id", "cover").put("content_type", "image/png").put("visibility", "public");
        var url = PackageRenderer.mediaUrl(node, asset, false, "revision-public");
        var alt = "原图 '单引号' \"双引号\" <script> & 细节";
        var item = published(node);
        var withAssets = new PackageViews.PublishedContent(item.content_id(), item.revision(), item.kind(),
            item.content(), item.data(), item.sources(), List.of(
                java.util.Map.<String, Object>of("content_type", "application/pdf", "url", "/attachment", "alt", "附件"),
                java.util.Map.<String, Object>of("content_type", "image/png", "url", url, "alt", alt),
                java.util.Map.<String, Object>of("content_type", "image/jpeg", "url", "/second-image", "alt", "下一张")));
        var html = renderer.home(List.of(withAssets));
        var document = Jsoup.parseBodyFragment(html);
        var cover = document.selectFirst(".featured-art>img");
        assertNotNull(cover);
        assertTrue(cover.parent().hasClass("portal-featured-media"));
        assertTrue(cover.closest(".featured-story").hasClass("portal-featured-story"));
        assertEquals(url, cover.attr("src"));
        assertEquals(alt, cover.attr("alt"));
        assertEquals(2, cover.attributes().size());
        assertTrue(html.contains("alt='" + PackageRenderer.escape(alt) + "'"));
        assertTrue(document.select(".featured-art .portal-hero-paper,.featured-art .art-note,.featured-art script").isEmpty());
        assertEquals("测评精选 能力测评", document.selectFirst(".featured-art .issue-label").text());
    }

    @Test void featuredStoryWithoutImageKeepsClearlyLabelledFallback() throws Exception {
        var item = published("review");
        var withAttachment = new PackageViews.PublishedContent(item.content_id(), item.revision(), item.kind(),
            item.content(), item.data(), item.sources(), List.of(
                java.util.Map.<String, Object>of("content_type", "application/pdf", "url", "/attachment", "alt", "附件")));
        for (var review : List.of(item, withAttachment)) {
            var document = Jsoup.parseBodyFragment(renderer.home(List.of(review)));
            assertTrue(document.select(".featured-art img,.portal-featured-story").isEmpty());
            assertEquals(1, document.select(".featured-art .portal-hero-paper").size());
            assertEquals("专题排版示意", document.selectFirst(".featured-art .art-note").text());
        }
    }

    @Test void exportRepresentativeLocalPreview() throws Exception {
        var contents = new ArrayList<PackageViews.PublishedContent>();
        for (var kind : List.of("review", "leaderboard", "tutorial")) { contents.add(published(kind)); }
        for (var suffix : List.of("a", "b")) { contents.add(published(usageFixture(suffix, suffix.equals("a") ? 12 : 28))); }
        var output = Path.of("build/portal-preview"); Files.createDirectories(output);
        Files.writeString(output.resolve("pricing-fragment.html"), renderer.listing("pricing", new PackageViews.Page<>(contents, contents.size(), 1, 100), null));
        Files.writeString(output.resolve("home-fragment.html"), renderer.home(contents));
    }

    ObjectNode usageFixture(String suffix, int percent) throws Exception {
        var root = (ObjectNode) codec.parse(Files.readString(Path.of("../contracts/examples/pricing-ui-" + suffix + ".json")));
        ((ObjectNode) root.path("data").path("task_usage").get(0)).put("consumed_percent", percent);
        return root;
    }
}
