package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.Metadata;

class NativeGuardTest {
    @Test void legacySlugRepairUsesCategoryIdentityAndPreservesCompletePublicationState() {
        var client = mock(run.halo.app.extension.ReactiveExtensionClient.class);
        var mirror = new NativeMirror(client, new PackageCodec(), mock(PackageRenderer.class), mock(FeedPublicationEvents.class));
        var post = new Post(); var metadata = new Metadata(); metadata.setName(NativeMirror.postName("sample"));
        metadata.setAnnotations(new HashMap<>(Map.of(NativeMirror.ID,"sample",NativeMirror.KIND,"pricing",NativeMirror.DRAFT,"draft-b",NativeMirror.PUBLIC,"published-a")));
        post.setMetadata(metadata); var spec = new Post.PostSpec(); post.setSpec(spec);
        spec.setCategories(java.util.List.of("prices")); spec.setTitle("Published A"); spec.setReleaseSnapshot("snapshot-a");
        var category = new run.halo.app.core.extension.content.Category(); var categoryMeta = new Metadata();
        categoryMeta.setName("actual-category-id"); category.setMetadata(categoryMeta);
        when(client.listAll(eq(run.halo.app.core.extension.content.Category.class),any(),isNull())).thenReturn(reactor.core.publisher.Flux.just(category));
        when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.just(post));
        when(client.fetch(run.halo.app.core.extension.content.Category.class,"prices")).thenReturn(Mono.empty());
        when(client.update(post)).thenReturn(Mono.just(post));
        var repaired = mirror.repairCategoryReferences(post).block();
        assertEquals(java.util.List.of("actual-category-id"),repaired.getSpec().getCategories());
        assertEquals("Published A",repaired.getSpec().getTitle()); assertEquals("snapshot-a",repaired.getSpec().getReleaseSnapshot());
        assertEquals("draft-b",NativeMirror.annotations(repaired).get(NativeMirror.DRAFT));
        assertEquals("published-a",NativeMirror.annotations(repaired).get(NativeMirror.PUBLIC));
    }
    @Test void metadataResponseNeverAcknowledgesAnotherWritersRevision() {
        var client = mock(run.halo.app.extension.ReactiveExtensionClient.class);
        var mirror = new NativeMirror(client, new PackageCodec(), mock(PackageRenderer.class), mock(FeedPublicationEvents.class));
        var post = new Post(); var metadata = new Metadata();
        metadata.setAnnotations(new HashMap<>(Map.of(NativeMirror.DRAFT, "writer-b")));
        post.setMetadata(metadata);
        when(client.fetch(Post.class, NativeMirror.postName("example"))).thenReturn(Mono.just(post));
        var error = assertThrows(PackageProblem.class, () -> mirror.editor("example", "writer-a").block());
        assertTrue(error.getMessage().contains("草稿已变化"));
        // No-op saves are bound to their original revision by the same projection.
        verify(client, never()).fetch(eq(ContentRevision.class), anyString());
    }
    @Test void ownerUserCenterWritesCannotBypassWorkflow() {
        var mirror = mock(NativeMirror.class); var post = new Post();
        when(mirror.managed("managed")).thenReturn(Mono.just(post));
        var guard = new PackageGuard(mirror, mock(run.halo.app.extension.ReactiveExtensionClient.class));
        for (var suffix : new String[]{"", "/draft", "/publish", "/unpublish"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.put("/apis/uc.api.content.halo.run/v1alpha1/posts/managed" + suffix));
            guard.filter(exchange, ignored -> Mono.error(new AssertionError("Core write handler was reached"))).block();
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }
    @Test void ordinaryPostsPassThroughOnce() {
        var mirror = mock(NativeMirror.class); when(mirror.managed("ordinary")).thenReturn(Mono.empty());
        var count = new java.util.concurrent.atomic.AtomicInteger();
        new PackageGuard(mirror, mock(run.halo.app.extension.ReactiveExtensionClient.class)).filter(MockServerWebExchange.from(MockServerHttpRequest.put("/apis/content.halo.run/v1alpha1/posts/ordinary")),
            exchange -> { count.incrementAndGet(); return Mono.empty(); }).block();
        assertEquals(1, count.get());
    }
    @Test void nativeContentReadRedirectsToVersionedPrivateEndpoint() {
        var mirror = mock(NativeMirror.class); when(mirror.managed("managed")).thenReturn(Mono.just(new Post()));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/apis/api.console.halo.run/v1alpha1/posts/managed/head-content"));
        new PackageGuard(mirror, mock(run.halo.app.extension.ReactiveExtensionClient.class)).filter(exchange, ignored -> Mono.error(new AssertionError("Unversioned content handler was reached"))).block();
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, exchange.getResponse().getStatusCode());
        assertEquals(PackageService.ADMIN + "/native-posts/managed/content", exchange.getResponse().getHeaders().getLocation().toString());
    }
    @Test void internalEscapedSnapshotCanExceedPackageInputSize() {
        var snapshot = new Snapshot(); var metadata = new Metadata(); metadata.setName("large");
        metadata.setAnnotations(new HashMap<>(Map.of("key", "value"))); snapshot.setMetadata(metadata);
        var spec = new Snapshot.SnapShotSpec(); snapshot.setSpec(spec);
        spec.setRawPatch("&".repeat(30000)); spec.setContentPatch("&amp;".repeat(30000));
        var codec = new PackageCodec();
        assertTrue(codec.serialize(snapshot).length() > PackageCodec.MAX_BYTES);
        assertEquals(spec.getContentPatch(), codec.toMap(codec.tree(snapshot)).get("spec") instanceof Map<?, ?> map ? map.get("contentPatch") : null);
    }
}
