package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackageRendererTest {
    final PackageCodec codec = new PackageCodec();
    final PackageRenderer renderer = new PackageRenderer();

    ObjectNode fixture(String kind) throws Exception {
        var payload = (ObjectNode) codec.validate(Files.readString(Path.of("../contracts/examples/" + kind + ".json")));
        payload.withArray("assets").removeAll();
        return payload;
    }

    ObjectNode asset(ObjectNode payload, String id, String type, String visibility) {
        return payload.withArray("assets").addObject().put("asset_id", id).put("content_type", type)
            .put("visibility", visibility).put("alt", visibility.equals("private") ? "PRIVATE_ASSET_NAME" : id);
    }

    String url(ObjectNode payload, String id, boolean preview, String revision) {
        for (var asset : payload.path("assets")) {
            if (id.equals(asset.path("asset_id").asText())) {
                return PackageRenderer.mediaUrl(payload, asset, preview, revision);
            }
        }
        throw new AssertionError("Missing test asset: " + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "leaderboard", "pricing", "tutorial"})
    void inlineMediaIsNotRepeatedAndUnreferencedPublicAssetsRemain(String kind) throws Exception {
        var payload = fixture(kind);
        asset(payload, "picture", "image/png", "public");
        asset(payload, "linked-file", "application/pdf", "public");
        asset(payload, "inline-video", "video/mp4", "public");
        asset(payload, "extra-image", "image/jpeg", "public");
        asset(payload, "extra-file", "application/pdf", "public");
        asset(payload, "extra-video", "video/mp4", "public");
        var body = renderer.markdown("![正文截图](asset://picture)\n\n图注由正文保留。\n\n[下载已引用附件](asset://linked-file)\n\n[查看视频](asset://inline-video)");
        var document = Jsoup.parseBodyFragment(renderer.nativeBody(payload, body, "public-a"));
        assertEquals(1, document.select(".report-prose>p img").size());
        assertEquals(1, document.select(".report-prose>p video").size());
        assertTrue(document.text().contains("图注由正文保留。"));
        for (var id : List.of("picture", "linked-file", "inline-video")) {
            var mediaUrl = url(payload, id, false, "public-a");
            assertEquals(1, document.select("[src='" + mediaUrl + "'],[href='" + mediaUrl + "']").size(), id);
            assertTrue(document.select(".aihyb-assets [src='" + mediaUrl + "'],.aihyb-assets [href='" + mediaUrl + "']").isEmpty(), id);
        }
        assertEquals(3, document.select(".aihyb-assets figure").size());
        for (var id : List.of("extra-image", "extra-file", "extra-video")) {
            var mediaUrl = url(payload, id, false, "public-a");
            assertEquals(1, document.select(".aihyb-assets [src='" + mediaUrl + "'],.aihyb-assets [href='" + mediaUrl + "']").size(), id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "leaderboard", "pricing", "tutorial"})
    void textCodeAndInvalidImageReferencesDoNotHideAttachments(String kind) throws Exception {
        var payload = fixture(kind);
        asset(payload, "text-only", "image/png", "public");
        asset(payload, "code-only", "image/png", "public");
        asset(payload, "pdf-image", "application/pdf", "public");
        var body = renderer.markdown("文字提到 asset://text-only。\n\n`![代码示例](asset://code-only)`\n\n![不能作为图片展示的PDF](asset://pdf-image)");
        var document = Jsoup.parseBodyFragment(renderer.nativeBody(payload, body, "public-a"));
        assertEquals(3, document.select(".aihyb-assets figure").size());
        assertFalse(document.select(".report-prose code").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "leaderboard", "pricing", "tutorial"})
    void privateAndUnknownReferencesStayFilteredAndOnlySurvivingReferencesCount(String kind) throws Exception {
        var payload = fixture(kind);
        asset(payload, "visible", "image/png", "public");
        asset(payload, "secret", "image/png", "private");
        var body = renderer.markdown("![PRIVATE_INLINE_ALT](asset://secret)\n\n![UNKNOWN_INLINE_ALT](asset://missing)\n\n[![嵌套图片](asset://visible)](asset://secret)");
        var html = renderer.nativeBody(payload, body, "public-a");
        var document = Jsoup.parseBodyFragment(html);
        assertTrue(document.select(".report-prose>p img,.report-prose>p a").isEmpty());
        assertEquals(1, document.select(".aihyb-assets img").size());
        assertEquals(url(payload, "visible", false, "public-a"), document.selectFirst(".aihyb-assets img").attr("src"));
        assertFalse(html.contains("PRIVATE_"));
        assertFalse(html.contains("UNKNOWN_INLINE_ALT"));
        assertFalse(html.contains("/aihyb-asset/"));
        assertFalse(html.contains("/secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "leaderboard", "pricing", "tutorial"})
    void previewUsesDraftGatewayOncePerReferencedAssetAndKeepsPrivateAttachments(String kind) throws Exception {
        var payload = fixture(kind);
        asset(payload, "picture", "image/png", "public");
        asset(payload, "secret", "image/png", "private");
        asset(payload, "secret-file", "application/pdf", "private");
        var body = renderer.markdown("![公开正文图](asset://picture)\n\n![审核正文图](asset://secret)\n\n[未知引用](asset://missing)");
        var html = renderer.articleFragment(payload, body, true, "draft-b");
        var document = Jsoup.parseBodyFragment(html);
        assertEquals(1, document.select(".notice").size());
        assertEquals(2, document.select(".report-prose>p img").size());
        for (var id : List.of("picture", "secret", "secret-file")) {
            var mediaUrl = url(payload, id, true, "draft-b");
            assertEquals(PackageService.ADMIN + "/entries/" + payload.path("content_id").asText() + "/media/draft-b/" + id, mediaUrl);
            assertEquals(1, document.select("[src='" + mediaUrl + "'],[href='" + mediaUrl + "']").size(), id);
        }
        assertEquals(1, document.select(".aihyb-assets figure").size());
        assertTrue(document.selectFirst(".aihyb-assets figcaption").text().contains("仅审核可见"));
        assertFalse(html.contains("/file/draft-b/"));
        assertFalse(html.contains("/aihyb-asset/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"review", "leaderboard", "pricing", "tutorial"})
    void allAssetsInlineLeavesNoEmptyMediaSection(String kind) throws Exception {
        var payload = fixture(kind);
        asset(payload, "picture", "image/png", "public");
        var body = renderer.markdown("![唯一正文图](asset://picture)");
        var document = Jsoup.parseBodyFragment(renderer.nativeBody(payload, body, "public-a"));
        assertEquals(1, document.select(".report-prose>p img").size());
        assertTrue(document.select(".aihyb-assets").isEmpty());
    }
}
