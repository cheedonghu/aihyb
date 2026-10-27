package run.aihyb.pluginaihyb;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;

@Configuration(proxyBeanMethods = false)
public class WebController {
    private final PackageService service;
    private final PackageRenderer renderer;
    private final TemplateNameResolver templates;
    private final PortalRenderer portal;
    private final SiteSettings settings;
    private static final String HOME_TITLE = "AI测评_AI榜单_AI教程_AI使用技巧";
    private static final String HOME_DESCRIPTION = "选对AI，用好AI，就来AI好用榜！AI测评、AI评分与榜单、AI价格对比、AI教程、AI使用技巧、AI资源库，尽在AI好用榜！";
    private static final Map<String, String> PAGE_TITLES = Map.of("news", "最新资讯",
        "review", "AI测评-AI大模型测评_最新AI模型能力测评",
        "leaderboard", "评分与榜单-最新AI评分_AI排行榜",
        "pricing", "价格对比-最新AI大模型价格_AI价格对比",
        "tutorial", "AI资源库-AI教程_AI使用技巧_AI工具箱");
    private static final Map<String, String> ASSETS = Map.of("site.css", "text/css", "portal.css", "text/css", "pricing.css", "text/css", "review.css", "text/css",
        "portal.js", "text/javascript", "logo-primary.png", "image/png", "logo-icon.png", "image/png", "favicon.png", "image/png");
    public WebController(PackageService service, PackageRenderer renderer, TemplateNameResolver templates, PortalRenderer portal, SiteSettings settings) {
        this.service = service; this.renderer = renderer; this.templates = templates; this.portal = portal; this.settings=settings;
    }
    @Bean RouterFunction<ServerResponse> aihybPages() {
        return RouterFunctions.route()
            .GET("/aihyb/site-media/{id}/{hash}", request -> settings.get().flatMap(config -> {
                var qr=config.qrs().stream().filter(item->item.id().equals(request.pathVariable("id")) && SiteSettings.active(item)
                    && (PackageCodec.hash(item.image())+".png").equals(request.pathVariable("hash"))).findFirst();
                if(qr.isEmpty()) return ServerResponse.notFound().build();
                return ServerResponse.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control","no-cache")
                    .header("X-Content-Type-Options","nosniff").bodyValue(java.util.Base64.getDecoder().decode(qr.get().image().substring(22)));
            }))
            .GET("/aihyb/assets/{name}", request -> {
                var name = request.pathVariable("name");
                if (!ASSETS.containsKey(name)) { return ServerResponse.notFound().build(); }
                var resource = new org.springframework.core.io.ClassPathResource("theme-assets/" + name,
                    WebController.class.getClassLoader());
                return ServerResponse.ok().contentType(MediaType.valueOf(ASSETS.get(name)))
                    .header("Cache-Control", name.endsWith(".png") ? "public, max-age=86400" : "no-cache")
                    .bodyValue(resource);
            })
            .GET("/aihyb/poc-sample.svg", request -> ServerResponse.ok().contentType(MediaType.valueOf("image/svg+xml"))
                .header("Cache-Control", "public, max-age=3600")
                .bodyValue("<svg xmlns='http://www.w3.org/2000/svg' width='800' height='200' viewBox='0 0 800 200'><rect width='800' height='200' fill='#edf2fc'/><text x='40' y='110' font-size='36' fill='#2454c6'>AIHYB PoC · Markdown image</text></svg>"))
            .GET("/aihyb/fallback", request -> service.publicList(null, null, 1, 20)
                .flatMap(items -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-cache")
                    .bodyValue(renderer.listing(null, items))))
            .GET("/aihyb", this::listing).GET("/aihyb/{kind}", this::listing)
            .GET("/aihyb/content/{id}", request -> service.publicContent(request.pathVariable("id"))
                .flatMap(content -> ServerResponse.status(HttpStatus.FOUND)
                    .location(URI.create("/archives/aihyb-" + content.content_id())).header("Cache-Control", "no-cache").build()))
            .build().filter((request, next) -> Mono.defer(() -> next.handle(request)).onErrorResume(PackageProblem.class, AdminController::error));
    }
    private Mono<ServerResponse> listing(ServerRequest request) {
        var kind = request.pathVariables().get("kind"); var task = request.queryParam("task").orElse(null);
        var page = AdminController.number(request, "page", 1); var size = AdminController.number(request, "size", 20);
        var scene=request.queryParam("scene").orElse(null);
        var result = service.publicSelection(kind,task,scene,page,size,kind==null || "pricing".equals(kind));
        return result.zipWith(settings.get()).flatMap(pair -> {
            var items=pair.getT1(); var config=pair.getT2();
            var model = new HashMap<String, Object>();
            model.put("pageTitle", kind == null ? HOME_TITLE : PAGE_TITLES.getOrDefault(kind, "四模块内容"));
            model.put("pageDescription", kind == null ? HOME_DESCRIPTION : "news".equals(kind) ? "AI 动态、行业观察与有出处的解读" : "AI好用榜四模块已发布内容");
            model.put("pageBody", portal.listing(kind, items, task, scene, config));
            model.put("pageCanonical", "/aihyb" + (kind == null ? "" : "/" + kind));
            model.put(ModelConst.TEMPLATE_ID, "plugin:plugin-aihyb:portal");
            return templates.resolveTemplateNameOrDefault(request.exchange(), "aihyb-portal")
                .flatMap(template -> ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                    .header("Cache-Control", "no-cache").render(template, model));
        });
    }
}
