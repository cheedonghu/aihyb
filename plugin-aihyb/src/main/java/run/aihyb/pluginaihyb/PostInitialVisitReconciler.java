package run.aihyb.pluginaihyb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.function.IntSupplier;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.infra.ConditionStatus;

/** Adds a durable, one-time visit baseline after a post's first public publication. */
@Component
public class PostInitialVisitReconciler implements Reconciler<Reconciler.Request> {
    static final String CONFIG_NAME = "plugin-aihyb-initial-visits";
    static final String CUTOFF_KEY = "enabledAt";
    static final String INITIAL_VISIT_ANNO = "aihyb.run/initial-visit";
    static final int MIN_INITIAL_VISITS = 100;
    static final int MAX_INITIAL_VISITS = 500;
    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(10);
    private final ReactiveExtensionClient client;
    private final Clock clock;
    private final IntSupplier initialVisits;
    private Instant enabledAt;

    @Autowired
    public PostInitialVisitReconciler(ReactiveExtensionClient client) {
        this(client, Clock.systemUTC(), () -> ThreadLocalRandom.current()
            .nextInt(MIN_INITIAL_VISITS, MAX_INITIAL_VISITS + 1));
    }

    PostInitialVisitReconciler(ReactiveExtensionClient client, Clock clock,
                               IntSupplier initialVisits) {
        this.client = client;
        this.clock = clock;
        this.initialVisits = initialVisits;
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        // Persist the first activation before watching posts, including an initially empty site.
        activationTime();
        return builder.extension(new Post()).syncAllOnStart(true).build();
    }

    @Override
    public Result reconcile(Request request) {
        var cutoff = activationTime();
        client.fetch(Post.class, request.name())
            .filter(post -> eligible(post, cutoff))
            .flatMap(post -> initializeCounter(post.getMetadata().getName()))
            .block(BLOCKING_TIMEOUT);
        return Result.doNotRetry();
    }

    private synchronized Instant activationTime() {
        if (enabledAt == null) {
            enabledAt = Mono.defer(() -> client.fetch(ConfigMap.class, CONFIG_NAME)
                    .switchIfEmpty(Mono.defer(() -> {
                        var config = new ConfigMap();
                        var metadata = new Metadata();
                        metadata.setName(CONFIG_NAME);
                        config.setMetadata(metadata);
                        config.putDataItem(CUTOFF_KEY, clock.instant().toString());
                        return client.create(config);
                    })))
                .map(config -> {
                    var value = config.getData() == null ? null : config.getData().get(CUTOFF_KEY);
                    if (value == null) {
                        throw new IllegalStateException("Initial visit activation time is missing");
                    }
                    // Invalid persisted state must fail closed rather than move the cutoff.
                    return Instant.parse(value);
                })
                .retryWhen(conflictRetry())
                .block(BLOCKING_TIMEOUT);
            if (enabledAt == null) {
                throw new IllegalStateException("Initial visit activation time was not persisted");
            }
        }
        return enabledAt;
    }

    private boolean eligible(Post post, Instant cutoff) {
        var spec = post.getSpec();
        var metadata = post.getMetadata();
        if (spec == null || metadata == null || !Boolean.TRUE.equals(spec.getPublish())
            || !post.isPublished() || post.isDeleted() || Post.isRecycled(metadata)
            || !Post.isPublic(spec) || spec.getReleaseSnapshot() == null
            || spec.getReleaseSnapshot().isBlank()) {
            return false;
        }
        var annotations = metadata.getAnnotations();
        if (annotations != null && "true".equals(annotations.get(NativeMirror.ARCHIVED))) {
            return false;
        }
        var status = post.getStatus();
        if (status == null || status.getConditions() == null) {
            return false;
        }
        Instant firstPublished = null;
        for (var condition : status.getConditions()) {
            if (!ConditionStatus.TRUE.equals(condition.getStatus())) {
                continue;
            }
            var transition = condition.getLastTransitionTime();
            if ("CancelledPublish".equals(condition.getType())
                && transition != null && transition.isBefore(cutoff)) {
                return false;
            }
            if (Post.PostPhase.PUBLISHED.name().equals(condition.getType())) {
                if (transition == null) {
                    return false;
                }
                if (firstPublished == null || transition.isBefore(firstPublished)) {
                    firstPublished = transition;
                }
            }
        }
        // Halo's ConditionList preserves this timestamp across edits and republication.
        // spec.publishTime is editable and status.lastModifyTime refers to the draft snapshot.
        return firstPublished != null && !firstPublished.isBefore(cutoff)
            && !firstPublished.isAfter(clock.instant());
    }

    private Mono<Counter> initializeCounter(String postName) {
        var counterName = "posts.content.halo.run/" + postName;
        // Reuse one random draw across CAS retries, and do not draw for an existing marker.
        var baseline = Mono.fromSupplier(initialVisits::getAsInt).cache();
        return Mono.defer(() -> client.fetch(Counter.class, counterName)
                .flatMap(counter -> {
                    var annotations = counter.getMetadata().getAnnotations();
                    if (annotations != null && annotations.containsKey(INITIAL_VISIT_ANNO)) {
                        return Mono.just(counter);
                    }
                    return baseline.flatMap(value -> {
                        addBaseline(counter, value);
                        return client.update(counter);
                    });
                })
                .switchIfEmpty(Mono.defer(() -> baseline.flatMap(value -> {
                    var counter = Counter.emptyCounter(counterName);
                    addBaseline(counter, value);
                    return client.create(counter);
                }))))
            .retryWhen(conflictRetry());
    }

    private static void addBaseline(Counter counter, int baseline) {
        if (baseline < MIN_INITIAL_VISITS || baseline > MAX_INITIAL_VISITS) {
            throw new IllegalArgumentException("Initial visits must be between 100 and 500");
        }
        var annotations = counter.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            counter.getMetadata().setAnnotations(annotations);
        }
        // The marker and increment share one versioned Counter write. Never reset real visits.
        counter.setVisit(Math.addExact(counter.getVisit() == null ? 0 : counter.getVisit(), baseline));
        annotations.put(INITIAL_VISIT_ANNO, Integer.toString(baseline));
    }

    private static Retry conflictRetry() {
        return Retry.max(5)
            .filter(error -> error instanceof OptimisticLockingFailureException
                || error instanceof DataIntegrityViolationException)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
