package run.aihyb.pluginaihyb;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class SiteSettingsController implements CustomEndpoint {
    private final SiteSettings settings;
    public SiteSettingsController(SiteSettings settings) { this.settings=settings; }
    @Override public GroupVersion groupVersion() { return new GroupVersion("console.api.aihyb.run","v1alpha1"); }
    @Override public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/site-settings", request->json(settings.get()), b->b.operationId("getSiteSettings").tag("AIHYBSite")
                .response(responseBuilder().implementation(Object.class)))
            .PUT("/site-settings", request->request.principal().switchIfEmpty(Mono.error(new PackageProblem(401,"请先登录")))
                .flatMap(actor->request.bodyToMono(String.class).flatMap(raw->json(settings.save(raw)))),b->b.operationId("saveSiteSettings").tag("AIHYBSite")
                .requestBody(requestBodyBuilder().required(true).implementation(Object.class)).response(responseBuilder().implementation(Object.class)))
            .GET("/taxonomy", request->json(settings.get().map(value->java.util.Map.of("tags",value.tags(),"scenes",SiteSettings.SCENES))),
                b->b.operationId("getTaskTaxonomy").tag("AIHYBSite").response(responseBuilder().implementation(Object.class)))
            .build().filter((request,next)->Mono.defer(()->next.handle(request))
                .onErrorResume(PackageProblem.class,AdminController::error)
                .onErrorResume(OptimisticLockingFailureException.class,error->AdminController.error(new PackageProblem(409,"配置已变化，请重新加载"))));
    }
    private Mono<ServerResponse> json(Mono<?> value) {
        return value.flatMap(body->ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control","private, no-store").bodyValue(SiteSettings.serialize(body)));
    }
}
