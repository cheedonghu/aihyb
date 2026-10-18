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
                                   List<Map<String, Object>> sources, List<Map<String,Object>> assets) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record EntryPage(List<EntrySummary> items, long total, int page, int size) {}
    public record ContentPage(List<PublishedContent> items, long total, int page, int size) {}
    public record Problem(String message, List<String> errors) {}
}
