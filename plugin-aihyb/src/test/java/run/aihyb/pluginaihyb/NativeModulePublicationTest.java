package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** Exercises native publication through the shared catalog and the real page renderer. */
class NativeModulePublicationTest {
    final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    final PackageCodec codec = new PackageCodec();
    final PortalRenderer renderer = new PortalRenderer(codec);
    PackageService service;

    @BeforeEach void setup() {
        var categories = Map.of("review", "reviews", "leaderboard", "leaderboards", "pricing", "prices",
            "tutorial", "tutorials", "news", "news").entrySet().stream().map(entry -> {
                var category = new Category(); var metadata = new Metadata(); metadata.setName(entry.getKey());
                category.setMetadata(metadata); var spec = new Category.CategorySpec(); spec.setSlug(entry.getValue());
                category.setSpec(spec); return category;
            }).toList();
        when(client.listAll(eq(Category.class), any(ListOptions.class), isNull())).thenReturn(Flux.fromIterable(categories));
        when(client.listAll(eq(ContentEntry.class), any(ListOptions.class), any())).thenReturn(Flux.empty());
        service = spy(new PackageService(client, codec, new PackageRenderer(), mock(NativeMirror.class),
            mock(MediaService.class), new NativeCatalog(client)));
    }

    @Test void everySelectedCategoryAppearsOnHomeAndItsListWithoutFakeTablesOrClaims() throws Exception {
        var post = post("native-article", List.of("news", "review", "leaderboard", "pricing", "tutorial"));
        when(client.listAll(eq(Post.class), any(ListOptions.class), isNull())).thenReturn(Flux.just(post));
        var all = service.publicCatalog(null, null).block();
        assertEquals(1, all.size());
        var home = Jsoup.parse(renderer.home(all));
        assertEquals(4, home.select(".portal-article-card").size());
        assertEquals(1, home.select(".news-entry").size());
        assertEquals("/archives/native-article", home.selectFirst(".featured-story").attr("href"));
        assertTrue(home.select(".portal-ranking,.pricing-table,.usage-table").isEmpty());
        var output = Path.of("build/reports/native-modules");
        Files.createDirectories(output);
        var style = Files.readString(Path.of("src/main/resources/theme-assets/portal.css"))
            + Files.readString(Path.of("src/main/resources/theme-assets/pricing.css"));
        Files.writeString(output.resolve("home.html"), "<!doctype html><html lang='zh-CN'><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1'><style>" + style
            + "</style><main id='aihyb-portal'>" + renderer.home(all) + "</main></html>");
        for (var kind : List.of("review", "leaderboard", "pricing", "tutorial", "news")) {
            var page = service.publicList(kind, null, 1, 20).block();
            assertEquals(1, page.total());
            assertEquals(kind, page.items().getFirst().kind());
            var document = Jsoup.parse(renderer.listing(kind, page, null));
            var link = document.selectFirst(kind.equals("news") ? ".news-card h3 a" : ".portal-article-card");
            assertEquals("/archives/native-article", link.attr("href"));
            assertTrue(link.text().contains("文章 <script> & 标题"));
            assertTrue(document.select("script,.portal-ranking,.pricing-table,.usage-table").isEmpty());
            assertFalse(document.text().contains("待复现核验"));
        }
    }

    @Test void reviewDoesNotAutomaticallyEnterNewsAndPaginationKeepsArticlesDistinct() {
        var older = post("older", List.of("review"));
        var newer = post("newer", List.of("review"));
        newer.getSpec().setPublishTime(Instant.parse("2026-10-02T08:00:00Z"));
        when(client.listAll(eq(Post.class), any(ListOptions.class), isNull())).thenReturn(Flux.just(older, newer));
        assertTrue(service.publicCatalog("news", null).block().isEmpty());
        var first = service.publicSelection("review", null, null, 1, 1).block();
        var second = service.publicSelection("review", null, null, 2, 1).block();
        assertEquals(2, first.total());
        assertEquals("newer", first.items().getFirst().content_id());
        assertEquals("older", second.items().getFirst().content_id());
        assertTrue(service.publicSelection("review", "research", null, 1, 20).block().items().isEmpty());
        var document = Jsoup.parse(renderer.listing("review", first, null));
        assertEquals(1, document.select(".portal-pagination a[href*=page=2]").size());
    }

    @Test void nativeAndPackageArticlesSortTogetherByPublicationInsteadOfDraftCreation() throws Exception {
        var post = post("native", List.of("review"));
        when(client.listAll(eq(Post.class), any(ListOptions.class), isNull())).thenReturn(Flux.just(post));
        var entry = new ContentEntry(); var em = new Metadata(); em.setName("entry"); entry.setMetadata(em);
        var spec = new ContentEntry.Spec(); entry.setSpec(spec); spec.setContentId("sample");
        spec.setPublished(true); spec.setPublishedRevision("released"); spec.setPublishedAt("2026-09-30T08:00:00Z");
        when(client.listAll(eq(ContentEntry.class), any(ListOptions.class), any())).thenReturn(Flux.just(entry));
        doReturn(Mono.just(entry)).when(service).entry("sample");
        var revision = new ContentRevision(); var rm = new Metadata(); rm.setName("released"); revision.setMetadata(rm);
        var rs = new ContentRevision.Spec(); revision.setSpec(rs); rs.setContentId("sample");
        rs.setRawJson(codec.canonical(codec.parse(Files.readString(Path.of("../contracts/examples/review.json")))));
        rs.setRequestHash(PackageCodec.hash(rs.getRawJson())); rs.setRenderedHtml("");
        rs.setRenderedHash(PackageCodec.hash("")); rs.setCreatedAt("2026-09-01T08:00:00Z");
        when(client.fetch(ContentRevision.class, "released")).thenReturn(Mono.just(revision));
        assertEquals("native", service.publicCatalog("review", null).block().getFirst().content_id());
        spec.setPublishedAt("2026-10-03T08:00:00Z");
        var all = service.publicCatalog("review", null).block();
        assertEquals("sample", all.getFirst().content_id());
        assertEquals(all.getFirst().content().get("published_at"), service.publicContent("sample").block().content().get("published_at"));
        assertEquals(1, Jsoup.parse(renderer.home(all)).select(".portal-article-card").size());
    }

    @Test void mixedLeaderboardCardsKeepTheCatalogOrder() throws Exception {
        var structured = new PortalRendererTest().published("leaderboard");
        var article = new PackageViews.PublishedContent("native", "post-1", "leaderboard",
            Map.of("title", "后台榜单文章", "summary", "一段摘要"), Map.of(), List.of(), List.of(), "/archives/native");
        for (var items : List.of(List.of(article, structured), List.of(structured, article))) {
            var document = Jsoup.parse(renderer.listing("leaderboard", new PackageViews.Page<>(items, 2, 1, 20), null));
            var children = document.selectFirst(".portal-board-list").children();
            assertEquals(2, children.size());
            assertEquals(items.getFirst().articleOnly(), children.getFirst().hasClass("portal-article-card"));
            assertEquals(1, document.select(".portal-ranking").size());
        }
    }

    @Test void contentRouteUsesTheNativePermalink() {
        var post = post("native", List.of("pricing"));
        when(client.fetch(Post.class, "native")).thenReturn(Mono.just(post));
        doReturn(Mono.error(new PackageProblem(404, "内容不存在"))).when(service).entry("native");
        var web = WebTestClient.bindToRouterFunction(new WebController(service, new PackageRenderer(), null,
            renderer, null).aihybPages()).build();
        web.get().uri("/aihyb/content/native").exchange().expectStatus().isFound()
            .expectHeader().valueEquals("Location", "/archives/native");
    }

    private Post post(String name, List<String> categories) {
        var post = new Post(); var metadata = new Metadata(); metadata.setName(name); metadata.setVersion(1L);
        metadata.setCreationTimestamp(Instant.parse("2026-09-30T08:00:00Z")); post.setMetadata(metadata);
        var spec = new Post.PostSpec(); spec.setTitle("文章 <script> & 标题"); spec.setCategories(categories);
        spec.setPublish(true); spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPublishTime(Instant.parse("2026-10-01T08:00:00Z")); spec.setCover("/upload/cover.png"); post.setSpec(spec);
        var status = new Post.PostStatus(); status.setPermalink("/archives/" + name); status.setExcerpt("文章摘要");
        post.setStatus(status); return post;
    }
}
