package run.aihyb.pluginaihyb;

import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.theme.finders.Finder;

/** Resolves article navigation from the selected native categories, in their saved order. */
@Finder("aihybArticleFinder")
public class ArticleNavigationFinder {
    public record Section(String label, String url) {}
    private static final Section ARCHIVE = new Section("内容归档", "/archives");
    private final ReactiveExtensionClient client;

    public ArticleNavigationFinder(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<Section> section(List<String> categoryNames) {
        if (categoryNames == null || categoryNames.isEmpty()) {
            return Mono.just(ARCHIVE);
        }
        return Flux.fromIterable(categoryNames.stream()
                .filter(name -> name != null && !name.isBlank()).distinct().toList())
            .concatMap(name -> client.fetch(Category.class, name))
            .filter(category -> category.getMetadata().getDeletionTimestamp() == null
                && category.getSpec() != null)
            .flatMap(category -> Mono.justOrEmpty(ContentSection.kindOfSlug(category.getSpec().getSlug())))
            .map(kind -> new Section(PortalRenderer.LABELS.get(kind), "/aihyb/" + kind))
            .next()
            .defaultIfEmpty(ARCHIVE);
    }

    public String categoryUrl(String slug, String fallback) {
        var kind = ContentSection.kindOfSlug(slug);
        return kind != null ? "/aihyb/" + kind : fallback == null || fallback.isBlank() ? ARCHIVE.url() : fallback;
    }
}
