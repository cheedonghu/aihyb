package run.aihyb.pluginaihyb;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

/** Recoverable editorial operations; a historical revision is never published implicitly. */
@Service
public class OperationsService {
    private final ReactiveExtensionClient client;
    private final NativeMirror mirror;
    private final PackageService packages;
    private final PackageCodec codec;
    private final FeedPublicationEvents feeds;
    public OperationsService(ReactiveExtensionClient client, NativeMirror mirror, PackageService packages,
                             PackageCodec codec, FeedPublicationEvents feeds) {
        this.client=client;this.mirror=mirror;this.packages=packages;this.codec=codec;this.feeds=feeds;
    }
    public record Command(String version, String action, String revision) {}
    public record Status(String version, boolean published, boolean archived, String draft_revision,
                         String published_revision, String reviewed_revision, String operation_epoch) {}
    public record History(String revision, String title, String created_at, String actor,
                          boolean draft, boolean published, boolean approved) {}
    public record HistoryPage(List<History> items,long total,int page,int size) {}
    public Mono<Status> status(String id) { return packages.entry(id).then(mirror.post(id)).map(this::status); }
    private Status status(Post post) {
        var a=NativeMirror.annotations(post);
        return new Status(NativeMirror.businessVersion(post,codec),Boolean.TRUE.equals(post.getSpec().getPublish())&&!NativeMirror.archived(post)&&!NativeMirror.removed(post),
            NativeMirror.archived(post),a.get(NativeMirror.DRAFT),a.get(NativeMirror.PUBLIC),a.get(NativeMirror.APPROVED),NativeMirror.epoch(post));
    }
    private void expected(Post post, String version) {
        if (version==null || !Objects.equals(version,NativeMirror.businessVersion(post,codec))) {
            throw new PackageProblem(409,"稿件或运营状态已变化，请重新加载");
        }
    }
    public Mono<Status> operate(String id, Command command, String actor) {
        return packages.entry(id).then(Mono.defer(()->mirror.post(id).flatMap(post -> {
            expected(post,command.version());var a=NativeMirror.annotations(post);
            if(NativeMirror.removed(post)){throw new PackageProblem(409,"稿件已进入回收状态，不能写入");}
            switch (command.action()==null?"":command.action()) {
                case "withdraw" -> NativeMirror.requireActive(post);
                case "archive" -> NativeMirror.requireActive(post);
                case "unarchive" -> {
                    if (!NativeMirror.archived(post)) { throw new PackageProblem(409,"稿件尚未归档"); }
                }
                default -> throw new PackageProblem(422,"请选择撤稿、归档或取消归档");
            }
            a.put(NativeMirror.ARCHIVED,Boolean.toString(command.action().equals("archive")));
            a.put(NativeMirror.OPERATION_EPOCH,UUID.randomUUID().toString());
            NativeMirror.advance(post);
            a.put("aihyb.run/operation",command.action());a.put("aihyb.run/operator",actor);
            a.put("aihyb.run/operation-at",Instant.now().toString());
            a.remove(NativeMirror.APPROVED);a.remove("aihyb.run/reviewer");a.remove("aihyb.run/review-note");
            post.getSpec().setPublish(false);
            return client.update(post);
        }))).retryWhen(reactor.util.retry.Retry.max(4).filter(org.springframework.dao.OptimisticLockingFailureException.class::isInstance))
            .flatMap(committed -> awaitUnpublished(id)).doOnNext(feeds::published).map(this::status);
    }
    private Mono<Post> awaitUnpublished(String id) {
        return Flux.interval(Duration.ZERO,Duration.ofMillis(100)).take(100)
            .concatMap(ignored -> mirror.post(id))
            .filter(post -> !Boolean.TRUE.equals(post.getSpec().getPublish()) && !post.isPublished())
            .next().switchIfEmpty(Mono.error(new PackageProblem(503,"撤稿已提交，公开状态仍在同步；请刷新确认，勿重复发布")));
    }
    public Mono<HistoryPage> history(String id, int page, int size) {
        if (page<1 || size<1 || size>100) { return Mono.error(new PackageProblem(422,"历史分页范围无效")); }
        return packages.entry(id).then(mirror.post(id)).flatMap(post -> {
            var a=NativeMirror.annotations(post);var journal=a.getOrDefault(NativeMirror.JOURNAL,"");
            var names=journal.isBlank()?List.<String>of():List.of(journal.split(","));
            var reversed=new ArrayList<>(names);java.util.Collections.reverse(reversed);
            return Flux.fromIterable(reversed).skip((long)(page-1)*size).take(size)
                .concatMap(name -> mirror.acceptedRevision(id,name).map(rev -> new History(name,
                    codec.parse(rev.getSpec().getRawJson()).get("content").get("title").asText(),rev.getSpec().getCreatedAt(),
                    rev.getSpec().getActor(),name.equals(a.get(NativeMirror.DRAFT)),
                    name.equals(a.get(NativeMirror.PUBLIC)),name.equals(a.get(NativeMirror.APPROVED)))))
                .collectList().map(items -> new HistoryPage(items,names.size(),page,size));
        });
    }
    public Mono<PackageViews.EntryDetail> restore(String id, Command command, String actor) {
        return packages.entry(id).then(mirror.post(id)).flatMap(post -> {
            expected(post,command.version());NativeMirror.requireActive(post);
            if (command.revision()==null) { return Mono.error(new PackageProblem(422,"请选择历史修订")); }
            return mirror.acceptedRevision(id,command.revision()).flatMap(rev -> {
                var payload=(com.fasterxml.jackson.databind.node.ObjectNode)codec.parse(rev.getSpec().getRawJson());
                payload.put("submission_id","restore-"+UUID.randomUUID());
                payload.put("base_revision",NativeMirror.annotations(post).get(NativeMirror.DRAFT));
                return packages.ingest(codec.canonical(payload),actor,command.version()).then(packages.detail(id));
            });
        });
    }
    public Mono<String> preview(String id,String revision) {
        return mirror.acceptedRevision(id,revision).map(rev -> new PackageRenderer().article(codec.parse(rev.getSpec().getRawJson()),
            rev.getSpec().getRenderedHtml(),true,rev.getMetadata().getName()));
    }
}
