package run.aihyb.pluginaihyb;

import java.util.List;
import java.util.Map;

public final class PackageViews {
    private PackageViews() {}

    public record Receipt(String content_id, String revision, boolean replayed,
                          String preview_url, String published_revision) {}
    public record EntrySummary(String content_id, String kind, String title,
                               String draft_revision, String published_revision,
                               String reviewed_revision, String public_url) {}
    public record EntryDetail(EntrySummary entry, String draft_json) {}
    public record ReviewCommand(String revision, String decision, String note, String operation_epoch) {
        public ReviewCommand(String revision,String decision,String note){this(revision,decision,note,null);}
    }
    public record PublishCommand(String revision, String operation_epoch) {
        public PublishCommand(String revision){this(revision,null);}
    }
    public record PublishedContent(String content_id, String revision, String kind,
                                   Map<String, Object> content, Map<String, Object> data,
                                   List<Map<String, Object>> sources, List<Map<String,Object>> assets,
                                   String url) {
        public boolean articleOnly() { return data.isEmpty(); }
        public boolean inSection(String section) {
            return section.equals(kind) || (articleOnly() && content.get("sections") instanceof List<?> sections
                && sections.contains(section));
        }
        /** The latest-article stream excludes ranking and pricing placements, even on mixed posts. */
        public boolean inLatestArticles() {
            return !inSection("leaderboard") && !inSection("pricing");
        }
        public PublishedContent forSection(String section) {
            return new PublishedContent(content_id, revision, section, content, data, sources, assets, url);
        }
        public java.time.Instant publicationTime() {
            var value = content.get("published_at");
            if (value != null) { return java.time.Instant.parse(value.toString()); }
            var date = content.get("news_date");
            return date instanceof String text && !text.isBlank()
                ? java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()
                : java.time.Instant.MIN;
        }
        public String publicationDate() {
            var time = publicationTime();
            return time.equals(java.time.Instant.MIN) ? ""
                : java.time.LocalDate.ofInstant(time, java.time.ZoneId.of("Asia/Shanghai")).toString();
        }
        /** Package content keeps its own article route; console posts pass their native permalink. */
        public PublishedContent(String content_id, String revision, String kind, Map<String, Object> content,
                                Map<String, Object> data, List<Map<String, Object>> sources,
                                List<Map<String,Object>> assets) {
            this(content_id, revision, kind, content, data, sources, assets, "/aihyb/content/" + content_id);
        }
    }
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record EntryPage(List<EntrySummary> items, long total, int page, int size) {}
    public record ContentPage(List<PublishedContent> items, long total, int page, int size) {}
    public record Problem(String message, List<String> errors) {}
}
