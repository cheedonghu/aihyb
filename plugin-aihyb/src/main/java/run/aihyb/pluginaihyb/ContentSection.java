package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Editorial placement is versioned separately from the immutable package structure. */
final class ContentSection {
    /** Module category slugs are the public signal for console posts, which carry no package data. */
    private static final Map<String, String> KINDS_BY_SLUG = Map.of(
        "reviews", "review", "tutorials", "tutorial", "leaderboards", "leaderboard",
        "prices", "pricing", "news", "news");
    private static final Map<String, String> SLUGS_BY_KIND = Map.of(
        "review", "reviews", "tutorial", "tutorials", "leaderboard", "leaderboards",
        "pricing", "prices", "news", "news");
    private ContentSection() {}

    static boolean news(JsonNode payload) {
        return "news".equals(payload.path("content").path("section").asText());
    }

    static String of(JsonNode payload) {
        return news(payload) ? "news" : payload.path("kind").asText();
    }

    static String kindOfSlug(String slug) {
        return slug == null ? null : KINDS_BY_SLUG.get(slug);
    }

    static String slugOf(String kind) {
        return kind == null ? null : SLUGS_BY_KIND.get(kind);
    }
}
