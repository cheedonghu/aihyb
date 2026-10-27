package run.aihyb.pluginaihyb;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Posts written in Halo's native editor. They have no package revision, so they are projected into
 * the same public read model as package content instead of living in a separate list.
 *
 * <p>Every selected module accepts console posts. Their cards expose article metadata only;
 * structured rankings, prices and verification claims remain owned by package revisions.
 */
@Component
public class NativeCatalog {
    static final Set<String> CARD_KINDS = Set.of("review", "leaderboard", "pricing", "tutorial", "news");
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SUMMARY_LIMIT = 140;
    private final ReactiveExtensionClient client;

    public NativeCatalog(ReactiveExtensionClient client) {
        this.client = client;
    }

    /** Published console posts that resolve to a card module, newest first. */
    public Mono<List<PackageViews.PublishedContent>> published() {
        return categorySlugs().flatMap(slugs -> client.listAll(Post.class, ListOptions.builder().build(), null)
            .filter(NativeCatalog::publicPost)
            .filter(NativeCatalog::unmanaged)
            .collectList()
            .map(posts -> posts.stream()
                .sorted(Comparator.comparing(NativeCatalog::publishedAt,
                    Comparator.nullsLast(Comparator.<Instant>reverseOrder())))
                .map(post -> item(post, slugs))
                .filter(Objects::nonNull)
                .toList()));
    }

    public Mono<PackageViews.PublishedContent> byId(String name) {
        if (name == null || name.isBlank()) { return Mono.empty(); }
        return categorySlugs().flatMap(slugs -> client.fetch(Post.class, name)
            .filter(NativeCatalog::publicPost)
            .filter(NativeCatalog::unmanaged)
            .flatMap(post -> Mono.justOrEmpty(item(post, slugs))));
    }

    private Mono<Map<String, String>> categorySlugs() {
        return client.listAll(Category.class, ListOptions.builder().build(), null)
            .filter(category -> category.getMetadata().getDeletionTimestamp() == null)
            .collectMap(category -> category.getMetadata().getName(), category -> category.getSpec().getSlug());
    }

    /** Package posts stay owned by their revision; only plain console posts are projected here. */
    private static boolean unmanaged(Post post) {
        var annotations = post.getMetadata().getAnnotations();
        return annotations == null || annotations.get(NativeMirror.ID) == null;
    }

    private static boolean publicPost(Post post) {
        var spec = post.getSpec();
        if (spec == null || !Boolean.TRUE.equals(spec.getPublish()) || Boolean.TRUE.equals(spec.getDeleted())) {
            return false;
        }
        if (post.getMetadata().getDeletionTimestamp() != null) { return false; }
        if (spec.getVisible() != Post.VisibleEnum.PUBLIC) { return false; }
        if (post.getStatus() != null && Boolean.TRUE.equals(post.getStatus().getHideFromList())) { return false; }
        var publishedAt = publishedAt(post);
        return publishedAt == null || !publishedAt.isAfter(Instant.now());
    }

    private static PackageViews.PublishedContent item(Post post, Map<String, String> slugs) {
        var sections = sections(post, slugs);
        if (sections.isEmpty()) { return null; }
        var kind = sections.getFirst();
        var title = post.getSpec().getTitle() == null ? "" : post.getSpec().getTitle();
        var permalink = permalink(post);
        var content = new LinkedHashMap<String, Object>();
        content.put("title", title);
        content.put("summary", summary(post));
        content.put("url", permalink);
        content.put("sections", sections);
        var publishedAt = publishedAt(post);
        if (publishedAt != null) { content.put("published_at", publishedAt.toString()); }
        if (sections.contains("news")) { content.put("news_date", date(post)); }
        var assets = new ArrayList<Map<String, Object>>();
        var cover = post.getSpec().getCover();
        if (cover != null && !cover.isBlank()) {
            assets.add(Map.of("url", cover, "content_type", "image/*", "alt", title));
        }
        return new PackageViews.PublishedContent(post.getMetadata().getName(),
            "post-" + post.getMetadata().getVersion(), kind, content, Map.of(), List.of(),
            List.copyOf(assets), permalink);
    }

    private static List<String> sections(Post post, Map<String, String> slugs) {
        var categories = post.getSpec().getCategories();
        if (categories == null) { return List.of(); }
        return categories.stream().map(name -> ContentSection.kindOfSlug(slugs.get(name)))
            .filter(Objects::nonNull).filter(CARD_KINDS::contains).distinct().toList();
    }

    private static Instant publishedAt(Post post) {
        if (post.getSpec() != null && post.getSpec().getPublishTime() != null) {
            return post.getSpec().getPublishTime();
        }
        return post.getMetadata().getCreationTimestamp();
    }

    private static String date(Post post) {
        var publishedAt = publishedAt(post);
        return publishedAt == null ? "" : LocalDate.ofInstant(publishedAt, SITE_ZONE).toString();
    }

    private static String permalink(Post post) {
        var permalink = post.getStatus() == null ? null : post.getStatus().getPermalink();
        if (permalink != null && !permalink.isBlank()) { return permalink; }
        var slug = post.getSpec().getSlug();
        return slug == null || slug.isBlank() ? "" : "/archives/" + slug;
    }

    private static String summary(Post post) {
        var excerpt = post.getStatus() == null ? null : post.getStatus().getExcerpt();
        if (excerpt == null) {
            var raw = post.getSpec().getExcerpt() == null ? null : post.getSpec().getExcerpt().getRaw();
            excerpt = raw;
        }
        if (excerpt == null) { return ""; }
        var text = excerpt.replaceAll("\\s+", " ").trim();
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT) + "…";
    }
}
