package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.jsonpatch.JsonPatch;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer.PathSegment;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.security.AfterSecurityWebFilter;

/** Preflight for native HTTP writes; core still owns authorization, persistence and scheduling. */
@Component
public class PostSectionRequiredFilter implements AfterSecurityWebFilter {
    private static final Set<String> SECTION_SLUGS =
        Set.of("reviews", "leaderboards", "prices", "tutorials", "news");
    private static final String SECTION_MESSAGE =
        "请在文章设置的分类中，至少选择一个栏目：测评、榜单、价格、教程或资讯，然后再发布文章；可同时选择多个栏目及其它分类。";
    private static final MediaType JSON_PATCH = MediaType.valueOf("application/json-patch+json");
    private final ReactiveExtensionClient client;
    private final ObjectMapper mapper = postMapper();
    private final int maxBytes;

    @SuppressWarnings("deprecation")
    private static ObjectMapper postMapper() {
        // Match Post-relevant native settings without crossing plugin class loaders.
        var metadataModule = new SimpleModule()
            .addAbstractTypeMapping(MetadataOperator.class, Metadata.class);
        return new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(metadataModule)
            .addMixIn(Post.PostStatus.class, StatusMixin.class)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS,
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_NULL_MAP_VALUES)
            .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @JsonIgnoreProperties({"conditionsOrDefault"})
    private abstract static class StatusMixin { }

    public PostSectionRequiredFilter(ReactiveExtensionClient client, Environment environment) {
        this.client = client;
        // Match Halo 2.26's native codec default, rather than the small package import limit.
        var configured = environment.getProperty("spring.http.codecs.max-in-memory-size",
            environment.getProperty("spring.codec.max-in-memory-size", "10MB"));
        long limit = DataSize.parse(configured).toBytes();
        this.maxBytes = limit > 0 ? (int) Math.min(limit, Integer.MAX_VALUE) : 10 * 1024 * 1024;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var route = route(exchange);
        if (route == null) { return chain.filter(exchange); }
        return Mono.defer(() -> prepare(exchange, route))
            .flatMap(chain::filter)
            .onErrorResume(PreflightProblem.class,
                problem -> problem(exchange, problem.status, problem.getMessage()));
    }

    private Mono<ServerWebExchange> prepare(ServerWebExchange exchange, Route route) {
        if (route.action == Action.PUBLISH || route.action == Action.UC_PUBLISH) {
            return client.fetch(Post.class, route.name)
                .flatMap(stored -> ownedWhenRequired(stored, route)
                    .flatMap(owned -> owned ? validate(stored, true).thenReturn(exchange)
                        : Mono.just(exchange)))
                .defaultIfEmpty(exchange);
        }
        if (route.action == Action.UC_UPDATE) {
            return client.fetch(Post.class, route.name)
                .flatMap(stored -> ownedWhenRequired(stored, route)
                    .flatMap(owned -> owned ? body(exchange, route, stored) : Mono.just(exchange)))
                .defaultIfEmpty(exchange);
        }
        if (route.action == Action.PATCH) {
            return client.fetch(Post.class, route.name)
                .flatMap(stored -> body(exchange, route, stored)).defaultIfEmpty(exchange);
        }
        return body(exchange, route, null);
    }

    private Mono<Boolean> ownedWhenRequired(Post stored, Route route) {
        if (route.action != Action.UC_UPDATE && route.action != Action.UC_PUBLISH) {
            return Mono.just(true);
        }
        // Core does its own owner check. Never disclose another user's category state.
        return ReactiveSecurityContextHolder.getContext()
            .filter(context -> context.getAuthentication() != null)
            .map(context -> stored.getSpec() != null
                && context.getAuthentication().getName().equals(stored.getSpec().getOwner()))
            .defaultIfEmpty(false);
    }

    private Mono<ServerWebExchange> body(ServerWebExchange exchange, Route route, Post stored) {
        return readBody(exchange).flatMap(bytes -> {
            final Post candidate;
            try {
                JsonNode root = mapper.readTree(bytes);
                if (root == null) { throw new IllegalArgumentException("Empty body"); }
                if (route.action == Action.PATCH) {
                    root = JsonPatch.fromJson(root).apply(postDocument(stored));
                } else if (route.action == Action.CONSOLE) {
                    root = root.path("post");
                }
                candidate = mapper.treeToValue(root, Post.class);
                if (candidate == null) { throw new IllegalArgumentException("Missing post"); }
            } catch (Exception error) {
                return Mono.error(new PreflightProblem(400, "文章请求格式无效，请刷新编辑器后重试。"));
            }
            var name = candidate.getMetadata() == null ? null : candidate.getMetadata().getName();
            if (route.name != null && route.action == Action.CONSOLE && !route.name.equals(name)) {
                return Mono.error(new PreflightProblem(400, "请求路径与文章标识不一致，请刷新编辑器后重试。"));
            }
            if (route.name != null && route.action == Action.RAW && !route.name.equals(name)) {
                // Native raw PUT rejects a mismatched identity; do not inspect a different post.
                return Mono.just(replay(exchange, bytes));
            }
            if (route.action == Action.UC_UPDATE && candidate.getSpec() != null) {
                // UC overwrites these values with persisted state, regardless of the incoming flags.
                candidate.getSpec().setPublish(stored.getSpec().getPublish());
                candidate.getSpec().setDeleted(stored.getSpec().getDeleted());
                candidate.setMetadata(stored.getMetadata());
            }
            if (exchange.getRequest().getMethod() == HttpMethod.POST && candidate.getMetadata() != null) {
                // Core always clears this field on create; normalize only the preflight copy.
                candidate.getMetadata().setDeletionTimestamp(null);
            }
            return validate(candidate, false).thenReturn(replay(exchange, bytes));
        });
    }

    private JsonNode postDocument(Post post) {
        // Core JsonIgnore annotations can belong to a different Jackson class loader.
        var document = mapper.createObjectNode();
        document.put("apiVersion", post.getApiVersion());
        document.put("kind", post.getKind());
        document.set("metadata", mapper.valueToTree(post.getMetadata()));
        document.set("spec", mapper.valueToTree(post.getSpec()));
        if (post.getStatus() != null) {
            document.set("status", mapper.valueToTree(post.getStatus()));
        }
        return document;
    }

    private Mono<byte[]> readBody(ServerWebExchange exchange) {
        if (exchange.getRequest().getHeaders().getContentLength() > maxBytes) {
            return Mono.error(new PreflightProblem(413, "文章请求超过原生正文大小限制，请缩小正文后重试。"));
        }
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxBytes)
            .map(buffer -> {
                try {
                    var bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    return bytes;
                } finally {
                    DataBufferUtils.release(buffer);
                }
            })
            .defaultIfEmpty(new byte[0])
            .onErrorMap(DataBufferLimitException.class,
                error -> new PreflightProblem(413, "文章请求超过原生正文大小限制，请缩小正文后重试。"));
    }

    private ServerWebExchange replay(ServerWebExchange exchange, byte[] bytes) {
        var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
            }
        };
        return exchange.mutate().request(request).build();
    }

    private Mono<Void> validate(Post post, boolean publishing) {
        var spec = post.getSpec();
        if (spec == null || (!publishing && !Boolean.TRUE.equals(spec.getPublish()))
            || Boolean.TRUE.equals(spec.getDeleted())
            || (post.getMetadata() != null && post.getMetadata().getDeletionTimestamp() != null)) {
            return Mono.empty();
        }
        var categories = spec.getCategories();
        if (categories == null || categories.isEmpty()) {
            return Mono.error(new PreflightProblem(422, SECTION_MESSAGE));
        }
        return Flux.fromIterable(categories.stream().filter(name -> name != null && !name.isBlank()).toList())
            .distinct().concatMap(name -> client.fetch(Category.class, name))
            .any(category -> category.getMetadata() != null
                && category.getMetadata().getDeletionTimestamp() == null
                && category.getSpec() != null && category.getSpec().getSlug() != null
                && SECTION_SLUGS.contains(category.getSpec().getSlug())
                && categories.contains(category.getMetadata().getName()))
            .flatMap(valid -> valid ? Mono.empty() : Mono.error(new PreflightProblem(422, SECTION_MESSAGE)));
    }

    private Mono<Void> problem(ServerWebExchange exchange, int status, String detail) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl("no-store");
        var root = mapper.createObjectNode();
        root.put("type", "about:blank");
        root.put("title", status == 422 ? "发布前请选择文章栏目" : "文章请求无效");
        root.put("status", status);
        root.put("detail", detail);
        root.put("message", detail);
        var bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private static Route route(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        var method = request.getMethod();
        if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.PATCH) {
            return null;
        }
        List<String> parts = request.getPath().pathWithinApplication().elements().stream()
            .filter(PathSegment.class::isInstance).map(PathSegment.class::cast)
            .map(PathSegment::valueToMatch).toList();
        if (parts.size() < 4 || parts.size() > 6 || !parts.get(0).equals("apis")
            || !parts.get(2).equals("v1alpha1") || !parts.get(3).equals("posts")) {
            return null;
        }
        var group = parts.get(1);
        boolean console = group.equals("api.console.halo.run");
        boolean raw = group.equals("content.halo.run");
        boolean uc = group.equals("uc.api.content.halo.run");
        if (!console && !raw && !uc) { return null; }
        var name = parts.size() >= 5 ? parts.get(4) : null;
        if (parts.size() == 6 && method == HttpMethod.PUT) {
            var action = parts.get(5);
            if (console && (action.equals("publish") || action.equals("revert-content"))) {
                return new Route(Action.PUBLISH, name);
            }
            if (uc && action.equals("publish")) { return new Route(Action.UC_PUBLISH, name); }
            return null;
        }
        if (parts.size() == 4 && method == HttpMethod.POST
            || parts.size() == 5 && method == HttpMethod.PUT) {
            return new Route(console ? Action.CONSOLE : uc && name != null ? Action.UC_UPDATE : Action.RAW, name);
        }
        var type = request.getHeaders().getContentType();
        return raw && parts.size() == 5 && method == HttpMethod.PATCH && type != null
            && type.isCompatibleWith(JSON_PATCH)
            ? new Route(Action.PATCH, name) : null;
    }

    private enum Action { CONSOLE, RAW, UC_UPDATE, PUBLISH, UC_PUBLISH, PATCH }
    private record Route(Action action, String name) { }
    private static final class PreflightProblem extends RuntimeException {
        private final int status;
        private PreflightProblem(int status, String message) { super(message); this.status = status; }
    }
}
