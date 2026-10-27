package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class NewsSectionTest {
    final PackageCodec codec = new PackageCodec();
    ObjectNode news(String date) throws Exception {
        var payload = (ObjectNode) codec.parse(Files.readString(Path.of("../contracts/examples/review.json")));
        ((ObjectNode) payload.get("content")).put("section", "news").put("news_date", date);
        return payload;
    }
    PackageViews.PublishedContent view(ObjectNode payload) {
        return new PackageViews.PublishedContent(payload.path("content_id").asText(), "release", ContentSection.of(payload),
            codec.toMap(payload.get("content")), codec.toMap(payload.get("data")), codec.toList(payload.get("sources")), List.of());
    }
    @Test void placementRequiresAValidDateAndPreservesOriginalPackageType() throws Exception {
        var payload = news("2026-10-23");
        assertEquals("review", codec.validate(codec.canonical(payload)).path("kind").asText());
        assertEquals("news", ContentSection.of(payload));
        ((ObjectNode) payload.get("content")).put("news_date", "2026-02-30");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(payload)));
        ((ObjectNode) payload.get("content")).remove("news_date");
        assertThrows(PackageProblem.class, () -> codec.validate(codec.canonical(payload)));
    }
    @Test void homeSeparatesNewsFromAllFourModulesAndLimitsTheStream() throws Exception {
        var items = new java.util.ArrayList<PackageViews.PublishedContent>();
        for (int day = 10; day <= 16; day++) { items.add(view(news("2026-10-" + day))); }
        var document = Jsoup.parse(new PortalRenderer(codec).home(items));
        assertEquals(4, document.select(".module-item").size());
        assertEquals(5, document.select(".news-entry").size());
        assertEquals("2026-10-16", document.selectFirst(".news-entry time").attr("datetime"));
        assertTrue(document.select(".review-card,.editor-item").isEmpty());
        assertEquals("/aihyb/review", document.selectFirst(".featured-story").attr("href"));
        assertEquals("/aihyb/news", document.selectFirst(".news-heading>a").attr("href"));
        assertEquals(1, document.select("h1").size());
    }
    @Test void newsKeepsSourceLinksEscapesTextAndSupportsPaginationAndEmptyState() throws Exception {
        var payload = news("2026-10-23");
        ((ObjectNode) payload.get("content")).put("title", "A <script>alert(1)</script> & B");
        ((ObjectNode) payload.get("sources").get(0)).put("url", "javascript:alert(1)");
        var renderer = new PortalRenderer(codec);
        var document = Jsoup.parse(renderer.listing("news", new PackageViews.Page<>(List.of(view(payload)), 3, 2, 1), null));
        assertEquals("A <script>alert(1)</script> & B", document.selectFirst(".news-card h3").text());
        assertTrue(document.select("script,.news-source,.filter-bar").isEmpty());
        assertEquals(2, document.select(".portal-pagination a").size());
        assertTrue(renderer.listing("news", new PackageViews.Page<>(List.of(), 0, 1, 20), null).contains("新的资讯正在路上"));
    }
    @Test void newsBodyRetainsProseAndSourcesWithoutCapabilityReviewClaims() throws Exception {
        var payload = news("2026-10-23"); var renderer = new PackageRenderer();
        var document = Jsoup.parse(renderer.nativeBody(payload, renderer.markdown("## 观察\n\n尚未独立复现。"), "release"));
        assertEquals("news", document.selectFirst(".aihyb-content-report").attr("data-report-kind"));
        assertTrue(document.selectFirst(".report-prose").text().contains("尚未独立复现"));
        assertTrue(document.text().contains("最新资讯"));
        assertFalse(document.text().contains("任务场景"));
        assertFalse(document.text().contains("过程与结果样例"));
        assertFalse(document.select(".report-source-card").isEmpty());
    }
    @Test void publicCatalogReadsOnlyTheReleasedSectionBeforeFilteringAndPaging() throws Exception {
        var client = mock(ReactiveExtensionClient.class);
        var mirror = mock(NativeMirror.class);
        var natives = mock(NativeCatalog.class);
        when(natives.published()).thenReturn(Mono.just(List.of()));
        var service = spy(new PackageService(client, codec, new PackageRenderer(), mirror, mock(MediaService.class), natives));
        var entry = new ContentEntry(); var metadata = new Metadata(); metadata.setName("entry"); entry.setMetadata(metadata);
        var spec = new ContentEntry.Spec(); entry.setSpec(spec); spec.setContentId("sample"); spec.setKind("review");
        spec.setPublished(true); spec.setPublishedRevision("released"); spec.setDraftRevision("unpublished");
        when(client.listAll(eq(ContentEntry.class), any(ListOptions.class), any())).thenReturn(Flux.just(entry));
        doReturn(Mono.just(entry)).when(service).entry("sample");
        var revision = new ContentRevision(); var rm = new Metadata(); rm.setName("released"); revision.setMetadata(rm);
        var rs = new ContentRevision.Spec(); revision.setSpec(rs); rs.setContentId("sample"); rs.setKind("review");
        rs.setRawJson(codec.canonical(news("2026-10-23"))); rs.setRequestHash(PackageCodec.hash(rs.getRawJson()));
        rs.setRenderedHtml("<p>Release</p>"); rs.setRenderedHash(PackageCodec.hash(rs.getRenderedHtml()));
        when(client.fetch(ContentRevision.class, "released")).thenReturn(Mono.just(revision));
        assertTrue(service.publicCatalog("review", null).block().isEmpty());
        assertEquals("news", service.publicList("news", null, 1, 1).block().items().getFirst().kind());
        assertEquals(1, service.publicList("news", null, 2, 1).block().total());
        assertTrue(service.publicList("news", null, 2, 1).block().items().isEmpty());
        verify(client, never()).fetch(ContentRevision.class, "unpublished");
        rs.setRawJson(codec.canonical(codec.parse(Files.readString(Path.of("../contracts/examples/review.json")))));
        rs.setRequestHash(PackageCodec.hash(rs.getRawJson()));
        assertTrue(service.publicCatalog("news", null).block().isEmpty());
        assertEquals("review", service.publicCatalog("review", null).block().getFirst().kind());
    }
    @Test void consolePostsJoinTheSameNewsStreamAndKeepTheirOwnRoute() throws Exception {
        var client = mock(ReactiveExtensionClient.class);
        var natives = mock(NativeCatalog.class);
        var consolePost = new PackageViews.PublishedContent("0a1b2c3d", "post-4", "news",
            Map.of("title", "后台直接发布的资讯", "summary", "一段摘要", "url", "/archives/console-post", "news_date", "2026-10-27"),
            Map.of(), List.of(), List.of(), "/archives/console-post");
        when(natives.published()).thenReturn(Mono.just(List.of(consolePost)));
        when(client.listAll(eq(ContentEntry.class), any(ListOptions.class), any())).thenReturn(Flux.empty());
        var service = spy(new PackageService(client, codec, new PackageRenderer(), mock(NativeMirror.class), mock(MediaService.class), natives));
        var news = service.publicList("news", null, 1, 20).block();
        assertEquals(1, news.total());
        assertEquals("/archives/console-post", news.items().getFirst().url());
        assertTrue(service.publicList("review", null, 1, 20).block().items().isEmpty());
        var document = Jsoup.parse(new PortalRenderer(codec).home(service.publicCatalog(null, null).block()));
        assertEquals(1, document.select(".news-entry").size());
        assertEquals("/archives/console-post", document.selectFirst(".news-card h3 a").attr("href"));
        assertEquals("2026-10-27", document.selectFirst(".news-entry time").attr("datetime"));
        assertTrue(document.select(".news-source").isEmpty());
        assertFalse(document.selectFirst(".news-meta").text().contains("来源材料"));
    }
    @Test void categoryAndReleaseChangeInOnePostWriteAndCanReturnToOriginalModule() throws Exception {
        var client = mock(ReactiveExtensionClient.class);
        var mirror = spy(new NativeMirror(client, codec, new PackageRenderer(), mock(FeedPublicationEvents.class)));
        var post = new Post(); var pm = new Metadata(); pm.setName(NativeMirror.postName("sample")); post.setMetadata(pm);
        pm.setAnnotations(new java.util.HashMap<>(Map.of(NativeMirror.KIND, "review", NativeMirror.DRAFT, "new", NativeMirror.APPROVED, "new")));
        post.setSpec(new Post.PostSpec()); post.getSpec().setCategories(List.of("old-category"));
        when(client.fetch(Post.class, NativeMirror.postName("sample"))).thenReturn(Mono.just(post));
        when(client.update(post)).thenAnswer(invocation -> {
            assertEquals("new", NativeMirror.annotations(post).get(NativeMirror.PUBLIC));
            assertEquals(List.of("target-category"), post.getSpec().getCategories());
            return Mono.just(post);
        });
        var revision = new ContentRevision(); revision.setSpec(new ContentRevision.Spec());
        revision.getSpec().setRawJson(codec.canonical(news("2026-10-23")));
        doReturn(Mono.just(revision)).when(mirror).revision("new");
        var category = new Category(); var cm = new Metadata(); cm.setName("target-category"); category.setMetadata(cm);
        when(client.listAll(eq(Category.class), any(ListOptions.class), isNull())).thenReturn(Flux.just(category));
        mirror.publish("sample", "new", "editor").block();
        assertEquals("news", NativeMirror.annotations(post).get("aihyb.run/published-section"));
        assertEquals("review", NativeMirror.annotations(post).get(NativeMirror.KIND));
        revision.getSpec().setRawJson(Files.readString(Path.of("../contracts/examples/review.json")));
        mirror.publish("sample", "new", "editor").block();
        assertEquals("review", NativeMirror.annotations(post).get("aihyb.run/published-section"));
        verify(client, times(2)).update(post);
        verify(client, times(2)).listAll(eq(Category.class), any(ListOptions.class), isNull());
    }
}
