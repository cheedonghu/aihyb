package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

/** A managed native Post is the ONE atomic state authority; revisions hold immutable payloads. */
@Component
public class NativeMirror {
    public static final String ID = "aihyb.run/content-id";
    public static final String KIND = "aihyb.run/kind";
    public static final String DRAFT = "aihyb.run/draft-revision";
    public static final String APPROVED = "aihyb.run/approved-revision";
    public static final String PUBLIC = "aihyb.run/published-revision";
    public static final String JOURNAL = "aihyb.run/accepted-revisions";
    public static final String VIEW_TOKEN = "aihyb.run/editor-revision";
    public static final String VIEW_VERSION = "aihyb.run/editor-version";
    public static final String ARCHIVED = "aihyb.run/archived";
    public static final String OPERATION_EPOCH = "aihyb.run/operation-epoch";
    public static final String GENERATION = "aihyb.run/business-generation";
    private final ReactiveExtensionClient client;
    private final PackageCodec codec;
    private final PackageRenderer renderer;
    private final FeedPublicationEvents feeds;
    public NativeMirror(ReactiveExtensionClient client, PackageCodec codec, PackageRenderer renderer, FeedPublicationEvents feeds) {
        this.client = client; this.codec = codec; this.renderer = renderer;
        this.feeds = feeds;
    }
    public static String postName(String id) { return "ap-" + PackageCodec.hash(id); }
    public static String snapshotName(String revision) { return "as-" + PackageCodec.hash(revision); }
    public static long token(String revision) { return Long.parseLong(PackageCodec.hash(revision).substring(0, 12), 16); }
    public Mono<Post> post(String id) { return client.fetch(Post.class, postName(id)); }
    public Mono<Post> managed(String name) {
        return client.fetch(Post.class, name).filter(post -> {
            var id = annotations(post).get(ID);
            return id != null && postName(id).equals(name);
        });
    }
    public Mono<ContentRevision> revision(String name) {
        if (name == null) { return Mono.error(new PackageProblem(409, "修订缺失")); }
        return client.fetch(ContentRevision.class, name).switchIfEmpty(Mono.error(new PackageProblem(409, "修订缺失")))
            .map(rev -> {
                var spec = rev.getSpec();
                if (!Objects.equals(spec.getRequestHash(), PackageCodec.hash(codec.canonical(codec.parse(spec.getRawJson()))))
                    || !Objects.equals(spec.getRenderedHash(), PackageCodec.hash(spec.getRenderedHtml()))) {
                    throw new PackageProblem(503, "修订完整性校验失败");
                }
                return rev;
            });
    }
    public static Map<String, String> annotations(Post post) {
        if (post.getMetadata().getAnnotations() == null) { post.getMetadata().setAnnotations(new HashMap<>()); }
        return post.getMetadata().getAnnotations();
    }
    public ContentEntry state(ContentEntry entry, Post post) {
        var projected = new ContentEntry(); projected.setMetadata(entry.getMetadata());
        var spec = new ContentEntry.Spec(); projected.setSpec(spec);
        spec.setContentId(entry.getSpec().getContentId()); spec.setKind(entry.getSpec().getKind());
        var a = annotations(post);
        spec.setDraftRevision(a.get(DRAFT)); spec.setReviewedRevision(a.get(APPROVED));
        spec.setPublishedRevision(a.get(PUBLIC)); spec.setPublished(Boolean.TRUE.equals(post.getSpec().getPublish()) && !archived(post) && !removed(post) && a.get(PUBLIC) != null);
        spec.setReviewer(a.get("aihyb.run/reviewer")); spec.setReviewNote(a.get("aihyb.run/review-note"));
        spec.setPublishedTask(a.get("aihyb.run/published-task"));
        spec.setPublishedAt(a.get("aihyb.run/published-at")); spec.setPublisher(a.get("aihyb.run/publisher"));
        spec.setAcceptedSubmissions(new ArrayList<>(journal(post)));
        return projected;
    }
    private List<String> journal(Post post) {
        var value = annotations(post).get(JOURNAL);
        return value == null || value.isBlank() ? new ArrayList<>() : new ArrayList<>(List.of(value.split(",")));
    }
    private void expect(Post post, String revision) {
        if (revision == null || !Objects.equals(annotations(post).get(DRAFT), revision)) {
            throw new PackageProblem(409, "草稿已变化，请重新加载并核对当前版本");
        }
    }
    public static boolean archived(Post post) { return "true".equals(annotations(post).get(ARCHIVED)); }
    public static void requireActive(Post post) {
        if(removed(post)){throw new PackageProblem(409,"稿件已进入回收状态，不能写入");}
        if (archived(post)) { throw new PackageProblem(409, "稿件已归档，请先取消归档"); }
    }
    public static boolean removed(Post post){return post.getMetadata().getDeletionTimestamp()!=null||Boolean.TRUE.equals(post.getSpec().getDeleted());}
    public static void advance(Post post){annotations(post).put(GENERATION,java.util.UUID.randomUUID().toString());}
    public static String epoch(Post post){return annotations(post).getOrDefault(OPERATION_EPOCH,"0");}
    public static String businessVersion(Post post,PackageCodec codec){
        var business=new java.util.TreeMap<String,String>();
        annotations(post).forEach((key,value)->{if(key.startsWith("aihyb.run/")){business.put(key,value);}});
        return PackageCodec.hash(java.util.Objects.toString(post.getMetadata().getName(),"")+"\u0000"
            +java.util.Objects.toString(post.getMetadata().getCreationTimestamp(),"")+"\u0000"
            +java.util.Objects.toString(post.getMetadata().getDeletionTimestamp(),"")+"\u0000"
            +codec.canonical(codec.tree(post.getSpec()))+"\u0000"+codec.canonical(codec.tree(business)));
    }
    public static void expectEpoch(Post post,String expected){
        if(!Objects.equals(epoch(post),expected==null?"0":expected)){
            throw new PackageProblem(409,"运营状态已变化，请重新加载并审核当前修订");
        }
    }
    public Mono<ContentRevision> acceptedRevision(String id, String name) {
        return post(id).flatMap(post -> {
            if (!journal(post).contains(name)) { return Mono.error(new PackageProblem(404, "该修订未被此稿件接纳")); }
            return revision(name).filter(rev -> id.equals(rev.getSpec().getContentId())
                && annotations(post).get(KIND).equals(rev.getSpec().getKind()))
                .switchIfEmpty(Mono.error(new PackageProblem(409, "修订与稿件不匹配")));
        });
    }
    private Mono<Snapshot> snapshot(Post post, ContentRevision revision) {
        var name = snapshotName(revision.getMetadata().getName());
        return client.fetch(Snapshot.class, name).switchIfEmpty(Mono.defer(() -> {
            var payload = codec.parse(revision.getSpec().getRawJson());
            var raw = payload.get("content").get("body_markdown").asText();
            var html = renderer.nativeBody(payload, revision.getSpec().getRenderedHtml(), revision.getMetadata().getName());
            var snapshot = new Snapshot(); var metadata = new Metadata(); metadata.setName(name);
            metadata.setAnnotations(new HashMap<>(Map.of("aihyb.run/revision", revision.getMetadata().getName())));
            snapshot.setMetadata(metadata); var spec = new Snapshot.SnapShotSpec(); snapshot.setSpec(spec);
            spec.setSubjectRef(Ref.of(post)); spec.setRawType("MARKDOWN");
            spec.setOwner(revision.getSpec().getActor()); spec.setContributors(Set.of(revision.getSpec().getActor()));
            spec.setLastModifyTime(Instant.parse(revision.getSpec().getCreatedAt()));
            spec.setParentSnapshotName(post.getSpec().getHeadSnapshot());
            if (post.getSpec().getBaseSnapshot() == null) {
                metadata.getAnnotations().put(Snapshot.KEEP_RAW_ANNO, "true");
                spec.setRawPatch(raw); spec.setContentPatch(html);
                return client.create(snapshot);
            }
            return client.get(Snapshot.class, post.getSpec().getBaseSnapshot()).flatMap(base -> {
                spec.setRawPatch(PatchUtils.diffToJsonPatch(base.getSpec().getRawPatch(), raw));
                spec.setContentPatch(PatchUtils.diffToJsonPatch(base.getSpec().getContentPatch(), html));
                return client.create(snapshot);
            });
        }));
    }
    /** Idempotent migration preserves the pre-existing draft, approval and public pointers. */
    public Mono<Post> ensure(ContentEntry entry) {
        var id = entry.getSpec().getContentId();
        return post(id).switchIfEmpty(Mono.defer(() -> {
            var post = new Post(); var metadata = new Metadata(); metadata.setName(postName(id));
            metadata.setLabels(new HashMap<>(Map.of("aihyb.run/managed", "true")));
            metadata.setAnnotations(new HashMap<>()); post.setMetadata(metadata);
            var a = annotations(post); var original = entry.getSpec();
            a.put(ID, id); a.put(KIND, original.getKind()); a.put(DRAFT, original.getDraftRevision());
            a.put(JOURNAL, String.join(",", original.getAcceptedSubmissions()));
            if (original.getReviewedRevision() != null) { a.put(APPROVED, original.getReviewedRevision()); }
            if (original.getReviewer() != null) { a.put("aihyb.run/reviewer", original.getReviewer()); }
            if (original.getReviewNote() != null) { a.put("aihyb.run/review-note", original.getReviewNote()); }
            var spec = new Post.PostSpec(); post.setSpec(spec); spec.setSlug("aihyb-" + id);
            spec.setPublish(false); spec.setDeleted(false); spec.setPinned(false); spec.setAllowComment(false);
            spec.setVisible(Post.VisibleEnum.PUBLIC); spec.setPriority(0); spec.setTags(List.of());
            spec.setCategories(List.of());
            var selected = Boolean.TRUE.equals(original.getPublished()) && original.getPublishedRevision() != null
                ? original.getPublishedRevision() : original.getDraftRevision();
            return moduleCategory(original.getKind()).flatMap(category -> {
                spec.setCategories(List.of(category));
                return revision(selected).flatMap(rev -> {
                applyMetadata(post, codec.parse(rev.getSpec().getRawJson()));
                spec.setOwner(rev.getSpec().getActor());
                return snapshot(post, rev).flatMap(base -> {
                    spec.setBaseSnapshot(base.getMetadata().getName()); spec.setHeadSnapshot(base.getMetadata().getName());
                    if (Boolean.TRUE.equals(original.getPublished()) && original.getPublishedRevision() != null) {
                        spec.setReleaseSnapshot(base.getMetadata().getName()); spec.setPublish(true);
                        a.put(PUBLIC, selected); a.put("aihyb.run/published-task", codec.parse(rev.getSpec().getRawJson()).get("data").get("task").asText());
                    }
                    return revision(original.getDraftRevision()).flatMap(draft -> snapshot(post, draft))
                        .flatMap(head -> { spec.setHeadSnapshot(head.getMetadata().getName()); return client.create(post); });
                });
                });
            });
        }));
    }
    private static String categorySlug(String kind) {
        var slug = Map.of("review", "reviews", "tutorial", "tutorials", "leaderboard", "leaderboards", "pricing", "prices", "news", "news").get(kind);
        if (slug == null) { throw new PackageProblem(422, "未知模块分类"); }
        return slug;
    }
    private Mono<String> moduleCategory(String kind) {
        return client.listAll(Category.class, run.halo.app.extension.ListOptions.builder()
                .fieldQuery(run.halo.app.extension.index.query.QueryFactory.equal("spec.slug", categorySlug(kind))).build(), null)
            .filter(category -> category.getMetadata().getDeletionTimestamp() == null).collectList().map(categories -> {
                if (categories.size() != 1) { throw new PackageProblem(422, "模块分类缺失或不唯一，请先核对 Halo 分类"); }
                return categories.getFirst().getMetadata().getName();
            });
    }
    /** Admin migration repairs only the known old dangling slug reference; no editorial pointers change. */
    public Mono<Post> repairCategoryReferences(Post managed) {
        var id = annotations(managed).get(ID);
        var kind = annotations(managed).getOrDefault("aihyb.run/published-section", annotations(managed).get(KIND));
        return moduleCategory(kind).flatMap(name -> Mono.defer(() -> post(id).flatMap(current -> {
            if (Objects.equals(current.getSpec().getCategories(), List.of(name))) { return Mono.just(current); }
            var legacy = categorySlug(kind);
            if (!Objects.equals(current.getSpec().getCategories(), List.of(legacy))) {
                return Mono.error(new PackageProblem(409, "分类不是已知旧引用，请人工核对"));
            }
            return client.fetch(Category.class, legacy).hasElement().flatMap(exists -> {
                if (exists) { return Mono.error(new PackageProblem(409, "旧分类标识已经存在，请人工核对")); }
                current.getSpec().setCategories(List.of(name));
                advance(current);
                return client.update(current);
            });
        }))).retryWhen(Retry.max(4).filter(OptimisticLockingFailureException.class::isInstance));
    }
    public Mono<Post> commit(ContentEntry entry, String revision, String expectedBase) {
        return commit(entry, revision, expectedBase, null);
    }
    public Mono<Post> commit(ContentEntry entry, String revision, String expectedBase, String expectedPostVersion) {
        return ensure(entry).then(Mono.defer(() -> post(entry.getSpec().getContentId()).flatMap(post -> {
            requireActive(post);
            var accepted = journal(post); var a = annotations(post);
            if (accepted.contains(revision)) { return Mono.just(post); }
            if (expectedPostVersion != null && !Objects.equals(businessVersion(post,codec), expectedPostVersion)) {
                return Mono.error(new PackageProblem(409, "运营状态已变化，请重新加载后恢复历史版本"));
            }
            if (!Objects.equals(a.get(DRAFT), expectedBase)) { return Mono.error(new PackageProblem(409, "base_revision: 草稿已变化")); }
            if (accepted.size() >= 1000) { return Mono.error(new PackageProblem(409, "交付修订日志已达上限")); }
            return revision(revision).flatMap(rev -> snapshot(post, rev).flatMap(head -> {
                a.put(DRAFT, revision); a.remove(APPROVED); a.remove("aihyb.run/reviewer"); a.remove("aihyb.run/review-note");
                advance(post);
                accepted.add(revision); a.put(JOURNAL, String.join(",", accepted));
                post.getSpec().setHeadSnapshot(head.getMetadata().getName());
                if (a.get(PUBLIC) == null) { applyMetadata(post, codec.parse(rev.getSpec().getRawJson())); }
                return client.update(post);
            }));
        }))).retryWhen(Retry.max(4).filter(OptimisticLockingFailureException.class::isInstance));
    }
    public Mono<Post> review(String id, PackageViews.ReviewCommand command, String actor) {
        return Mono.defer(() -> post(id).flatMap(post -> {
            requireActive(post);
            expectEpoch(post,command.operation_epoch());
            expect(post, command.revision());
            if (!Set.of("approve", "request_changes").contains(command.decision() == null ? "" : command.decision())) {
                return Mono.error(new PackageProblem(422, "请选择审核通过或退回"));
            }
            if (command.note() != null && command.note().length() > 2000) { return Mono.error(new PackageProblem(422, "审核备注过长")); }
            return revision(command.revision()).flatMap(verified -> {
                var a = annotations(post); if ("approve".equals(command.decision())) { a.put(APPROVED, command.revision()); } else { a.remove(APPROVED); }
                a.put("aihyb.run/reviewer", actor); a.put("aihyb.run/review-note", command.note() == null ? "" : command.note());
                advance(post);
                return client.update(post);
            });
        })).retryWhen(Retry.max(4).filter(OptimisticLockingFailureException.class::isInstance));
    }
    public Mono<Post> publish(String id, String expected, String actor) {
        return post(id).flatMap(initial -> {
            return publish(id,expected,actor,epoch(initial));
        });
    }
    public Mono<Post> publish(String id,String expected,String actor,String expectedEpoch){
        return Mono.defer(() -> post(id).flatMap(post -> {
            requireActive(post);
            expectEpoch(post,expectedEpoch);
            expect(post, expected); var a = annotations(post);
            if (!Objects.equals(a.get(APPROVED), expected)) { return Mono.error(new PackageProblem(409, "请先从文章的稿件数据操作审核当前修订")); }
            return revision(expected).flatMap(rev -> {
                var payload = codec.validate(rev.getSpec().getRawJson());
                var section = ContentSection.of(payload);
                var category = section.equals(a.getOrDefault("aihyb.run/published-section", a.get(KIND)))
                    ? Mono.just("") : moduleCategory(section);
                return category.flatMap(name -> {
                if (!name.isEmpty()) { post.getSpec().setCategories(List.of(name)); }
                a.put("aihyb.run/published-section", ContentSection.of(payload));
                applyMetadata(post, payload); a.put(PUBLIC, expected); a.put("aihyb.run/published-task", payload.get("data").get("task").asText());
                a.put("aihyb.run/publisher", actor); a.put("aihyb.run/published-at", Instant.now().toString());
                post.getSpec().setReleaseSnapshot(snapshotName(expected)); post.getSpec().setPublish(true);
                advance(post);
                // Title, excerpt, selected body snapshot AND JSON revision commit on ONE native Post.
                return client.update(post);
                });
            });
        })).retryWhen(Retry.max(4).filter(OptimisticLockingFailureException.class::isInstance))
            .doOnNext(feeds::published);
    }
    private void applyMetadata(Post post, JsonNode payload) {
        post.getSpec().setTitle(payload.get("content").get("title").asText());
        var excerpt = new Post.Excerpt(); excerpt.setAutoGenerate(false); excerpt.setRaw(payload.get("content").get("summary").asText());
        post.getSpec().setExcerpt(excerpt);
    }
    /** Private editor projection; never persist pending metadata in the public Post spec. */
    public Mono<Map<String, Object>> editor(String id) {
        return editor(id, null);
    }
    /** A write response may only acknowledge its own revision, never a later writer's token. */
    public Mono<Map<String, Object>> editor(String id, String expectedRevision) {
        return post(id).flatMap(post -> {
            if (expectedRevision != null) { expect(post, expectedRevision); }
            return revision(annotations(post).get(DRAFT)).map(rev -> {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) codec.tree(post);
            var payload = codec.parse(rev.getSpec().getRawJson());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("spec")).put("title", payload.get("content").get("title").asText());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("spec").get("excerpt")).put("raw", payload.get("content").get("summary").asText());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("metadata").get("annotations")).put(VIEW_TOKEN, rev.getMetadata().getName());
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("metadata").get("annotations")).put(VIEW_VERSION, Long.toString(token(rev.getMetadata().getName())));
            return codec.toMap(node);
            });
        });
    }
    public Mono<Map<String, Object>> editorSnapshot(String name) {
        return client.get(Snapshot.class, name).flatMap(snapshot -> {
            var rev = snapshot.getMetadata().getAnnotations().get("aihyb.run/revision");
            if (rev == null) { return Mono.error(new PackageProblem(404, "不是稿件快照")); }
            return revision(rev).flatMap(verified -> acceptedRevision(verified.getSpec().getContentId(),rev).map(accepted -> {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) codec.tree(snapshot);
                ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("metadata")).put("version", token(rev));
                return codec.toMap(node);
            }));
        });
    }
}
