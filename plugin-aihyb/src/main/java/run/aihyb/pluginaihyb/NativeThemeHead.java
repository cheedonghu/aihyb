package run.aihyb.pluginaihyb;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/** Scoped enhancement: native themes keep their own layout; only managed content is styled. */
@Component
public class NativeThemeHead implements TemplateHeadProcessor {
    private final PackageCodec codec;
    private final String highlighting;
    public NativeThemeHead(PackageCodec codec) {
        this.codec = codec;
        var scripts = new StringBuilder("(function(){const window={Prism:{manual:true}};\n");
        for (var file : new String[]{"prism-core.min.js", "prism-clike.min.js", "prism-javascript.min.js"}) {
            try (var input = getClass().getResourceAsStream("/theme-assets/" + file)) {
                if (input == null) { throw new IllegalStateException("Missing bundled Prism asset"); }
                scripts.append(new String(input.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        scripts.append("document.addEventListener('DOMContentLoaded',()=>{document.querySelectorAll('.aihyb-native').forEach(node=>Prism.highlightAllUnder(node))});})();");
        highlighting = scripts.toString().replace("</script", "<\\/script");
    }
    @Override public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler handler) {
        return Mono.fromRunnable(() -> {
            var factory = context.getModelFactory();
            var html = new StringBuilder("<style id='aihyb-theme-assets'>")
                .append(".aihyb-module-nav{display:flex;flex-wrap:wrap;gap:10px 24px;max-width:1080px;margin:12px auto;padding:12px 20px;min-width:0}.aihyb-module-nav a{color:#2454c6;text-decoration:none}")
                .append(".aihyb-native{max-width:100%;min-width:0;overflow-wrap:anywhere}.aihyb-native .scroll{max-width:100%;overflow-x:auto;contain:inline-size}.aihyb-native .scroll table{display:table;min-width:660px;width:100%;border-collapse:collapse}.aihyb-native td,.aihyb-native th{padding:10px;border:1px solid #cbd5e1;white-space:normal}.aihyb-native th{background:#edf2fc;color:#172036}.aihyb-native small{display:block;overflow-wrap:anywhere}.aihyb-native pre{max-width:100%;overflow:auto;white-space:pre;background:#f1f5f9;padding:16px;border-radius:8px;color:#172036}.aihyb-native img{max-width:100%;height:auto}.aihyb-native input,.aihyb-native select{padding:7px;border:1px solid #cbd5e1;max-width:100%}.aihyb-native .filters{display:flex;gap:10px;flex-wrap:wrap}.aihyb-native #compare-message{color:#9a3b12}.aihyb-native .token.keyword{color:#7c3aed}.aihyb-native .token.string{color:#047857}.aihyb-native .token.number,.aihyb-native .token.boolean{color:#b45309}.aihyb-native .token.comment{color:#64748b}.aihyb-native .token.function{color:#1d4ed8}")
                .append("</style>");
            var value = context.getVariable("post");
            var managed = false;
            if (value != null) {
                var post = codec.tree(value);
                if (post.path("metadata").path("annotations").has(NativeMirror.ID)) {
                    managed = true;
                    var canonical = post.path("status").path("permalink").asText();
                    var description = post.path("spec").path("excerpt").path("raw").asText();
                    for (int i = model.size() - 1; i >= 0; i--) {
                        if (model.get(i) instanceof IProcessableElementTag tag
                            && (("link".equals(tag.getElementCompleteName()) && "canonical".equals(tag.getAttributeValue("rel")))
                            || ("meta".equals(tag.getElementCompleteName()) && "description".equals(tag.getAttributeValue("name"))))) {
                            model.remove(i);
                        }
                    }
                    if (!canonical.isBlank()) { html.append("<link rel='canonical' href='").append(PackageRenderer.escape(canonical)).append("'>"); }
                    html.append("<meta name='description' content='").append(PackageRenderer.escape(description)).append("'>");
                }
            }
            if (managed) {
                html.append("<link rel='stylesheet' href='/aihyb/assets/review.css?v=20261023-body3'>")
                    .append("<link rel='stylesheet' href='/aihyb/assets/pricing.css?v=20261021'>")
                    .append("<script src='/aihyb/assets/portal.js?v=20261021' defer></script>")
                    .append(PackageRenderer.priceScript().replace("document.querySelector", "root.querySelector")
                    .replace("(()=>{", "document.addEventListener('DOMContentLoaded',()=>{const root=document.querySelector('.aihyb-native');if(!root)return;")
                    .replace("})();", "});"))
                    .append("<script>").append(highlighting).append("</script>");
            }
            model.insertModel(Math.max(0, model.size() - 1), factory.parse(context.getTemplateData(), html.toString()));
        });
    }
}
