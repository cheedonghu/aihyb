package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.infra.Condition;
import run.halo.app.infra.ConditionStatus;

class PostInitialVisitReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-11-02T03:30:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(60);
    private static final String POST_NAME = "ordinary-post";
    private static final String COUNTER_NAME = "posts.content.halo.run/" + POST_NAME;

    @ParameterizedTest
    @ValueSource(ints = {100, 500})
    void createsCounterWithInclusiveBoundaryAndAtomicMarker(int baseline) {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));

        reconcile(store.reconciler(() -> baseline), POST_NAME);

        var counter = store.counters.get(COUNTER_NAME);
        assertEquals(baseline, counter.getVisit());
        assertEquals(Integer.toString(baseline), marker(counter));
        assertEquals(0, counter.getUpvote());
        assertNull(counter.getDownvote());
        assertEquals(0, counter.getTotalComment());
        assertEquals(0, counter.getApprovedComment());
        assertEquals(1, store.counterWrites);
    }

    @Test
    void addsToRealVisitsAndPreservesOtherStatisticsAndMetadata() {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        var counter = Counter.emptyCounter(COUNTER_NAME);
        counter.getMetadata().setVersion(7L);
        counter.getMetadata().setAnnotations(new HashMap<>(Map.of("another.plugin/flag", "keep")));
        counter.getMetadata().setLabels(new HashMap<>(Map.of("owner", "keep")));
        counter.setVisit(13);
        counter.setUpvote(3);
        counter.setDownvote(2);
        counter.setTotalComment(9);
        counter.setApprovedComment(8);
        store.counters.put(COUNTER_NAME, counter);

        reconcile(store.reconciler(() -> 200), POST_NAME);

        var saved = store.counters.get(COUNTER_NAME);
        assertEquals(213, saved.getVisit());
        assertEquals("200", marker(saved));
        assertEquals("keep", saved.getMetadata().getAnnotations().get("another.plugin/flag"));
        assertEquals("keep", saved.getMetadata().getLabels().get("owner"));
        assertEquals(3, saved.getUpvote());
        assertEquals(2, saved.getDownvote());
        assertEquals(9, saved.getTotalComment());
        assertEquals(8, saved.getApprovedComment());
    }

    @Test
    void repeatReconciliationAndRepublicationNeverAddAgain() {
        var store = new Store();
        var post = published(CUTOFF);
        store.posts.put(POST_NAME, post);
        var draws = new AtomicInteger();
        var reconciler = store.reconciler(() -> { draws.incrementAndGet(); return 123; });
        reconcile(reconciler, POST_NAME);
        store.counters.get(COUNTER_NAME).setVisit(130);
        post.getSpec().setReleaseSnapshot("edited-release");
        post.getStatus().getConditions().addAndEvictFIFO(publishedCondition(NOW));

        reconcile(reconciler, POST_NAME);
        reconcile(reconciler, POST_NAME);

        assertEquals(130, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals("123", marker(store.counters.get(COUNTER_NAME)));
        assertEquals(1, store.counterWrites);
        assertEquals(1, draws.get());
    }

    @Test
    void skipsPreviouslyPublishedArticleEvenIfDisplayDateIsChanged() {
        var store = new Store();
        var post = published(CUTOFF.minusSeconds(1));
        post.getSpec().setPublishTime(NOW);
        store.posts.put(POST_NAME, post);

        reconcile(store.reconciler(() -> 100), POST_NAME);

        assertTrue(store.counters.isEmpty());
        assertEquals(0, store.counterWrites);
    }

    @Test
    void skipsOldArticleWithdrawnAndRepublishedAfterActivation() {
        var store = new Store();
        var post = published(CUTOFF.minusSeconds(1));
        var conditions = post.getStatus().getConditions();
        conditions.addAndEvictFIFO(Condition.builder().type("CancelledPublish")
            .status(ConditionStatus.TRUE).lastTransitionTime(CUTOFF.plusSeconds(10)).build());
        conditions.addAndEvictFIFO(publishedCondition(NOW));
        post.getSpec().setPublishTime(NOW);
        store.posts.put(POST_NAME, post);
        assertEquals(CUTOFF.minusSeconds(1), conditions.peekFirst().getLastTransitionTime());

        reconcile(store.reconciler(() -> 100), POST_NAME);

        assertTrue(store.counters.isEmpty());
    }

    @Test
    void initializesOldDraftOnFirstPublicationEvenWithBackdatedDisplayAndSnapshotDates() {
        var store = new Store();
        var post = published(CUTOFF);
        var old = CUTOFF.minusSeconds(30 * 86400L);
        post.getMetadata().setCreationTimestamp(old);
        post.getSpec().setPublishTime(old);
        post.getStatus().setLastModifyTime(old);
        post.getMetadata().setLabels(new HashMap<>(Map.of(Post.PUBLISHED_LABEL, "true",
            "aihyb.run/managed", "true")));
        store.posts.put(POST_NAME, post);

        reconcile(store.reconciler(() -> 321), POST_NAME);

        assertEquals(321, store.counters.get(COUNTER_NAME).getVisit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "unreconciled", "private", "internal", "deleted",
        "deleting", "recycled", "archived", "no-release", "no-spec", "no-status",
        "no-conditions", "missing-time", "false-condition", "future"})
    void skipsPostsThatHaveNotCompletedEligiblePublicPublication(String state) {
        var store = new Store();
        var post = published(CUTOFF);
        switch (state) {
            case "draft" -> post.getSpec().setPublish(false);
            case "unreconciled" -> post.getMetadata().getLabels().put(Post.PUBLISHED_LABEL, "false");
            case "private" -> post.getSpec().setVisible(Post.VisibleEnum.PRIVATE);
            case "internal" -> post.getSpec().setVisible(Post.VisibleEnum.INTERNAL);
            case "deleted" -> post.getSpec().setDeleted(true);
            case "deleting" -> post.getMetadata().setDeletionTimestamp(NOW);
            case "recycled" -> post.getMetadata().getLabels().put(Post.DELETED_LABEL, "true");
            case "archived" -> post.getMetadata().setAnnotations(Map.of(NativeMirror.ARCHIVED, "true"));
            case "no-release" -> post.getSpec().setReleaseSnapshot("");
            case "no-spec" -> post.setSpec(null);
            case "no-status" -> post.setStatus(null);
            case "no-conditions" -> post.getStatus().setConditions(null);
            case "missing-time" -> post.getStatus().getConditions().peekFirst().setLastTransitionTime(null);
            case "false-condition" -> post.getStatus().getConditions().peekFirst().setStatus(ConditionStatus.FALSE);
            case "future" -> post.getStatus().getConditions().peekFirst().setLastTransitionTime(NOW.plusSeconds(1));
            default -> fail("Unexpected test state");
        }
        store.posts.put(POST_NAME, post);
        var draws = new AtomicInteger();

        reconcile(store.reconciler(() -> { draws.incrementAndGet(); return 100; }), POST_NAME);
        reconcile(store.reconciler(() -> 100), "absent");

        assertTrue(store.counters.isEmpty());
        assertEquals(0, store.counterWrites);
        assertEquals(0, draws.get());
    }

    @Test
    void retriesVersionConflictUsingLatestRealVisitIncrementAndOnlyOneRandomDraw() {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        var counter = Counter.emptyCounter(COUNTER_NAME);
        counter.getMetadata().setVersion(1L);
        counter.setVisit(7);
        store.counters.put(COUNTER_NAME, counter);
        store.beforeCounterUpdate = () -> {
            var current = store.counters.get(COUNTER_NAME);
            current.setVisit(current.getVisit() + 9);
            current.getMetadata().setVersion(current.getMetadata().getVersion() + 1);
        };
        var draws = new AtomicInteger();

        reconcile(store.reconciler(() -> { draws.incrementAndGet(); return 100; }), POST_NAME);

        assertEquals(116, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals("100", marker(store.counters.get(COUNTER_NAME)));
        assertEquals(2, store.counterUpdateAttempts);
        assertEquals(1, store.counterWrites);
        assertEquals(1, draws.get());
    }

    @Test
    void retriesCounterCreationRaceWithoutLosingIncomingVisits() {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        store.beforeCounterCreate = () -> {
            var realVisits = Counter.emptyCounter(COUNTER_NAME);
            realVisits.getMetadata().setVersion(1L);
            realVisits.setVisit(5);
            store.counters.put(COUNTER_NAME, realVisits);
        };

        reconcile(store.reconciler(() -> 500), POST_NAME);

        assertEquals(505, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals("500", marker(store.counters.get(COUNTER_NAME)));
        assertEquals(1, store.counterUpdateAttempts);
        assertEquals(1, store.counterWrites);
    }

    @Test
    void concurrentInitializerWinnerPreventsSecondBaseline() {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        store.beforeCounterCreate = () -> {
            var winner = Counter.emptyCounter(COUNTER_NAME);
            winner.getMetadata().setVersion(1L);
            winner.setVisit(451);
            winner.getMetadata().setAnnotations(Map.of(PostInitialVisitReconciler.INITIAL_VISIT_ANNO, "450"));
            store.counters.put(COUNTER_NAME, winner);
        };

        reconcile(store.reconciler(() -> 100), POST_NAME);

        assertEquals(451, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals("450", marker(store.counters.get(COUNTER_NAME)));
        assertEquals(0, store.counterUpdateAttempts);
        assertEquals(0, store.counterWrites);
    }

    @Test
    void initializationFailureLeavesNoMarkerAndCanRecoverAfterRestart() {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        var counter = Counter.emptyCounter(COUNTER_NAME);
        counter.getMetadata().setVersion(1L);
        counter.setVisit(8);
        store.counters.put(COUNTER_NAME, counter);
        store.counterFailure = new IllegalStateException("Storage unavailable");

        assertThrows(IllegalStateException.class,
            () -> reconcile(store.reconciler(() -> 100), POST_NAME));
        assertNull(marker(store.counters.get(COUNTER_NAME)));
        assertEquals(8, store.counters.get(COUNTER_NAME).getVisit());
        store.counterFailure = null;

        reconcile(store.reconciler(() -> 200), POST_NAME);

        assertEquals(208, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals("200", marker(store.counters.get(COUNTER_NAME)));
    }

    @Test
    void restoresActivationCutoffAndCatchesMissedPublicationsAfterRestart() {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        reconcile(store.reconciler(() -> 100), POST_NAME);
        var missed = published(CUTOFF.plusSeconds(10));
        missed.getMetadata().setName("missed");
        missed.getMetadata().setCreationTimestamp(CUTOFF.minusSeconds(86400));
        store.posts.put("missed", missed);
        var draws = new AtomicInteger();
        var restarted = new PostInitialVisitReconciler(store.client,
            Clock.fixed(NOW.plusSeconds(86400), ZoneOffset.UTC),
            () -> { draws.incrementAndGet(); return 300; });

        reconcile(restarted, POST_NAME);
        reconcile(restarted, "missed");

        assertEquals(100, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals(300, store.counters.get("posts.content.halo.run/missed").getVisit());
        assertEquals(CUTOFF.toString(), store.config.getData().get(PostInitialVisitReconciler.CUTOFF_KEY));
        assertEquals(0, store.configCreates);
        assertEquals(1, draws.get());
    }

    @Test
    void persistsCutoffBeforeWatchingAnInitiallyEmptySiteAndScansAllPostsOnStart() {
        var store = new Store();
        store.config = null;
        var builder = mock(ControllerBuilder.class);
        var controller = mock(Controller.class);
        when(builder.extension(any(Post.class))).thenReturn(builder);
        when(builder.syncAllOnStart(true)).thenReturn(builder);
        when(builder.build()).thenReturn(controller);

        assertSame(controller, store.reconciler(() -> 100).setupWith(builder));

        var order = inOrder(store.client, builder);
        order.verify(store.client).create(any(ConfigMap.class));
        order.verify(builder).extension(any(Post.class));
        order.verify(builder).syncAllOnStart(true);
        assertEquals(NOW.toString(), store.config.getData().get(PostInitialVisitReconciler.CUTOFF_KEY));
        assertEquals(1, store.configCreates);
        store.posts.put(POST_NAME, published(NOW));
        reconcile(store.reconciler(() -> 100), POST_NAME);
        assertEquals(100, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals(1, store.configCreates);
    }

    @Test
    void activationCreationRaceUsesThePersistedWinnerRatherThanANewCutoff() {
        var store = new Store();
        store.config = null;
        store.beforeConfigCreate = () -> store.config = activation(CUTOFF);
        store.posts.put(POST_NAME, published(CUTOFF));

        reconcile(store.reconciler(() -> 100), POST_NAME);

        assertEquals(CUTOFF.toString(), store.config.getData().get(PostInitialVisitReconciler.CUTOFF_KEY));
        assertEquals(100, store.counters.get(COUNTER_NAME).getVisit());
        assertEquals(0, store.configCreates);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid"})
    void invalidPersistedActivationFailsClosed(String state) {
        var store = new Store();
        store.posts.put(POST_NAME, published(CUTOFF));
        if (state.equals("missing")) {
            store.config.setData(null);
        } else {
            store.config.putDataItem(PostInitialVisitReconciler.CUTOFF_KEY, "invalid");
        }

        assertThrows(RuntimeException.class, () -> reconcile(store.reconciler(() -> 100), POST_NAME));

        assertTrue(store.counters.isEmpty());
        assertEquals(0, store.configCreates);
    }

    private static void reconcile(PostInitialVisitReconciler reconciler, String name) {
        assertFalse(reconciler.reconcile(new Reconciler.Request(name)).reEnqueue());
    }

    private static String marker(Counter counter) {
        var annotations = counter.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(PostInitialVisitReconciler.INITIAL_VISIT_ANNO);
    }

    private static Post published(Instant firstPublished) {
        var post = new Post();
        var metadata = new Metadata();
        metadata.setName(POST_NAME);
        metadata.setCreationTimestamp(CUTOFF.minusSeconds(86400));
        metadata.setLabels(new HashMap<>(Map.of(Post.PUBLISHED_LABEL, "true")));
        post.setMetadata(metadata);
        var spec = new Post.PostSpec();
        spec.setPublish(true);
        spec.setDeleted(false);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setReleaseSnapshot("release");
        spec.setPublishTime(firstPublished);
        post.setSpec(spec);
        post.setStatus(new Post.PostStatus());
        post.getStatus().getConditionsOrDefault().addAndEvictFIFO(publishedCondition(firstPublished));
        return post;
    }

    private static Condition publishedCondition(Instant time) {
        return Condition.builder().type(Post.PostPhase.PUBLISHED.name()).reason("Published")
            .message("Post published successfully.").status(ConditionStatus.TRUE)
            .lastTransitionTime(time).build();
    }

    private static ConfigMap activation(Instant time) {
        var config = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(PostInitialVisitReconciler.CONFIG_NAME);
        metadata.setVersion(1L);
        config.setMetadata(metadata);
        config.putDataItem(PostInitialVisitReconciler.CUTOFF_KEY, time.toString());
        return config;
    }

    /** Simulates detached reads and versioned writes, with deterministic competing writers. */
    private static class Store {
        final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        final Map<String, Post> posts = new HashMap<>();
        final Map<String, Counter> counters = new HashMap<>();
        ConfigMap config = activation(CUTOFF);
        Runnable beforeCounterCreate;
        Runnable beforeCounterUpdate;
        Runnable beforeConfigCreate;
        RuntimeException counterFailure;
        int counterWrites;
        int counterUpdateAttempts;
        int configCreates;

        Store() {
            when(client.fetch(eq(Post.class), anyString())).thenAnswer(invocation ->
                Mono.defer(() -> Mono.justOrEmpty(posts.get(invocation.<String>getArgument(1)))));
            when(client.fetch(eq(ConfigMap.class), anyString())).thenAnswer(invocation ->
                Mono.defer(() -> Mono.justOrEmpty(config)));
            when(client.fetch(eq(Counter.class), anyString())).thenAnswer(invocation ->
                Mono.defer(() -> Mono.justOrEmpty(copy(counters.get(invocation.<String>getArgument(1))))));
            when(client.create(any(ConfigMap.class))).thenAnswer(invocation -> Mono.defer(() -> {
                if (beforeConfigCreate != null) {
                    var competingWrite = beforeConfigCreate;
                    beforeConfigCreate = null;
                    competingWrite.run();
                }
                if (config != null) {
                    return Mono.error(new DuplicateKeyException("Activation already exists"));
                }
                config = invocation.getArgument(0);
                config.getMetadata().setVersion(1L);
                configCreates++;
                return Mono.just(config);
            }));
            when(client.create(any(Counter.class))).thenAnswer(invocation -> Mono.defer(() -> {
                if (beforeCounterCreate != null) {
                    var competingWrite = beforeCounterCreate;
                    beforeCounterCreate = null;
                    competingWrite.run();
                }
                var counter = invocation.<Counter>getArgument(0);
                var name = counter.getMetadata().getName();
                if (counters.containsKey(name)) {
                    return Mono.error(new DuplicateKeyException("Counter already exists"));
                }
                if (counterFailure != null) {
                    return Mono.error(counterFailure);
                }
                var saved = copy(counter);
                saved.getMetadata().setVersion(1L);
                counters.put(name, saved);
                counterWrites++;
                return Mono.just(copy(saved));
            }));
            when(client.update(any(Counter.class))).thenAnswer(invocation -> Mono.defer(() -> {
                counterUpdateAttempts++;
                if (beforeCounterUpdate != null) {
                    var competingWrite = beforeCounterUpdate;
                    beforeCounterUpdate = null;
                    competingWrite.run();
                }
                var counter = invocation.<Counter>getArgument(0);
                var name = counter.getMetadata().getName();
                var current = counters.get(name);
                if (current == null || !Objects.equals(current.getMetadata().getVersion(),
                    counter.getMetadata().getVersion())) {
                    return Mono.error(new OptimisticLockingFailureException("Counter version changed"));
                }
                if (counterFailure != null) {
                    return Mono.error(counterFailure);
                }
                var saved = copy(counter);
                saved.getMetadata().setVersion(current.getMetadata().getVersion() + 1);
                counters.put(name, saved);
                counterWrites++;
                return Mono.just(copy(saved));
            }));
        }

        PostInitialVisitReconciler reconciler(IntSupplier random) {
            return new PostInitialVisitReconciler(client, Clock.fixed(NOW, ZoneOffset.UTC), random);
        }

        private static Counter copy(Counter source) {
            if (source == null) {
                return null;
            }
            var copy = Counter.emptyCounter(source.getMetadata().getName());
            var metadata = copy.getMetadata();
            var original = source.getMetadata();
            metadata.setVersion(original.getVersion());
            metadata.setCreationTimestamp(original.getCreationTimestamp());
            metadata.setDeletionTimestamp(original.getDeletionTimestamp());
            metadata.setAnnotations(original.getAnnotations() == null ? null
                : new HashMap<>(original.getAnnotations()));
            metadata.setLabels(original.getLabels() == null ? null : new HashMap<>(original.getLabels()));
            copy.setVisit(source.getVisit());
            copy.setUpvote(source.getUpvote());
            copy.setDownvote(source.getDownvote());
            copy.setTotalComment(source.getTotalComment());
            copy.setApprovedComment(source.getApprovedComment());
            return copy;
        }
    }
}
