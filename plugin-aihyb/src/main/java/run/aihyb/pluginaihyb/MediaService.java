package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

/** Private S3 objects and stable Halo attachment identities; shared URLs expire after one minute. */
@Service
public class MediaService {
    public static final String POLICY="aihyb-media-private";
    public static final String OWNER="aihyb.run/asset-content-id";
    public static final String HASH="aihyb.run/asset-sha256";
    public static final int MAX_BYTES=16*1024*1024;
    public static final Set<String> TYPES=Set.of("image/png","image/jpeg","image/webp","image/gif","video/mp4","video/webm",
        "application/pdf","text/plain","text/markdown","application/zip","application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final ReactiveExtensionClient client;
    private final AttachmentService storage;
    private final NativeMirror mirror;
    private final PackageCodec codec;
    private final WebClient http=WebClient.builder().clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(
        java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(10)).build())).build();
    public MediaService(ReactiveExtensionClient client,AttachmentService storage,NativeMirror mirror,PackageCodec codec) {
        this.client=client;this.storage=storage;this.mirror=mirror;this.codec=codec;
    }
    public record Upload(String object_key,String content_type,String sha256,String filename,long bytes) {}
    public record MediaConfiguration(boolean configured,String policy,int max_bytes) {}
    public Mono<Policy> policy() {
        return client.fetch(Policy.class,POLICY).filter(p -> "s3os".equals(p.getSpec().getTemplateName()))
            .switchIfEmpty(Mono.error(new PackageProblem(503,"请先配置专用私有 S3 存储策略 aihyb-media-private")));
    }
    public Mono<MediaConfiguration> configuration() {
        return policy().map(p -> new MediaConfiguration(true,POLICY,MAX_BYTES))
            .onErrorResume(PackageProblem.class,ignored -> Mono.just(new MediaConfiguration(false,POLICY,MAX_BYTES)));
    }
    public Mono<Upload> upload(String id,String actor,FilePart part) {
        var type=part.headers().getContentType();var mime=type==null?"":type.toString();
        if (!TYPES.contains(mime)) { return Mono.error(new PackageProblem(422,"暂不支持此媒体类型；图片使用 PNG/JPEG/WebP/GIF，视频使用 MP4/WebM")); }
        return mirror.post(id).switchIfEmpty(Mono.error(new PackageProblem(404,"请先创建稿件草稿")))
            .doOnNext(NativeMirror::requireActive).then(policy())
            .then(DataBufferUtils.join(part.content(),MAX_BYTES))
            .switchIfEmpty(Mono.error(new PackageProblem(422,"文件为空")))
            .flatMap(buffer -> {
                byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);
                if (bytes.length==0) { return Mono.error(new PackageProblem(422,"文件为空")); }
                String hash;
                try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
                catch (Exception failure) { return Mono.error(failure); }
                var filename=part.filename().replace('\\','/');filename=filename.substring(filename.lastIndexOf('/')+1);
                if (filename.length()>150 || filename.isBlank()) { return Mono.error(new PackageProblem(422,"文件名无效或过长")); }
                var safeName=UUID.randomUUID()+"-"+filename.replaceAll("[^a-zA-Z0-9._-]","_");
                var original=filename;
                FilePart immutable=new FilePart() {
                    public String filename(){return safeName;}
                    public String name(){return "file";}
                    public HttpHeaders headers(){var headers=new HttpHeaders();headers.setContentType(type);return headers;}
                    public Flux<DataBuffer> content(){return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)));}
                    public Mono<Void> transferTo(Path target){return Mono.error(new UnsupportedOperationException("Use streaming upload"));}
                };
                return storage.upload(actor,POLICY,null,immutable,attachment -> {
                    if (attachment.getMetadata().getAnnotations()==null) { attachment.getMetadata().setAnnotations(new HashMap<>()); }
                    attachment.getMetadata().getAnnotations().put(OWNER,id);attachment.getMetadata().getAnnotations().put(HASH,hash);
                    attachment.getSpec().setDisplayName(original);
                }).flatMap(attachment -> assertPrivate(attachment).thenReturn(new Upload("attachments/"+attachment.getMetadata().getName(),mime,hash,original,bytes.length)));
            }).onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class,
                failure -> new PackageProblem(413,"PoC 单文件上限 16 MiB，请使用短视频或外部视频嵌入"))
            .onErrorMap(failure -> !(failure instanceof PackageProblem),failure -> new PackageProblem(502,"媒体上传暂不可用，请检查专用存储连接后重试"));
    }
    private Mono<Void> assertPrivate(Attachment attachment) {
        // Fail closed when the bucket is accidentally public. Test the unsigned object URL server-side.
        return storage.getPermalink(attachment).switchIfEmpty(Mono.error(new PackageProblem(503,"存储未提供对象地址")))
            .flatMap(uri -> http.get().uri(uri).exchangeToMono(response -> {
                int status=response.statusCode().value();
                return response.releaseBody().then(status==401||status==403 ? Mono.<Void>empty()
                    : Mono.error(new PackageProblem(503,"对象存储未拒绝匿名读取；请设置测试 Bucket 为私有后重试")));
            })).timeout(Duration.ofSeconds(15))
            .onErrorMap(failure -> !(failure instanceof PackageProblem),failure -> new PackageProblem(502,"存储私有性检查暂不可用，请稍后重试"));
    }
    public Mono<Attachment> attachment(String id,JsonNode asset) {
        var key=asset.path("object_key").asText();
        if (!key.matches("attachments/[a-zA-Z0-9][a-zA-Z0-9-]{0,99}")) { return Mono.error(new PackageProblem(422,"附件必须引用本稿件上传的稳定附件标识")); }
        return policy().then(client.fetch(Attachment.class,key.substring("attachments/".length())))
            .filter(a -> a.getMetadata().getDeletionTimestamp()==null && POLICY.equals(a.getSpec().getPolicyName())
                && a.getMetadata().getAnnotations()!=null && id.equals(a.getMetadata().getAnnotations().get(OWNER))
                && asset.get("sha256").asText().equals(a.getMetadata().getAnnotations().get(HASH))
                && asset.get("content_type").asText().equals(a.getSpec().getMediaType()))
            .switchIfEmpty(Mono.error(new PackageProblem(422,"附件不存在、与稿件不匹配或完整性标识不符")));
    }
    public Mono<Void> validate(String id,JsonNode payload) {
        return Flux.fromIterable(payload.get("assets")).concatMap(asset -> attachment(id,asset)
            .flatMap(attachment -> assertPrivate(attachment).then(verifyStored(attachment,asset.get("sha256").asText())))).then();
    }
    private Mono<Void> verifyStored(Attachment attachment,String expected){
        return Mono.defer(()->{
            java.security.MessageDigest digest;
            try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(Exception failure){return Mono.error(failure);}
            var bytes=new java.util.concurrent.atomic.AtomicLong();
            return signed(attachment).flatMap(uri -> http.get().uri(uri).exchangeToMono(response -> {
                if(response.statusCode().value()!=200){return response.releaseBody().then(Mono.error(new PackageProblem(422,"对象不存在或无法读取，请检查存储权限及保留策略")));}
                return response.bodyToFlux(DataBuffer.class).doOnNext(buffer->{
                    try{
                        if(bytes.addAndGet(buffer.readableByteCount())>MAX_BYTES){throw new PackageProblem(422,"存储对象大小异常");}
                        digest.update(buffer.asByteBuffer().asReadOnlyBuffer());
                    }finally{DataBufferUtils.release(buffer);}
                }).then(Mono.defer(()->expected.equals(HexFormat.of().formatHex(digest.digest()))?Mono.<Void>empty()
                    :Mono.error(new PackageProblem(422,"存储对象哈希与稿件不符，禁止发布"))));
            })).timeout(Duration.ofSeconds(30))
              .onErrorMap(failure -> !(failure instanceof PackageProblem),failure -> new PackageProblem(502,"媒体完整性校验暂不可用，请稍后重试"));
        });
    }
    public Mono<Attachment> resolve(String id,String revision,String assetId,boolean preview) {
        return mirror.post(id).flatMap(post -> {
            var a=NativeMirror.annotations(post);
            if (!preview && (!Boolean.TRUE.equals(post.getSpec().getPublish())||NativeMirror.archived(post)||NativeMirror.removed(post)||!revision.equals(a.get(NativeMirror.PUBLIC)))) {
                return Mono.error(new PackageProblem(404,"媒体不可用"));
            }
            return mirror.acceptedRevision(id,revision).flatMap(rev -> {
                var payload=codec.parse(rev.getSpec().getRawJson());
                for (var asset:payload.get("assets")) {
                    if (assetId.equals(asset.get("asset_id").asText())&&(preview||"public".equals(asset.get("visibility").asText()))) { return attachment(id,asset); }
                }
                return Mono.error(new PackageProblem(404,"媒体不可用"));
            });
        }).switchIfEmpty(Mono.error(new PackageProblem(404,"媒体不可用")));
    }
    public Mono<URI> signed(Attachment attachment) { return storage.getSharedURL(attachment,Duration.ofMinutes(1)); }
}
