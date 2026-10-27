package run.aihyb.pluginaihyb;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "content.aihyb.run", version = "v1alpha1", kind = "ContentEntry",
    plural = "contententries", singular = "contententry")
public class ContentEntry extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        private String contentId;
        private String kind;
        private String draftRevision;
        private String reviewedRevision;
        private String reviewer;
        private String reviewNote;
        private String publishedRevision;
        private String publishedAt;
        private String publisher;
        private Boolean published = false;
        private String publishedTask;
        // Commit journal and draft pointer together for retries after lost responses.
        private List<String> acceptedSubmissions = new ArrayList<>();
    }
}
