package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class ArticleNavigationFinderTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final ArticleNavigationFinder finder = new ArticleNavigationFinder(client);

    @ParameterizedTest
    @CsvSource({"reviews,review,能力测评", "leaderboards,leaderboard,评分与榜单",
        "prices,pricing,价格对比", "tutorials,tutorial,使用技巧与教程", "news,news,最新资讯"})
    void nativeAndManagedCategoriesLeadToTheirPortalSection(String slug, String kind, String label) {
        when(client.fetch(Category.class, "category-uuid")).thenReturn(Mono.just(category("category-uuid", slug)));
        var section = finder.section(List.of("category-uuid")).block();
        assertEquals(label, section.label());
        assertEquals("/aihyb/" + kind, section.url());
        assertEquals(section.url(), finder.categoryUrl(slug, "/categories/" + slug));
    }

    @Test
    void followsSavedCategoryOrderAndIgnoresUnrelatedDeletedAndMissingCategories() {
        when(client.fetch(Category.class, "missing")).thenReturn(Mono.empty());
        when(client.fetch(Category.class, "custom")).thenReturn(Mono.just(category("custom", "custom")));
        var deleted = category("deleted", "reviews");
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        when(client.fetch(Category.class, "deleted")).thenReturn(Mono.just(deleted));
        when(client.fetch(Category.class, "tutorial")).thenReturn(Mono.just(category("tutorial", "tutorials")));
        when(client.fetch(Category.class, "review")).thenReturn(Mono.just(category("review", "reviews")));
        var selected = Arrays.asList(null, "", "missing", "custom", "deleted", "tutorial", "tutorial", "review");
        assertEquals("/aihyb/tutorial", finder.section(selected).block().url());
        assertEquals(selected, Arrays.asList(null, "", "missing", "custom", "deleted", "tutorial", "tutorial", "review"));
    }

    @Test
    void oldUncategorizedArticlesUseTheArchiveWithoutInventingAModule() {
        assertEquals("内容归档", finder.section(null).block().label());
        assertEquals("/archives", finder.section(List.of()).block().url());
        when(client.fetch(Category.class, "custom")).thenReturn(Mono.just(category("custom", "custom")));
        assertEquals("/archives", finder.section(List.of("custom")).block().url());
        assertEquals("/categories/custom", finder.categoryUrl("custom", "/categories/custom"));
        assertEquals("/archives", finder.categoryUrl(null, null));
    }

    @ParameterizedTest
    @CsvSource({"reviews,review,能力测评", "leaderboards,leaderboard,评分与榜单",
        "prices,pricing,价格对比", "tutorials,tutorial,使用技巧与教程", "news,news,最新资讯"})
    void earthTemplateRendersBothBreadcrumbAndCategoryBadgeAsPortalLinks(String slug, String kind, String label) throws Exception {
        var category = category("native-id", slug);
        category.getSpec().setDisplayName(label);
        when(client.fetch(Category.class, "native-id")).thenReturn(Mono.just(category));
        var html = render(List.of(category), List.of("native-id"), true);
        var document = Jsoup.parseBodyFragment(html);
        var breadcrumb = document.selectFirst(".aihyb-article-breadcrumb");
        assertEquals("/", breadcrumb.select("a").getFirst().attr("href"));
        assertEquals(label, breadcrumb.select("a").get(1).text());
        assertEquals("/aihyb/" + kind, breadcrumb.select("a").get(1).attr("href"));
        assertEquals("正文", breadcrumb.selectFirst("[aria-current=page]").text());
        assertEquals("/aihyb/" + kind, document.selectFirst(".aihyb-article-labels a").attr("href"));
        assertFalse(html.contains("AI好用指南"));
    }

    @Test
    void earthTemplateFallsBackWhenThePluginIsUnavailable() throws Exception {
        var category = category("review", "reviews");
        var document = Jsoup.parseBodyFragment(render(List.of(category), List.of("review"), false));
        assertEquals("/archives", document.select(".aihyb-article-breadcrumb a").get(1).attr("href"));
        assertEquals("/categories/reviews", document.selectFirst(".aihyb-article-labels a").attr("href"));
        verifyNoInteractions(client);
    }

    private String render(List<Category> categories, List<String> selected, boolean available) throws Exception {
        var source = Jsoup.parse(Files.readString(Path.of("../themes/shared/earth-post.html")));
        var template = source.selectFirst(".aihyb-article-breadcrumb").outerHtml()
            + source.selectFirst(".aihyb-article-labels").outerHtml();
        var context = new Context();
        var spec = new Post.PostSpec();
        spec.setCategories(selected);
        context.setVariable("post", new View(spec, categories));
        context.setVariable("pluginFinder", new PluginAvailability(available));
        context.setVariable("aihybArticleFinder", new SynchronousFinder(finder));
        return new SpringTemplateEngine().process(template, context);
    }

    public record View(Post.PostSpec spec, List<Category> categories) {}
    public record PluginAvailability(boolean enabled) {
        public boolean available(String ignored) { return enabled; }
    }
    public record SynchronousFinder(ArticleNavigationFinder finder) {
        public ArticleNavigationFinder.Section section(List<String> categories) { return finder.section(categories).block(); }
        public String categoryUrl(String slug, String fallback) { return finder.categoryUrl(slug, fallback); }
    }

    private static Category category(String name, String slug) {
        var category = new Category();
        var metadata = new Metadata();
        metadata.setName(name);
        category.setMetadata(metadata);
        var spec = new Category.CategorySpec();
        spec.setSlug(slug);
        spec.setDisplayName(slug);
        category.setSpec(spec);
        var status = new Category.CategoryStatus();
        status.setPermalink("/categories/" + slug);
        category.setStatus(status);
        return category;
    }
}
