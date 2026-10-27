package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class ReviewBodyRendererTest {
    final PackageCodec codec = new PackageCodec();
    final PackageRenderer renderer = new PackageRenderer();
    ObjectNode payload() throws Exception {
        return (ObjectNode) codec.validate(Files.readString(Path.of("../contracts/examples/review.json")));
    }
    @Test void reportPreservesEvidenceAndLinksWithoutSynthesizingScores() throws Exception {
        var payload = payload();
        var html = renderer.nativeBody(payload, renderer.markdown(payload.path("content").path("body_markdown").asText()), "release-a");
        var document = Jsoup.parseBodyFragment(html);
        assertTrue(document.select("h1").isEmpty());
        assertEquals(1, document.select(".report-evidence-card").size());
        assertEquals("需要人工检查结构。", document.selectFirst(".report-verdict-statement").text());
        assertEquals("#report-source-source-poc", document.selectFirst(".report-source-ref").attr("href"));
        assertTrue(document.text().contains("虚构测试材料：结构需人工检查。"));
        assertTrue(document.text().contains("虚构演示 · 非真实测评"));
        assertTrue(document.select(".report-prose table").size() == 1);
        assertEquals(4, document.select(".report-facts dd").size());
        assertFalse(document.text().contains("综合评分"));
        assertFalse(document.text().contains("usage_note"));
    }
    @Test void longFormDemoIsValidAndRetainsPromptAndFictionalNotice() throws Exception {
        var payload = codec.validate(Files.readString(Path.of("../contracts/examples/review-body-demo.json")));
        var document = Jsoup.parseBodyFragment(renderer.nativeBody(payload,
            renderer.markdown(payload.path("content").path("body_markdown").asText()), "demo-release"));
        assertEquals(3, document.select(".report-evidence-card").size());
        assertFalse(document.select(".report-prose pre code").isEmpty());
        assertTrue(document.selectFirst(".report-verdict").text().contains("非真实测评"));
        assertTrue(document.text().contains("不是模型实测输出"));
    }
    @Test void escapesPayloadTextAndDoesNotExposePrivateAssetsOrUsageNotes() throws Exception {
        var payload = payload();
        ((ObjectNode) payload.path("data").path("conclusions").get(0)).put("text", "<script>alert(1)</script>");
        ((ObjectNode) payload.path("sources").get(0)).put("usage_note", "PRIVATE_SOURCE_NOTE");
        var asset = payload.withArray("assets").addObject();
        asset.put("asset_id", "secret"); asset.put("visibility", "private"); asset.put("alt", "PRIVATE_ASSET_NAME");
        asset.put("content_type", "image/png");
        var html = renderer.nativeBody(payload, "<h2>Content</h2>", "release-a");
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("PRIVATE_ASSET_NAME"));
        assertFalse(html.contains("PRIVATE_SOURCE_NOTE"));
        assertTrue(html.contains("&lt;script&gt;"));
    }
    @Test void readsOnlyExactPublishedRevisionAndRefusesUnpublishedContent() throws Exception {
        var payload = payload(); var raw = codec.canonical(payload);
        var client = mock(ReactiveExtensionClient.class);
        var service = spy(new PackageService(client, codec, renderer, mock(NativeMirror.class), null, mock(NativeCatalog.class)));
        var entry = new ContentEntry(); var state = new ContentEntry.Spec(); entry.setSpec(state);
        state.setPublished(true); state.setPublishedRevision("release-a"); state.setDraftRevision("draft-b");
        doReturn(Mono.just(entry)).when(service).entry("poc-review");
        var release = new ContentRevision(); var metadata = new Metadata(); metadata.setName("release-a"); release.setMetadata(metadata);
        var spec = new ContentRevision.Spec(); release.setSpec(spec); spec.setRawJson(raw);
        spec.setRenderedHtml(renderer.markdown(payload.path("content").path("body_markdown").asText()));
        spec.setRequestHash(PackageCodec.hash(raw)); spec.setRenderedHash(PackageCodec.hash(spec.getRenderedHtml()));
        when(client.fetch(ContentRevision.class, "release-a")).thenReturn(Mono.just(release));
        assertTrue(service.publicNativeBody("poc-review", "release-a").block().contains("aihyb-review-report"));
        assertNull(service.publicNativeBody("poc-review", "draft-b").block());
        state.setPublished(false);
        var error = assertThrows(PackageProblem.class, () -> service.publicNativeBody("poc-review", "release-a").block());
        assertTrue(error.getMessage().contains("内容未发布"));
        verify(client, never()).fetch(ContentRevision.class, "draft-b");
    }
}
