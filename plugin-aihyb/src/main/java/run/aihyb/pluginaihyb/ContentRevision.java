package run.aihyb.pluginaihyb;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "content.aihyb.run", version = "v1alpha1", kind = "ContentRevision",
    plural = "contentrevisions", singular = "contentrevision")
public class ContentRevision extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        private String contentId;
        private String kind;
        private String actor;
        private String requestHash;
        private String rawJson;
        private String renderedHtml;
        private String renderedHash;
        private String createdAt;
    }
}
