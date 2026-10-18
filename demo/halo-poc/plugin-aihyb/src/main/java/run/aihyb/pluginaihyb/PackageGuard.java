package run.aihyb.pluginaihyb;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

/** HTTP callers must use the workflow; internal ExtensionClient writes bypass HTTP. */
@Component
public class PackageGuard implements AdditionalWebFilter {
    private final NativeMirror mirror;
    private final run.halo.app.extension.ReactiveExtensionClient client;
    public PackageGuard(NativeMirror mirror,run.halo.app.extension.ReactiveExtensionClient client) { this.mirror = mirror;this.client=client; }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getURI().normalize().getPath();
        var method = exchange.getRequest().getMethod();
        for(var attachmentPrefix:java.util.List.of("/apis/storage.halo.run/v1alpha1/attachments/",
            "/apis/api.console.halo.run/v1alpha1/attachments/","/apis/s3os.halo.run/v1alpha1/attachments/")) {
            if(path.startsWith(attachmentPrefix)&&method!=HttpMethod.GET&&method!=HttpMethod.HEAD&&method!=HttpMethod.OPTIONS){
                var name=path.substring(attachmentPrefix.length()).split("/")[0];
                return client.fetch(run.halo.app.core.extension.attachment.Attachment.class,name).flatMap(attachment -> {
                    if(attachment.getMetadata().getAnnotations()!=null&&attachment.getMetadata().getAnnotations().containsKey(MediaService.OWNER)){
                        return reject(exchange,"此附件用于完整稿件历史，禁止原地修改、删除或解除关联；请上传新附件并保存新草稿");
                    }
                    return chain.filter(exchange).thenReturn(Boolean.TRUE);
                }).switchIfEmpty(Mono.defer(()->chain.filter(exchange).thenReturn(Boolean.TRUE))).then();
            }
        }
        var nativePosts = "/apis/content.halo.run/v1alpha1/posts/";
        var consolePosts = "/apis/api.console.halo.run/v1alpha1/posts/";
        var ucPosts = "/apis/uc.api.content.halo.run/v1alpha1/posts/";
        var prefix = path.startsWith(nativePosts) ? nativePosts : path.startsWith(consolePosts) ? consolePosts : path.startsWith(ucPosts) ? ucPosts : null;
        if (prefix != null) {
            var tail = path.substring(prefix.length()); var parts = tail.split("/", 2); var name = parts[0];
            return mirror.managed(name).flatMap(post -> {
                var suffix = parts.length == 2 ? parts[1] : "";
                String target = null;
                if (method == HttpMethod.GET && prefix.equals(consolePosts)
                    && (suffix.equals("snapshot") || (suffix.equals("content") && exchange.getRequest().getQueryParams().containsKey("snapshotName")))) {
                    return reject(exchange,"完整历史版本请从稿件数据与审核入口查看和恢复");
                }
                if (prefix.equals(nativePosts) && suffix.isEmpty() && (method == HttpMethod.GET || method == HttpMethod.PUT)) {
                    target = PackageService.ADMIN + "/native-posts/" + name + "/editor";
                } else if (prefix.equals(consolePosts) && method == HttpMethod.PUT) {
                    if (suffix.equals("content")) { target = PackageService.ADMIN + "/native-posts/" + name + "/content"; }
                    else if (suffix.equals("publish")) { target = PackageService.ADMIN + "/native-posts/" + name + "/publish"; }
                    else if (suffix.isEmpty()) { return reject(exchange, "请分别保存文章信息与 Markdown 正文"); }
                } else if (prefix.equals(consolePosts) && method == HttpMethod.GET && suffix.equals("head-content")) {
                    target = PackageService.ADMIN + "/native-posts/" + name + "/content";
                }
                if (target != null) { return redirect(exchange, target); }
                if (method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS) {
                    return reject(exchange, "此稿件需通过完整修订流程操作，不能直接修改发布状态或删除");
                }
                return chain.filter(exchange).thenReturn(Boolean.TRUE);
            }).switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.TRUE))).then();
        }
        var snapshotPrefix = "/apis/content.halo.run/v1alpha1/snapshots/";
        if (path.startsWith(snapshotPrefix) && path.substring(snapshotPrefix.length()).matches("as-[0-9a-f]{64}")) {
            if (method == HttpMethod.GET) { return redirect(exchange, PackageService.ADMIN + "/native-snapshots/" + path.substring(snapshotPrefix.length())).then(); }
            if (method != HttpMethod.HEAD && method != HttpMethod.OPTIONS) { return reject(exchange, "稿件快照不可原地修改或删除").then(); }
        }
        if (path.startsWith("/archives/aihyb-") && exchange.getRequest().getQueryParams().containsKey("preview")) {
            var id = path.substring("/archives/aihyb-".length());
            return mirror.post(id).flatMap(post -> redirect(exchange, PackageService.ADMIN + "/entries/" + id + "/preview?revision=" + NativeMirror.annotations(post).get(NativeMirror.DRAFT)))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.TRUE))).then();
        }
        if (path.startsWith("/archives/aihyb-")) {
            var id=path.substring("/archives/aihyb-".length());
            return mirror.post(id).flatMap(post -> {
                if (!Boolean.TRUE.equals(post.getSpec().getPublish()) || NativeMirror.archived(post)||NativeMirror.removed(post)) {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    exchange.getResponse().getHeaders().set("Cache-Control","no-store");
                    return exchange.getResponse().setComplete().thenReturn(Boolean.TRUE);
                }
                return chain.filter(exchange).thenReturn(Boolean.TRUE);
            }).switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.TRUE))).then();
        }
        if (path.startsWith("/apis/content.aihyb.run/")) {
            if (method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                exchange.getResponse().getHeaders().set("Cache-Control", "private, no-store");
                return exchange.getResponse().setComplete();
            }
        }
        if (path.startsWith(PackageService.ADMIN + "/")) {
            exchange.getResponse().getHeaders().set("Cache-Control", "private, no-store");
            exchange.getResponse().getHeaders().set("X-Robots-Tag", "noindex, nofollow");
            var limit=path.matches(".*/entries/[^/]+/assets")?MediaService.MAX_BYTES+64*1024:PackageCodec.MAX_BYTES;
            if (exchange.getRequest().getHeaders().getContentLength() > limit) {
                exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                return exchange.getResponse().setComplete();
            }
        }
        return chain.filter(exchange);
    }
    private Mono<Boolean> redirect(ServerWebExchange exchange, String target) {
        var query = exchange.getRequest().getURI().getRawQuery();
        if (query != null && !target.contains("?")) { target += "?" + query; }
        exchange.getResponse().setStatusCode(HttpStatus.TEMPORARY_REDIRECT);
        exchange.getResponse().getHeaders().setLocation(java.net.URI.create(target));
        exchange.getResponse().getHeaders().set("Cache-Control", "private, no-store");
        return exchange.getResponse().setComplete().thenReturn(Boolean.TRUE);
    }
    private Mono<Boolean> reject(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var bytes = ("{\"message\":\"" + reason + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))).thenReturn(Boolean.TRUE);
    }
}
