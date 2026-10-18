package run.aihyb.pluginaihyb;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.List;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class AdminController implements CustomEndpoint {
    private final PackageService service;
    private final PackageCodec codec;
    private final OperationsService operations;
    public AdminController(PackageService service, PackageCodec codec, OperationsService operations) {
        this.service = service;
        this.codec = codec;
        this.operations = operations;
    }

    @Override
    public GroupVersion groupVersion() { return new GroupVersion("console.api.aihyb.run", "v1alpha1"); }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/entries/{id}/operations", r -> json(operations.status(r.pathVariable("id"))),
                b -> b.operationId("getOperations").tag("AIHYBOperations")
                    .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .response(responseBuilder().implementation(OperationsService.Status.class)))
            .POST("/entries/{id}/operations", r -> json(r.principal().flatMap(actor -> r.bodyToMono(OperationsService.Command.class)
                .flatMap(command -> operations.operate(r.pathVariable("id"),command,actor.getName())))),
                b -> b.operationId("operateEntry").tag("AIHYBOperations")
                    .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .requestBody(requestBodyBuilder().required(true).implementation(OperationsService.Command.class))
                    .response(responseBuilder().implementation(OperationsService.Status.class)))
            .GET("/entries/{id}/history", r -> json(operations.history(r.pathVariable("id"),number(r,"page",1),number(r,"size",20))),
                b -> b.operationId("listHistory").tag("AIHYBOperations")
                    .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                    .response(responseBuilder().implementation(OperationsService.HistoryPage.class)))
            .GET("/entries/{id}/history/{revision}/preview", r -> operations.preview(r.pathVariable("id"),r.pathVariable("revision"))
                .flatMap(html -> ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                    .header("Cache-Control","private, no-store").header("X-Robots-Tag","noindex, nofollow")
                    .header("Content-Security-Policy","frame-ancestors 'self'; object-src 'none'; base-uri 'none'").bodyValue(html)),
                b -> b.operationId("previewHistory").tag("AIHYBOperations")
                    .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .parameter(parameterBuilder().name("revision").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .response(responseBuilder().implementation(String.class)))
            .POST("/entries/{id}/restore", r -> json(r.principal().flatMap(actor -> r.bodyToMono(OperationsService.Command.class)
                .flatMap(command -> operations.restore(r.pathVariable("id"),command,actor.getName())))),
                b -> b.operationId("restoreHistory").tag("AIHYBOperations")
                    .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .requestBody(requestBodyBuilder().required(true).implementation(OperationsService.Command.class))
                    .response(responseBuilder().implementation(PackageViews.EntryDetail.class)))
            .POST("/content-packages", this::submit, builder -> builder.operationId("submitPackage")
                .tag("AIHYBAdmin").description("JSON 对象稿件包，只创建草稿；结构见版本化 JSON Schema。")
                .requestBody(requestBodyBuilder().required(true).implementation(Object.class))
                .response(responseBuilder().implementation(PackageViews.Receipt.class)))
            .GET("/entries", request -> json(service.adminList(request.queryParam("kind").orElse(null), number(request, "page", 1), number(request, "size", 20))),
                builder -> builder.operationId("listEntries").tag("AIHYBAdmin")
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("kind").in(ParameterIn.QUERY).implementation(String.class))
                    .response(responseBuilder().implementation(PackageViews.EntryPage.class)))
            .GET("/entries/{id}", request -> json(service.detail(request.pathVariable("id"))),
                builder -> builder.operationId("getEntry").tag("AIHYBAdmin")
                    .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .response(responseBuilder().implementation(PackageViews.EntryDetail.class)))
            .GET("/entries/{id}/preview", this::preview, builder -> builder.operationId("previewEntry").tag("AIHYBAdmin")
                .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                .parameter(parameterBuilder().name("revision").in(ParameterIn.QUERY).implementation(String.class))
                .response(responseBuilder().implementation(String.class)))
            .POST("/entries/{id}/review", this::review, builder -> builder.operationId("reviewEntry").tag("AIHYBAdmin")
                .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                .requestBody(requestBodyBuilder().required(true).implementation(PackageViews.ReviewCommand.class))
                .response(responseBuilder().implementation(PackageViews.EntryDetail.class)))
            .POST("/entries/{id}/publish", this::publish, builder -> builder.operationId("publishEntry").tag("AIHYBAdmin")
                .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
                .requestBody(requestBodyBuilder().required(true).implementation(PackageViews.PublishCommand.class))
                .response(responseBuilder().implementation(PackageViews.EntryDetail.class)))
            .build().filter((request, next) -> Mono.defer(() -> next.handle(request))
                .onErrorResume(PackageProblem.class, AdminController::error)
                .onErrorResume(OptimisticLockingFailureException.class,
                    problem -> error(new PackageProblem(409, "资源已更新，请重新取得草稿版本"))));
    }

    private Mono<ServerResponse> submit(ServerRequest request) {
        return json(request.principal().switchIfEmpty(Mono.error(new PackageProblem(401, "请先登录")))
            .flatMap(actor -> request.bodyToMono(String.class)
                .switchIfEmpty(Mono.error(new PackageProblem(422, "$: 请提交 JSON 稿件包")))
                .flatMap(raw -> service.ingest(raw, actor.getName()))));
    }

    private Mono<ServerResponse> preview(ServerRequest request) {
        return service.preview(request.pathVariable("id"), request.queryParam("revision").orElse(null))
            .flatMap(html -> ServerResponse.ok().contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "private, no-store").header("X-Robots-Tag", "noindex, nofollow")
                .header("Content-Security-Policy", "frame-ancestors 'self'; object-src 'none'; base-uri 'none'")
                .bodyValue(html)).onErrorResume(PackageProblem.class, AdminController::error);
    }

    private Mono<ServerResponse> review(ServerRequest request) {
        return json(request.principal().flatMap(actor -> request.bodyToMono(PackageViews.ReviewCommand.class)
            .flatMap(command -> service.review(request.pathVariable("id"), command, actor.getName()))));
    }

    private Mono<ServerResponse> publish(ServerRequest request) {
        return json(request.principal().flatMap(actor -> request.bodyToMono(PackageViews.PublishCommand.class)
            .flatMap(command -> service.publish(request.pathVariable("id"), command, actor.getName()))));
    }

    private Mono<ServerResponse> json(Mono<?> value) {
        return value.flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(codec.serialize(body)))
            .onErrorResume(PackageProblem.class, AdminController::error)
            .onErrorResume(OptimisticLockingFailureException.class,
                problem -> error(new PackageProblem(409, "资源已更新，请重新取得草稿版本")));
    }

    static Mono<ServerResponse> error(PackageProblem problem) {
        return ServerResponse.status(problem.status()).contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "private, no-store")
            .bodyValue(new PackageViews.Problem(problem.getMessage(), problem.errors()));
    }

    static int number(ServerRequest request, String name, int fallback) {
        try { return Integer.parseInt(request.queryParam(name).orElse(Integer.toString(fallback))); }
        catch (NumberFormatException error) { throw new PackageProblem(422, name + ": 需要整数"); }
    }
}
