package run.aihyb.pluginaihyb;

import java.net.URI;
import java.util.HashMap;
import org.jsoup.Jsoup;
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
    public WebController(PackageService service, PackageRenderer renderer, TemplateNameResolver templates) {
        this.service = service; this.renderer = renderer; this.templates = templates;
    }
    @Bean RouterFunction<ServerResponse> aihybPages() {
        return RouterFunctions.route()
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
        return service.publicList(kind, task, page, size).flatMap(items -> {
            var document = Jsoup.parse(renderer.listing(kind, items, task)); var model = new HashMap<String, Object>();
            model.put("pageTitle", document.title().replace(" · AI好用榜", ""));
            model.put("pageDescription", "AI好用榜四模块已发布内容");
            model.put("pageBody", document.selectFirst("main").html());
            model.put("pageCanonical", "/aihyb" + (kind == null ? "" : "/" + kind));
            model.put(ModelConst.TEMPLATE_ID, "plugin:plugin-aihyb:portal");
            return templates.resolveTemplateNameOrDefault(request.exchange(), "aihyb-portal")
                .flatMap(template -> ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                    .header("Cache-Control", "no-cache").render(template, model));
        });
    }
}
