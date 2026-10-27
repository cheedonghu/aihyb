package run.aihyb.pluginaihyb;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.Objects;
import java.util.UUID;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/** Native SDK requests are redirected here, so Halo re-authenticates against narrow workflow RBAC. */
@Component
public class NativeEditorController implements CustomEndpoint {
    private final NativeMirror mirror;
    private final PackageService packages;
    private final PackageCodec codec;
    private final ReactiveExtensionClient client;
    public NativeEditorController(NativeMirror mirror, PackageService packages, PackageCodec codec, ReactiveExtensionClient client) {
        this.mirror = mirror; this.packages = packages; this.codec = codec; this.client = client;
    }
    @Override public GroupVersion groupVersion() { return new GroupVersion("console.api.aihyb.run", "v1alpha1"); }
    @Override public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/native-posts/{name}/editor", request -> managed(request).flatMap(post -> json(mirror.editor(id(post)))),
                b -> b.operationId("getNativeEditor").tag("AIHYBNative").parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true).implementation(String.class)))
            .PUT("/native-posts/{name}/editor", this::metadata,
                b -> b.operationId("saveNativeMetadata").tag("AIHYBNative").parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .requestBody(requestBodyBuilder().required(true).implementation(Object.class)))
            .PUT("/native-posts/{name}/content", this::content,
                b -> b.operationId("saveNativeContent").tag("AIHYBNative").parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true).implementation(String.class))
                    .requestBody(requestBodyBuilder().required(true).implementation(Object.class)))
            .GET("/native-posts/{name}/content", request -> managed(request).flatMap(post -> json(mirror.revision(NativeMirror.annotations(post).get(NativeMirror.DRAFT))
                .map(rev -> java.util.Map.of("snapshotName", NativeMirror.snapshotName(rev.getMetadata().getName()),
                    "raw", codec.parse(rev.getSpec().getRawJson()).get("content").get("body_markdown").asText(),
                    "content", rev.getSpec().getRenderedHtml(), "rawType", "MARKDOWN", "version", NativeMirror.token(rev.getMetadata().getName()))))),
                b -> b.operationId("getNativeContent").tag("AIHYBNative").parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true).implementation(String.class)))
            .PUT("/native-posts/{name}/publish", this::publish,
                b -> b.operationId("publishNativePackage").tag("AIHYBNative").parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true).implementation(String.class)))
            .GET("/native-snapshots/{name}", request -> json(mirror.editorSnapshot(request.pathVariable("name"))),
                b -> b.operationId("getNativeSnapshot").tag("AIHYBNative").parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true).implementation(String.class)))
            .POST("/native-migrate", request -> json(client.listAll(ContentEntry.class, ListOptions.builder().build(), null)
                .concatMap(entry -> mirror.ensure(entry).flatMap(mirror::repairCategoryReferences))
                .count().map(count -> java.util.Map.of("managed_posts", count))),
                b -> b.operationId("migrateNativePackages").tag("AIHYBNative").response(responseBuilder().implementation(Object.class)))
            .build().filter((request, next) -> Mono.defer(() -> next.handle(request))
                .onErrorResume(PackageProblem.class, AdminController::error)
                .onErrorResume(OptimisticLockingFailureException.class, error -> AdminController.error(new PackageProblem(409, "版本冲突，请重新加载"))));
    }
    private Mono<Post> managed(ServerRequest request) {
        return mirror.managed(request.pathVariable("name")).switchIfEmpty(Mono.error(new PackageProblem(404, "不是适配器管理的文章")));
    }
    private static String id(Post post) { return NativeMirror.annotations(post).get(NativeMirror.ID); }
    private Mono<ServerResponse> metadata(ServerRequest request) {
        return request.bodyToMono(String.class).flatMap(raw -> managed(request).flatMap(post -> {
            var input = codec.parse(raw); var incoming = input.has("post") ? input.get("post") : input;
            var expected = incoming.path("metadata").path("annotations").path(NativeMirror.VIEW_TOKEN).asText(null);
            if (!Objects.equals(expected, NativeMirror.annotations(post).get(NativeMirror.DRAFT))) {
                return Mono.error(new PackageProblem(409, "编辑版本已变化，请重新加载文章"));
            }
            var spec = incoming.path("spec");
            if (!Objects.equals(spec.path("slug").asText(), post.getSpec().getSlug())
                || !Objects.equals(spec.path("owner").asText(), post.getSpec().getOwner())
                || !spec.path("categories").equals(codec.parse(codec.serialize(post.getSpec().getCategories())))
                || !spec.path("tags").equals(codec.parse(codec.serialize(post.getSpec().getTags())))
                || !Objects.equals(spec.path("cover").asText(""), post.getSpec().getCover() == null ? "" : post.getSpec().getCover())) {
                return Mono.error(new PackageProblem(422, "当前稿件的模块、标签、来源与素材请在稿件数据操作维护；URL、作者和封面暂固定"));
            }
            return mirror.revision(expected).flatMap(rev -> {
                var packageNode = (ObjectNode) codec.parse(rev.getSpec().getRawJson()); var editorial = (ObjectNode) packageNode.get("content");
                editorial.put("title", spec.path("title").asText());
                editorial.put("summary", spec.path("excerpt").path("raw").asText());
                return save(request, post, packageNode, expected);
            });
        }));
    }
    private Mono<ServerResponse> content(ServerRequest request) {
        return request.bodyToMono(String.class).flatMap(raw -> managed(request).flatMap(post -> {
            var input = codec.parse(raw); var expected = NativeMirror.annotations(post).get(NativeMirror.DRAFT);
            if (!input.path("version").canConvertToLong() || input.path("version").asLong() != NativeMirror.token(expected)) {
                return Mono.error(new PackageProblem(409, "正文版本已变化，请重新加载文章"));
            }
            if (!"MARKDOWN".equals(input.path("rawType").asText())) { return Mono.error(new PackageProblem(422, "此稿件请使用稿件 Markdown 编辑器")); }
            return mirror.revision(expected).flatMap(rev -> {
                var payload = (ObjectNode) codec.parse(rev.getSpec().getRawJson());
                ((ObjectNode) payload.get("content")).put("body_markdown", input.path("raw").asText());
                return save(request, post, payload, expected);
            });
        }));
    }
    private Mono<ServerResponse> save(ServerRequest request, Post post, ObjectNode payload, String expected) {
        return mirror.revision(expected).flatMap(previous -> {
            var old = codec.parse(previous.getSpec().getRawJson());
            if (old.get("content").equals(payload.get("content")) && old.get("data").equals(payload.get("data"))) {
                return json(mirror.editor(id(post), expected));
            }
            payload.put("submission_id", "native-" + UUID.randomUUID()); payload.put("base_revision", expected);
            return request.principal().switchIfEmpty(Mono.error(new PackageProblem(401, "请先登录")))
                .flatMap(actor -> packages.ingest(codec.serialize(payload), actor.getName()))
                .flatMap(receipt -> json(mirror.editor(id(post), receipt.revision())));
        });
    }
    private Mono<ServerResponse> publish(ServerRequest request) {
        return managed(request).flatMap(post -> {
            var revision = NativeMirror.annotations(post).get(NativeMirror.DRAFT);
            var snapshot = request.queryParam("headSnapshot").orElse("");
            if (!snapshot.equals(NativeMirror.snapshotName(revision))) { return Mono.error(new PackageProblem(409, "发布修订过期")); }
            return request.principal().flatMap(actor -> packages.publish(id(post), new PackageViews.PublishCommand(revision,
                request.queryParam("operationEpoch").orElse(null)), actor.getName()))
                .then(json(mirror.editor(id(post))));
        });
    }
    private Mono<ServerResponse> json(Mono<?> value) {
        return value.flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "private, no-store").bodyValue(codec.serialize(body)));
    }
}
