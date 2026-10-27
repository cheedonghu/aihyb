package run.aihyb.pluginaihyb;

import static run.halo.app.extension.index.query.Queries.equal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class PackageService {
    public static final String ADMIN = "/apis/console.api.aihyb.run/v1alpha1";
    private final ReactiveExtensionClient client;
    private final PackageCodec codec;
    private final PackageRenderer renderer;
    private final NativeMirror nativeMirror;
    private final MediaService media;
    private final NativeCatalog natives;
    @org.springframework.beans.factory.annotation.Autowired private SiteSettings settings;
    private Mono<SiteSettings.Settings> configuration() { return settings == null ? Mono.just(SiteSettings.defaults()) : settings.get(); }

    public PackageService(ReactiveExtensionClient client, PackageCodec codec, PackageRenderer renderer, NativeMirror nativeMirror, MediaService media, NativeCatalog natives) {
        this.client = client;
        this.codec = codec;
        this.renderer = renderer;
        this.nativeMirror = nativeMirror;
        this.media = media;
        this.natives = natives;
    }

    public Mono<PackageViews.Receipt> ingest(String raw, String actor) {
        return ingest(raw, actor, null);
    }
    public Mono<PackageViews.Receipt> ingest(String raw, String actor, String expectedPostVersion) {
        if (actor == null) { return Mono.error(new PackageProblem(401, "请先登录")); }
        return Mono.fromCallable(() -> codec.validate(raw)).subscribeOn(Schedulers.boundedElastic())
            .flatMap(payload -> configuration().map(config -> {
                if (payload.path("content").has("task_tags")) {
                    var known = config.tags().stream().map(SiteSettings.Tag::id).collect(java.util.stream.Collectors.toSet());
                    for (var tag : payload.path("content").path("task_tags")) if (!known.contains(tag.asText())) throw new PackageProblem(422,"任务标签不存在，请先在站点配置中创建");
                }
                return payload;
            }))
            .flatMap(payload -> {
                var id = payload.get("content_id").asText();
                var rev = "r-" + PackageCodec.hash(actor + "\u0000" + payload.get("submission_id").asText());
                return ensureRevision(rev, payload, PackageCodec.hash(codec.canonical(payload)), actor)
                    .then(client.fetch(ContentEntry.class, entryName(id)).switchIfEmpty(Mono.defer(() -> {
                        if (payload.has("base_revision")) { return Mono.error(new PackageProblem(409, "新条目不能引用旧修订")); }
                        var entry = new ContentEntry(); var metadata = new Metadata(); metadata.setName(entryName(id));
                        entry.setMetadata(metadata); var spec = new ContentEntry.Spec(); entry.setSpec(spec);
                        spec.setContentId(id); spec.setKind(payload.get("kind").asText()); setDraft(entry, rev);
                        return client.create(entry);
                    })))
                    .flatMap(entry -> {
                        if (!entry.getSpec().getKind().equals(payload.get("kind").asText())) {
                            return Mono.error(new PackageProblem(409, "内容类型不能改变"));
                        }
                        return nativeMirror.post(id).map(post -> NativeMirror.annotations(post).getOrDefault(NativeMirror.JOURNAL, "").contains(rev))
                            .defaultIfEmpty(false).flatMap(replayed -> nativeMirror.commit(entry, rev, payload.path("base_revision").asText(null), expectedPostVersion)
                                .map(post -> receipt(nativeMirror.state(entry, post), rev, replayed)));
                    });
            });
    }

    private Mono<ContentRevision> ensureRevision(String name, JsonNode payload, String hash, String actor) {
        return client.fetch(ContentRevision.class, name).flatMap(existing -> {
            if (!Objects.equals(hash, existing.getSpec().getRequestHash())) {
                return Mono.error(new PackageProblem(409, "submission_id: 同一交付 ID 不得提交不同内容"));
            }
            return Mono.just(existing);
        }).switchIfEmpty(Mono.defer(() -> {
            var revision = new ContentRevision();
            var metadata = new Metadata();
            metadata.setName(name);
            revision.setMetadata(metadata);
            var spec = new ContentRevision.Spec();
            spec.setContentId(payload.get("content_id").asText());
            spec.setKind(payload.get("kind").asText());
            spec.setActor(actor);
            spec.setRequestHash(hash);
            spec.setRawJson(codec.canonical(payload));
            spec.setRenderedHtml(renderer.markdown(payload.get("content").get("body_markdown").asText()));
            spec.setRenderedHash(PackageCodec.hash(spec.getRenderedHtml()));
            spec.setCreatedAt(Instant.now().toString());
            revision.setSpec(spec);
            // A collision is safe to retry: no draft pointer was changed by this operation.
            return client.create(revision);
        }));
    }

    private void setDraft(ContentEntry entry, String revision) {
        entry.getSpec().setDraftRevision(revision);
        entry.getSpec().setReviewedRevision(null);
        entry.getSpec().setReviewer(null);
        entry.getSpec().setReviewNote(null);
        entry.getSpec().getAcceptedSubmissions().add(revision);
    }

    private PackageViews.Receipt receipt(ContentEntry entry, String revision, boolean replay) {
        return new PackageViews.Receipt(entry.getSpec().getContentId(), revision, replay,
            ADMIN + "/entries/" + entry.getSpec().getContentId() + "/preview?revision=" + revision,
            entry.getSpec().getPublishedRevision());
    }

    public Mono<ContentEntry> entry(String id) {
        if (!id.matches("[a-z0-9][a-z0-9-]{0,79}")) {
            return Mono.error(new PackageProblem(404, "内容不存在"));
        }
        return client.fetch(ContentEntry.class, entryName(id))
            .switchIfEmpty(Mono.error(new PackageProblem(404, "内容不存在")))
            .flatMap(entry -> nativeMirror.post(id).map(post -> nativeMirror.state(entry, post)).defaultIfEmpty(entry));
    }

    private Mono<ContentRevision> revision(String name) {
        return client.fetch(ContentRevision.class, name)
            .switchIfEmpty(Mono.error(new PackageProblem(409, "修订缺失，请检查恢复记录")))
            .map(revision -> {
                var spec = revision.getSpec();
                if (!Objects.equals(spec.getRequestHash(), PackageCodec.hash(codec.canonical(codec.parse(spec.getRawJson()))))
                    || !Objects.equals(spec.getRenderedHash(), PackageCodec.hash(spec.getRenderedHtml()))) {
                    throw new PackageProblem(503, "修订完整性校验失败，请检查恢复记录");
                }
                return revision;
            });
    }

    public Mono<PackageViews.EntryDetail> detail(String id) {
        return entry(id).flatMap(entry -> revision(entry.getSpec().getDraftRevision())
            .map(revision -> new PackageViews.EntryDetail(summary(entry, revision), revision.getSpec().getRawJson())));
    }

    private PackageViews.EntrySummary summary(ContentEntry entry, ContentRevision revision) {
        return new PackageViews.EntrySummary(entry.getSpec().getContentId(), entry.getSpec().getKind(),
            codec.parse(revision.getSpec().getRawJson()).get("content").get("title").asText(),
            entry.getSpec().getDraftRevision(), entry.getSpec().getPublishedRevision(),
            entry.getSpec().getReviewedRevision(), "/aihyb/content/" + entry.getSpec().getContentId());
    }

    public Mono<PackageViews.Page<PackageViews.EntrySummary>> adminList(String kind, int page, int size) {
        if (kind != null && !PackageCodec.KINDS.contains(kind)) {
            return Mono.error(new PackageProblem(422, "kind: 不支持的模块"));
        }
        var builder = ListOptions.builder();
        if (kind != null) { builder.andQuery(equal("spec.kind", kind)); }
        return client.listBy(ContentEntry.class, builder.build(), pageable(page, size))
            .flatMap(result -> reactor.core.publisher.Flux.fromIterable(result.getItems())
                .flatMapSequential(record -> entry(record.getSpec().getContentId()))
                .flatMapSequential(entry -> revision(entry.getSpec().getDraftRevision())
                    .map(revision -> summary(entry, revision)), 4).collectList()
                .map(items -> new PackageViews.Page<>(items, result.getTotal(), page, size)));
    }

    public Mono<PackageViews.EntryDetail> review(String id, PackageViews.ReviewCommand command, String actor) {
        return entry(id).flatMap(nativeMirror::ensure).then(nativeMirror.review(id, command, actor)).then(detail(id));
    }

    public Mono<PackageViews.EntryDetail> publish(String id, PackageViews.PublishCommand command, String actor) {
        return entry(id).flatMap(nativeMirror::ensure)
            .doOnNext(post -> NativeMirror.expectEpoch(post,command.operation_epoch()))
            .then(nativeMirror.acceptedRevision(id,command.revision()))
            .flatMap(rev -> media.validate(id,codec.parse(rev.getSpec().getRawJson())))
            .then(nativeMirror.publish(id, command.revision(), actor,command.operation_epoch())).then(detail(id));
    }

    private void current(ContentEntry entry, String expected) {
        if (expected == null || !expected.equals(entry.getSpec().getDraftRevision())) {
            throw new PackageProblem(409, "revision: 草稿已变更，请重新预览和审核");
        }
    }

    public Mono<String> preview(String id, String expectedRevision) {
        return entry(id).flatMap(entry -> {
            if (expectedRevision != null && !expectedRevision.equals(entry.getSpec().getDraftRevision())) {
                return Mono.error(new PackageProblem(409, "草稿已变化，请重新加载后预览当前修订"));
            }
            return revision(entry.getSpec().getDraftRevision())
            .map(revision -> renderer.article(codec.parse(revision.getSpec().getRawJson()),
                  revision.getSpec().getRenderedHtml(), true, entry.getSpec().getDraftRevision()));
        });
    }

    public Mono<PackageViews.PublishedContent> publicContent(String id) {
        return publishedEntry(id).flatMap(entry -> revision(entry.getSpec().getPublishedRevision())
                .map(revision -> publicView(revision, entry.getSpec().getPublishedAt())))
            .onErrorResume(PackageProblem.class, error -> error.status() == 404
                ? natives.byId(id).switchIfEmpty(Mono.error(error)) : Mono.error(error));
    }

    /** Re-render only the exact release selected by the native page; never read a draft. */
    public Mono<String> publicNativeBody(String id, String releaseRevision) {
        return publishedRevision(id).filter(revision -> revision.getMetadata().getName().equals(releaseRevision))
            .flatMap(revision -> configuration().map(config -> renderer.nativeBody(codec.parse(revision.getSpec().getRawJson()),
                revision.getSpec().getRenderedHtml(), revision.getMetadata().getName(),config)));
    }

    private Mono<ContentRevision> publishedRevision(String id) {
        return publishedEntry(id).flatMap(entry -> revision(entry.getSpec().getPublishedRevision()));
    }

    private Mono<ContentEntry> publishedEntry(String id) {
        return entry(id).flatMap(entry -> {
            var selected = entry.getSpec().getPublishedRevision();
            if (!Boolean.TRUE.equals(entry.getSpec().getPublished()) || selected == null) {
                return Mono.error(new PackageProblem(404, "内容未发布"));
            }
            return Mono.just(entry);
        });
    }

    private PackageViews.PublishedContent publicView(ContentRevision revision, String publishedAt) {
        var payload = codec.parse(revision.getSpec().getRawJson());
        var content = new java.util.LinkedHashMap<>(codec.toMap(payload.get("content")));
        if (publishedAt != null) {
            content.put("published_at", publishedAt);
        }
        var sources = payload.get("sources").deepCopy();
        sources.forEach(source -> ((com.fasterxml.jackson.databind.node.ObjectNode) source).remove("usage_note"));
        return new PackageViews.PublishedContent(revision.getSpec().getContentId(),
            revision.getMetadata().getName(), ContentSection.of(payload), content,
            codec.toMap(payload.get("data")), codec.toList(sources),java.util.stream.StreamSupport.stream(payload.get("assets").spliterator(),false)
                .filter(asset -> "public".equals(asset.get("visibility").asText()))
                .map(asset -> java.util.Map.<String,Object>of("asset_id",asset.get("asset_id").asText(),"content_type",asset.get("content_type").asText(),
                    "alt",asset.get("alt").asText(),"url",PackageRenderer.mediaUrl(payload,asset,false,revision.getMetadata().getName())))
                .toList());
    }

    public Mono<String> publicArticle(String id) {
        return publishedRevision(id).map(revision -> renderer.article(codec.parse(revision.getSpec().getRawJson()),
            revision.getSpec().getRenderedHtml(), false, revision.getMetadata().getName()));
    }

    public Mono<PackageViews.Page<PackageViews.PublishedContent>> publicList(String kind, String task, int page, int size) {
        pageable(page, size);
        return publicCatalog(kind, task).map(all -> new PackageViews.Page<>(all.stream()
            .skip((long) (page - 1) * size).limit(size).toList(), all.size(), page, size));
    }

    /** Same canonical publication projection as the paginated API, for complete offer tables. */
    public Mono<java.util.List<PackageViews.PublishedContent>> publicCatalog(String kind, String task) {
        if (kind != null && !PackageCodec.KINDS.contains(kind) && !"news".equals(kind)) { return Mono.error(new PackageProblem(422, "kind: 不支持的栏目")); }
        var builder = ListOptions.builder();
        // During this PoC, assemble the published read model from the canonical Post pointers.
        // This avoids stale cross-resource publication indexes; scale testing remains pending.
        // Console posts written in Halo's native editor join the same list: the package revision
        // stays the authority for structured content, a plain post publishes exactly what was saved.
        var published = client.listAll(ContentEntry.class, builder.build(), Sort.by(Sort.Direction.DESC, "metadata.creationTimestamp"))
            .flatMapSequential(record -> entry(record.getSpec().getContentId()).flatMap(entry -> {
                if (!Boolean.TRUE.equals(entry.getSpec().getPublished())) { return Mono.empty(); }
                return revision(entry.getSpec().getPublishedRevision())
                    .map(revision -> publicView(revision, entry.getSpec().getPublishedAt()));
            }), 4)
            .collectList();
        return published.zipWith(natives.published()).map(pair -> {
            var items = new java.util.ArrayList<>(pair.getT1());
            items.addAll(pair.getT2());
            if (kind != null) {
                items.removeIf(item -> !item.inSection(kind));
                items.replaceAll(item -> item.forSection(kind));
            }
            items.sort(java.util.Comparator.comparing(PackageViews.PublishedContent::publicationTime).reversed());
            if ("news".equals(kind)) {
                items.sort((left, right) -> String.valueOf(right.content().get("news_date")).compareTo(String.valueOf(left.content().get("news_date"))));
            }
            return java.util.List.copyOf(items);
        }).flatMap(items -> configuration().map(config -> items.stream()
            .filter(item -> SiteSettings.matches(config,item,task,null)).toList()));
    }

    public Mono<PackageViews.Page<PackageViews.PublishedContent>> publicSelection(String kind,String task,String scene,int page,int size) {
        return publicSelection(kind,task,scene,page,size,false);
    }

    public Mono<PackageViews.Page<PackageViews.PublishedContent>> publicSelection(String kind,String task,String scene,int page,int size,boolean complete) {
        pageable(page,size);
        if (scene != null && !scene.isBlank() && !SiteSettings.SCENES.containsKey(scene)) return Mono.error(new PackageProblem(422,"场景不存在"));
        return publicCatalog(kind,task).zipWith(configuration()).map(pair -> {
            var items=pair.getT1().stream().filter(item->SiteSettings.matches(pair.getT2(),item,null,scene)).toList();
            if (complete) return new PackageViews.Page<>(items,items.size(),1,Math.max(1,items.size()));
            return new PackageViews.Page<>(items.stream().skip((long)(page-1)*size).limit(size).toList(),items.size(),page,size);
        });
    }

    private PageRequestImpl pageable(int page, int size) {
        if (page < 1 || size < 1 || size > 100) { throw new PackageProblem(422, "分页范围：page ≥ 1，size 为 1–100"); }
        return new PageRequestImpl(page, size, Sort.by(Sort.Direction.DESC, "metadata.creationTimestamp"));
    }

    public static String entryName(String contentId) { return "c-" + PackageCodec.hash(contentId); }
}
