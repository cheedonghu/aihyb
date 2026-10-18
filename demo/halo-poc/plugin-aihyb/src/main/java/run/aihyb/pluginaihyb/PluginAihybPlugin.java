package run.aihyb.pluginaihyb;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author AIHYB
 * @since 1.0.0
 */
@Component
public class PluginAihybPlugin extends BasePlugin {
    private final SchemeManager schemes;

    public PluginAihybPlugin(PluginContext pluginContext, SchemeManager schemes) {
        super(pluginContext);
        this.schemes = schemes;
    }

    @Override
    public void start() {
        schemes.register(ContentEntry.class, indexes -> {
            indexes.add(IndexSpecs.<ContentEntry, String>single("spec.kind", String.class)
                .indexFunc(entry -> entry.getSpec().getKind()));
            indexes.add(IndexSpecs.<ContentEntry, Boolean>single("spec.published", Boolean.class)
                .indexFunc(entry -> entry.getSpec().getPublished()));
            indexes.add(IndexSpecs.<ContentEntry, String>single("spec.publishedTask", String.class)
                .indexFunc(entry -> entry.getSpec().getPublishedTask()));
        });
        schemes.register(ContentRevision.class);
    }

    @Override
    public void stop() {
        schemes.unregister(Scheme.buildFromType(ContentRevision.class));
        schemes.unregister(Scheme.buildFromType(ContentEntry.class));
    }
}
