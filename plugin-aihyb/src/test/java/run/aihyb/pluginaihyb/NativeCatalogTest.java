package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** Console posts carry no package revision, so their projection rules need their own coverage. */
class NativeCatalogTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);

    @Test void projectsPublishedConsoleNewsWithPermalinkAndCollectionDate() {
        var item = catalog(post("p1", "cat-news")).published().block().getFirst();
        assertEquals("p1", item.content_id());
        assertEquals("news", item.kind());
        assertEquals("/archives/openai-new-model", item.url());
        assertEquals("2026-10-01", item.content().get("news_date"));
        assertEquals("OpenAI 发布新模型", item.content().get("title"));
        assertTrue(String.valueOf(item.content().get("summary")).startsWith("智东西消息"));
        assertTrue(item.data().isEmpty());
        assertTrue(item.sources().isEmpty());
        assertTrue(item.assets().isEmpty());
    }

    @Test void keepsDraftsDeletedManagedPrivateScheduledAndHiddenPostsOut() {
        var managed = post("p2", "cat-news");
        managed.getMetadata().setAnnotations(new HashMap<>(Map.of(NativeMirror.ID, "managed-id")));
        var deleted = post("p3", "cat-news");
        deleted.getSpec().setDeleted(true);
        var privatePost = post("p4", "cat-news");
        privatePost.getSpec().setVisible(Post.VisibleEnum.PRIVATE);
        var scheduled = post("p5", "cat-news");
        scheduled.getSpec().setPublishTime(Instant.parse("2099-01-01T00:00:00Z"));
        var draft = post("p6", "cat-news");
        draft.getSpec().setPublish(false);
        var uncategorized = post("p7", "cat-other");
        var hidden = post("p8", "cat-reviews");
        hidden.getStatus().setHideFromList(true);
        var deletedMetadata = post("p9", "cat-tutorials");
        deletedMetadata.getMetadata().setDeletionTimestamp(Instant.now());
        var items = catalog(managed, deleted, privatePost, scheduled, draft, uncategorized, hidden, deletedMetadata, post("p1", "cat-news")).published().block();
        assertEquals(List.of("p1"), items.stream().map(PackageViews.PublishedContent::content_id).toList());
    }

    @Test void projectsAllFourModuleCategoriesWithoutInventingStructuredData() {
        var expected = Map.of("cat-reviews", "review", "cat-tutorials", "tutorial",
            "cat-leaderboards", "leaderboard", "cat-prices", "pricing");
        expected.forEach((category, kind) -> {
            var source = post("p1", category);
            source.getSpec().setCover("/upload/cover.png");
            var item = catalog(source).published().block().getFirst();
            assertEquals(kind, item.kind());
            assertTrue(item.data().isEmpty());
            assertTrue(item.sources().isEmpty());
            assertEquals("/upload/cover.png", item.assets().getFirst().get("url"));
            assertEquals("2026-10-01T08:00:00Z", item.content().get("published_at"));
            assertFalse(item.inSection("news"));
        });
    }

    @Test void preservesEverySelectedModuleWithoutDuplicatingTheArticle() {
        var source = post("p1", "cat-other");
        source.getSpec().setCategories(List.of("cat-other", "cat-reviews", "cat-news", "cat-prices", "cat-reviews"));
        var items = catalog(source).published().block();
        assertEquals(1, items.size());
        var item = items.getFirst();
        assertEquals(List.of("review", "news", "pricing"), item.content().get("sections"));
        assertTrue(item.inSection("news"));
        assertEquals("2026-10-01", item.content().get("news_date"));
    }

    @Test void newestPublishedArticleComesFirstAndUnknownDatesComeLast() {
        var older = post("old", "cat-reviews");
        var newer = post("new", "cat-reviews");
        newer.getSpec().setPublishTime(Instant.parse("2026-10-02T08:00:00Z"));
        var undated = post("undated", "cat-reviews");
        undated.getSpec().setPublishTime(null);
        undated.getMetadata().setCreationTimestamp(null);
        assertEquals(List.of("new", "old", "undated"), catalog(older, undated, newer).published().block().stream()
            .map(PackageViews.PublishedContent::content_id).toList());
    }

    @Test void byIdResolvesOnlyPublicUnmanagedPosts() {
        when(client.fetch(Post.class, "p1")).thenReturn(Mono.just(post("p1", "cat-news")));
        assertEquals("/archives/openai-new-model", catalog().byId("p1").block().url());
        when(client.fetch(Post.class, "p2")).thenReturn(Mono.just(post("p2", "cat-leaderboards")));
        assertEquals("leaderboard", catalog().byId("p2").block().kind());
        var privatePost = post("private", "cat-reviews");
        privatePost.getSpec().setVisible(Post.VisibleEnum.PRIVATE);
        when(client.fetch(Post.class, "private")).thenReturn(Mono.just(privatePost));
        assertNull(catalog().byId("private").block());
    }

    private NativeCatalog catalog(Post... posts) {
        when(client.listAll(eq(Category.class), any(ListOptions.class), isNull())).thenReturn(Flux.just(
            category("cat-news", "news"), category("cat-reviews", "reviews"), category("cat-tutorials", "tutorials"),
            category("cat-prices", "prices"), category("cat-leaderboards", "leaderboards"),
            category("cat-other", "default")));
        when(client.listAll(eq(Post.class), any(ListOptions.class), isNull())).thenReturn(Flux.fromArray(posts));
        return new NativeCatalog(client);
    }

    private static Category category(String name, String slug) {
        var category = new Category();
        var metadata = new Metadata();
        metadata.setName(name);
        category.setMetadata(metadata);
        var spec = new Category.CategorySpec();
        spec.setSlug(slug);
        category.setSpec(spec);
        return category;
    }

    private static Post post(String name, String category) {
        var post = new Post();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setVersion(3L);
        metadata.setCreationTimestamp(Instant.parse("2026-09-30T00:00:00Z"));
        post.setMetadata(metadata);
        var spec = new Post.PostSpec();
        spec.setTitle("OpenAI 发布新模型");
        spec.setSlug("openai-new-model");
        spec.setPublish(true);
        spec.setDeleted(false);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPublishTime(Instant.parse("2026-10-01T08:00:00Z"));
        spec.setCategories(List.of(category));
        post.setSpec(spec);
        var status = new Post.PostStatus();
        status.setPermalink("/archives/openai-new-model");
        status.setExcerpt("智东西消息，OpenAI 推出新模型。");
        post.setStatus(status);
        return post;
    }
}
