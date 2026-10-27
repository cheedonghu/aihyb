package run.aihyb.pluginaihyb;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class PublicController implements CustomEndpoint {
    private final PackageService service;
    private final PackageCodec codec;
    public PublicController(PackageService service, PackageCodec codec) {
        this.service = service;
        this.codec = codec;
    }

    @Override
    public GroupVersion groupVersion() { return new GroupVersion("api.aihyb.run", "v1alpha1"); }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/contents", this::list, builder -> builder.operationId("listPublishedContents").tag("AIHYBPublic")
                .parameter(parameterBuilder().name("kind").in(ParameterIn.QUERY).implementation(String.class))
                .parameter(parameterBuilder().name("task").in(ParameterIn.QUERY).implementation(String.class))
                .parameter(parameterBuilder().name("scene").in(ParameterIn.QUERY).implementation(String.class))
                .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                .response(responseBuilder().implementation(PackageViews.ContentPage.class)))
            .GET("/contents/{id}", this::get, builder -> builder.operationId("getPublishedContent").tag("AIHYBPublic")
                .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                .response(responseBuilder().implementation(PackageViews.PublishedContent.class)))
            .build().filter((request, next) -> Mono.defer(() -> next.handle(request))
                .onErrorResume(PackageProblem.class, AdminController::error));
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        return service.publicSelection(request.queryParam("kind").orElse(null), request.queryParam("task").orElse(null), request.queryParam("scene").orElse(null),
                AdminController.number(request, "page", 1), AdminController.number(request, "size", 20))
            .flatMap(page -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache").bodyValue(codec.serialize(page)))
            .onErrorResume(PackageProblem.class, AdminController::error);
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        return service.publicContent(request.pathVariable("id"))
            .flatMap(content -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache").eTag(content.revision()).bodyValue(codec.serialize(content)))
            .onErrorResume(PackageProblem.class, AdminController::error);
    }
}
