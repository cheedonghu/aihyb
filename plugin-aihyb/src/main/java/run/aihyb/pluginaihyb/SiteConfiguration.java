package run.aihyb.pluginaihyb;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/** One versioned configuration; HTTP writes go through the validated administrator endpoint. */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "content.aihyb.run", version = "v1alpha1", kind = "SiteConfiguration",
    plural = "siteconfigurations", singular = "siteconfiguration")
public class SiteConfiguration extends AbstractExtension {
    private String configuration;
}
