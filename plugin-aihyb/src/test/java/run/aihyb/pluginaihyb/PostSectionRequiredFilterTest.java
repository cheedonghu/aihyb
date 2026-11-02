package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class PostSectionRequiredFilterTest {
    private static final String CONSOLE = "/apis/api.console.halo.run/v1alpha1/posts";
    private static final String RAW = "/apis/content.halo.run/v1alpha1/posts";
    private static final String UC = "/apis/uc.api.content.halo.run/v1alpha1/posts";
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MockEnvironment environment = new MockEnvironment();
    private final PostSectionRequiredFilter filter = new PostSectionRequiredFilter(client, environment);

    PostSectionRequiredFilterTest() {
        when(client.fetch(eq(Category.class), anyString())).thenReturn(Mono.empty());
        when(client.fetch(eq(Post.class), anyString())).thenReturn(Mono.empty());
    }

    @Test void draftsMayHaveNoCategoriesAtEveryCreateAndUpdateBodyShape() throws Exception {
        for (var base : List.of(CONSOLE, RAW, UC)) {
            var draft = post(false, List.of());
            pass(HttpMethod.POST, base, body(base, draft));
            if (base.equals(UC)) { stored(draft); }
            pass(HttpMethod.PUT, base + "/sample", body(base, draft));
        }
        verify(client, never()).fetch(eq(Category.class), anyString());
    }

    @Test void everySectionResolvesByCategoryIdentityAndAllowsMultipleOtherCategories() throws Exception {
        for (var slug : List.of("reviews", "leaderboards", "prices", "tutorials", "news")) {
            category("id-" + slug, slug);
            var post = post(true, List.of("other", "id-" + slug));
            for (var base : List.of(CONSOLE, RAW, UC)) {
                pass(HttpMethod.POST, base, body(base, post));
                if (base.equals(UC)) { stored(post); }
                pass(HttpMethod.PUT, base + "/sample", body(base, post));
            }
        }
        var multi = post(true, List.of("id-reviews", "other", "id-news"));
        pass(HttpMethod.PUT, RAW + "/sample?fieldManager=editor", body(RAW, multi));
        assertEquals(List.of("id-reviews", "other", "id-news"), multi.getSpec().getCategories());
    }

    @Test void emptyNullInvalidDanglingAndDeletedCategoriesAreRejected() throws Exception {
        category("other", "other");
        category("wrong-slug", "review");
        var deleted = category("deleted", "reviews");
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        category("missing-slug", null);
        for (var categories : List.of(List.<String>of(), List.of("other"), List.of("wrong-slug"),
            List.of("dangling"), List.of("deleted"), List.of("missing-slug"), List.of("reviews"))) {
            reject(HttpMethod.POST, RAW, body(RAW, post(true, categories)), 422);
        }
        reject(HttpMethod.POST, RAW, body(RAW, post(true, null)), 422);
    }

    @Test void publishedEditsAndFutureScheduledPostsCannotClearCategories() throws Exception {
        var scheduled = post(true, List.of());
        scheduled.getSpec().setPublishTime(Instant.parse("2099-01-01T00:00:00Z"));
        for (var base : List.of(CONSOLE, RAW)) {
            reject(HttpMethod.POST, base, body(base, scheduled), 422);
            reject(HttpMethod.PUT, base + "/sample", body(base, scheduled), 422);
        }
    }

    @Test void explicitPublishAndRevertReadStoredCategoriesWithoutParsingTheirBodies() throws Exception {
        var draft = post(false, List.of());
        stored(draft);
        reject(HttpMethod.PUT, CONSOLE + "/sample/publish?async=true&headSnapshot=original", new byte[0], 422);
        reject(HttpMethod.PUT, CONSOLE + "/sample/revert-content", bytes("{\"snapshotName\":\"history\"}"), 422);
        category("section-id", "news");
        draft.getSpec().setCategories(List.of("section-id"));
        pass(HttpMethod.PUT, CONSOLE + "/sample/publish?async=true&headSnapshot=original", new byte[0]);
        pass(HttpMethod.PUT, CONSOLE + "/sample/revert-content", bytes("{ \"snapshotName\" : \"history\" }"));
    }

    @Test void userCenterUsesStoredPublishAndDeletedWithIncomingCategories() throws Exception {
        var published = post(true, List.of("old-section"));
        stored(published);
        var incoming = post(false, List.of());
        incoming.getSpec().setDeleted(true);
        reject(HttpMethod.PUT, UC + "/sample", body(UC, incoming), 422);
        category("new-section", "tutorials");
        incoming.getSpec().setCategories(List.of("new-section"));
        pass(HttpMethod.PUT, UC + "/sample", body(UC, incoming));
        assertTrue(published.getSpec().getPublish());
        assertEquals(List.of("old-section"), published.getSpec().getCategories());
        assertFalse(published.getSpec().getDeleted());
        stored(post(false, List.of()));
        pass(HttpMethod.PUT, UC + "/sample", body(UC, post(true, List.of())));
    }

    @Test void userCenterPublishChecksStoredCategories() throws Exception {
        stored(post(false, List.of()));
        reject(HttpMethod.PUT, UC + "/sample/publish", new byte[0], 422);
        category("id", "prices");
        stored(post(false, List.of("id")));
        pass(HttpMethod.PUT, UC + "/sample/publish", new byte[0]);
    }

    @Test void userCenterOtherOwnersGoDirectlyToCoreWithoutCategoryDisclosure() {
        var other = post(true, List.of());
        other.getSpec().setOwner("someone-else");
        stored(other);
        for (var suffix : List.of("", "/publish")) {
            var exchange = exchange(HttpMethod.PUT, UC + "/sample" + suffix, bytes("not-json"), MediaType.APPLICATION_JSON);
            var called = new AtomicInteger();
            filter.filter(exchange, original -> {
                assertSame(exchange, original);
                called.incrementAndGet();
                return Mono.empty();
            }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication())).block();
            assertEquals(1, called.get());
            assertNull(exchange.getResponse().getStatusCode());
        }
        verify(client, never()).fetch(eq(Category.class), anyString());
    }

    @Test void userCenterWithoutSecurityContextAlsoLeavesOwnerEnforcementToCore() {
        stored(post(true, List.of()));
        var exchange = exchange(HttpMethod.PUT, UC + "/sample/publish", new byte[0], MediaType.APPLICATION_JSON);
        var called = new AtomicInteger();
        filter.filter(exchange, ignored -> { called.incrementAndGet(); return Mono.empty(); }).block();
        assertEquals(1, called.get());
        verify(client, never()).fetch(eq(Category.class), anyString());
    }

    @Test void rawJsonPatchValidatesFullResultIncludingRemoveSpecAndDocumentReplacement() throws Exception {
        category("section", "reviews");
        stored(post(true, List.of("section")));
        for (var patch : List.of(
            "[{\"op\":\"remove\",\"path\":\"/spec/categories\"}]",
            "[{\"op\":\"replace\",\"path\":\"/spec\",\"value\":{\"publish\":true,\"deleted\":false}}]",
            "[{\"op\":\"replace\",\"path\":\"\",\"value\":" + mapper.writeValueAsString(post(true, List.of())) + "}]",
            "[{\"op\":\"move\",\"from\":\"/spec/categories\",\"path\":\"/spec/tags\"}]")) {
            rejectPatch(patch, 422);
        }
        assertEquals(List.of("section"), client.fetch(Post.class, "sample").block().getSpec().getCategories());
    }

    @Test void rawJsonPatchSupportsTestCopyMoveAndDraftTransitionsAndReplaysExactly() {
        category("section", "news");
        var post = post(true, List.of("section"));
        post.getSpec().setTags(List.of("section"));
        stored(post);
        for (var patch : List.of(
            "[ {\"op\":\"test\",\"path\":\"/spec/publish\",\"value\":true}, {\"op\":\"copy\",\"from\":\"/spec/tags\",\"path\":\"/spec/categories\"} ]",
            "[{\"op\":\"move\",\"from\":\"/spec/tags/0\",\"path\":\"/spec/categories/-\"}]",
            "[{\"op\":\"replace\",\"path\":\"/spec/publish\",\"value\":false},{\"op\":\"remove\",\"path\":\"/spec/categories\"}]")) {
            pass(HttpMethod.PATCH, RAW + "/sample?fieldManager=original", bytes(patch),
                MediaType.valueOf("application/json-patch+json;charset=UTF-8"));
        }
    }

    @Test void rawPostPutAndPatchAcceptNativeLowercaseVisibilityEnums() throws Exception {
        category("section", "news");
        stored(post(true, List.of("section")));
        for (var visible : List.of("public", "private")) {
            var root = mapper.valueToTree(post(true, List.of("section")));
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("spec")).put("visible", visible);
            var incoming = mapper.writeValueAsBytes(root);
            pass(HttpMethod.POST, RAW, incoming);
            pass(HttpMethod.PUT, RAW + "/sample", incoming);
            pass(HttpMethod.PATCH, RAW + "/sample",
                bytes("[{\"op\":\"add\",\"path\":\"/spec/visible\",\"value\":\"" + visible + "\"}]"),
                MediaType.valueOf("application/json-patch+json"));
        }
    }

    @Test void rawPatchDocumentExcludesHelperPropertiesAndDoesNotInitializeStoredStatus() {
        category("section", "news");
        var persisted = post(true, List.of("section"));
        stored(persisted);
        for (var helper : List.of("statusOrDefault", "deleted", "published")) {
            rejectPatch("[{\"op\":\"remove\",\"path\":\"/" + helper + "\"}]", 400);
        }
        pass(HttpMethod.PATCH, RAW + "/sample",
            bytes("[{\"op\":\"test\",\"path\":\"/spec/publish\",\"value\":true}]"),
            MediaType.valueOf("application/json-patch+json"));
        assertNull(persisted.getStatus());
        assertEquals(List.of("section"), persisted.getSpec().getCategories());
    }

    @Test void rawPatchStatusExcludesConditionsHelperWithoutInitializingStoredConditions() {
        category("section", "news");
        var persisted = post(true, List.of("section"));
        var status = spy(new Post.PostStatus());
        status.setPhase("PUBLISHED");
        persisted.setStatus(status);
        stored(persisted);
        rejectPatch("[{\"op\":\"remove\",\"path\":\"/status/conditionsOrDefault\"}]", 400);
        pass(HttpMethod.PATCH, RAW + "/sample",
            bytes("[{\"op\":\"test\",\"path\":\"/status/phase\",\"value\":\"PUBLISHED\"}]"),
            MediaType.valueOf("application/json-patch+json"));
        assertSame(status, persisted.getStatus());
        assertNull(status.getConditions());
        verify(status, never()).getConditionsOrDefault();
    }

    @Test void malformedPatchProducesGenericBadRequestWithoutMetadata() {
        stored(post(true, List.of()));
        rejectPatch("[{\"op\":\"remove\",\"path\":\"/missing\"}]", 400);
    }

    @Test void nativeCompatibleWildcardPatchTypesCannotClearPublishedCategories() {
        category("section", "news");
        stored(post(true, List.of("section")));
        for (var type : List.of("application/*+json", "application/*", "*/*")) {
            for (var patch : List.of(
                "[{\"op\":\"remove\",\"path\":\"/spec/categories\"}]",
                "[{\"op\":\"replace\",\"path\":\"/spec\",\"value\":{\"publish\":true,\"categories\":[]}}]")) {
                reject(HttpMethod.PATCH, RAW + "/sample", bytes(patch), 422, MediaType.valueOf(type));
            }
        }
    }

    @Test void incompatibleRawPatchContentTypesRemainWithNativeHandling() {
        pass(HttpMethod.PATCH, RAW + "/sample", bytes("anything"), MediaType.APPLICATION_JSON);
        pass(HttpMethod.PATCH, RAW + "/sample", bytes("anything"), MediaType.valueOf("application/merge-patch+json"));
        verifyNoInteractionsBeyondDefaultStubs();
    }

    @Test void consoleUpdateRejectsPathBodyIdentityMismatchBeforeAnyCoreWrite() throws Exception {
        var wrong = post(false, List.of());
        wrong.getMetadata().setName("different");
        reject(HttpMethod.PUT, CONSOLE + "/sample", body(CONSOLE, wrong), 400);
        pass(HttpMethod.PUT, RAW + "/sample", body(RAW, wrong));
        verifyNoInteractionsBeyondDefaultStubs();
    }

    @Test void bodyReplayPreservesWhitespaceUtf8VersionQueryAndLargeNativeContent() {
        category("section", "news");
        var input = bytes(" { \"post\" : {\"apiVersion\":\"content.halo.run/v1alpha1\",\"kind\":\"Post\","
            + "\"metadata\":{\"name\":\"sample\",\"version\":37},\"spec\":{\"publish\":true,\"categories\":[\"section\"]}},"
            + "\"content\":{\"raw\":\"中文正文" + "长".repeat(150000) + "\",\"rawType\":\"markdown\"} }\n");
        assertTrue(input.length > PackageCodec.MAX_BYTES);
        pass(HttpMethod.PUT, CONSOLE + "/sample?async=true&headSnapshot=a%2Fb", input);
    }

    @Test void declaredAndChunkedBodiesRespectTheNativeConfiguredLimit() {
        var small = new PostSectionRequiredFilter(client,
            new MockEnvironment().withProperty("spring.http.codecs.max-in-memory-size", "1KB"));
        var body = bytes("x".repeat(1025));
        for (var declared : List.of(false, true)) {
            var builder = MockServerHttpRequest.post(RAW).contentType(MediaType.APPLICATION_JSON);
            if (declared) { builder.header("Content-Length", "1025"); }
            var exchange = MockServerWebExchange.from(builder.body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body))));
            small.filter(exchange, ignored -> Mono.error(new AssertionError("Oversized request passed"))).block();
            assertEquals(413, exchange.getResponse().getStatusCode().value());
        }
    }

    @Test void forgedManagedAnnotationsNeverBypassTheRule() throws Exception {
        var forged = post(true, List.of());
        forged.getMetadata().setAnnotations(new HashMap<>(Map.of(NativeMirror.ID, "forged", "managed", "true")));
        reject(HttpMethod.POST, RAW, body(RAW, forged), 422);
        reject(HttpMethod.PUT, CONSOLE + "/sample", body(CONSOLE, forged), 422);
    }

    @Test void existingPackageGuardRedirectsPersistedManagedPostsBeforeThisFilter() {
        var mirror = mock(NativeMirror.class);
        when(mirror.managed("sample")).thenReturn(Mono.just(post(true, List.of())));
        var guard = new PackageGuard(mirror, client);
        var exchange = exchange(HttpMethod.PUT, CONSOLE + "/sample/publish?async=true", new byte[0], MediaType.APPLICATION_JSON);
        guard.filter(exchange, next -> filter.filter(next,
            ignored -> Mono.error(new AssertionError("Managed publish reached core")))).block();
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getHeaders().getLocation().toString().contains("/native-posts/sample/publish?async=true"));
        verifyNoInteractionsBeyondDefaultStubs();
    }

    @Test void createCannotUseDeletionTimestampClearedByCoreToSkipSectionValidation() throws Exception {
        category("section", "news");
        for (var base : List.of(CONSOLE, RAW, UC)) {
            var incoming = post(true, List.of());
            incoming.getMetadata().setDeletionTimestamp(Instant.parse("2020-01-01T00:00:00Z"));
            reject(HttpMethod.POST, base, body(base, incoming), 422);
            incoming.getSpec().setCategories(List.of("section"));
            pass(HttpMethod.POST, base, body(base, incoming));
            incoming.getSpec().setPublish(false);
            incoming.getSpec().setCategories(List.of());
            pass(HttpMethod.POST, base, body(base, incoming));
        }
    }

    @Test void createKeepsExplicitSpecDeletedStatePreservedByCore() throws Exception {
        var incoming = post(true, List.of());
        incoming.getSpec().setDeleted(true);
        incoming.getMetadata().setDeletionTimestamp(Instant.parse("2020-01-01T00:00:00Z"));
        for (var base : List.of(CONSOLE, RAW, UC)) {
            pass(HttpMethod.POST, base, body(base, incoming));
        }
        verify(client, never()).fetch(eq(Category.class), anyString());
    }

    @Test void deletedCandidatesAreOutsidePublicationValidation() throws Exception {
        var deleted = post(true, List.of());
        deleted.getSpec().setDeleted(true);
        pass(HttpMethod.PUT, RAW + "/sample", body(RAW, deleted));
        deleted.getSpec().setDeleted(false);
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        pass(HttpMethod.PUT, RAW + "/sample", body(RAW, deleted));
        verify(client, never()).fetch(eq(Category.class), anyString());
    }

    @Test void unrelatedNativeOperationsAndInternalPackageApisPassThrough() {
        for (var base : List.of(CONSOLE, RAW, UC)) {
            for (var suffix : List.of("/content", "/draft", "/unpublish", "/recycle", "/unknown")) {
                pass(HttpMethod.PUT, base + "/sample" + suffix, bytes("not-json"));
            }
            pass(HttpMethod.GET, base + "/sample", new byte[0]);
            pass(HttpMethod.DELETE, base + "/sample", new byte[0]);
        }
        pass(HttpMethod.PUT, PackageService.ADMIN + "/native-posts/sample/publish", bytes("not-json"));
        pass(HttpMethod.POST, "/apis/content.halo.run/v1beta1/posts", bytes("not-json"));
        verifyNoInteractionsBeyondDefaultStubs();
    }

    @Test void missingStoredPostsReachNativeNotFoundHandling() {
        pass(HttpMethod.PUT, CONSOLE + "/missing/publish", new byte[0]);
        pass(HttpMethod.PUT, UC + "/missing/publish", new byte[0]);
        pass(HttpMethod.PUT, UC + "/missing", bytes("not-json"));
        pass(HttpMethod.PATCH, RAW + "/missing", bytes("not-json"), MediaType.valueOf("application/json-patch+json"));
    }

    private void verifyNoInteractionsBeyondDefaultStubs() { verifyNoInteractions(client); }
    private void stored(Post post) { when(client.fetch(Post.class, "sample")).thenReturn(Mono.just(post)); }
    private static Post post(boolean publish, List<String> categories) {
        var post = new Post();
        var metadata = new Metadata(); metadata.setName("sample"); metadata.setVersion(37L); post.setMetadata(metadata);
        var spec = new Post.PostSpec(); spec.setOwner("owner"); spec.setTitle("Private draft title");
        spec.setPublish(publish); spec.setDeleted(false); spec.setCategories(categories); post.setSpec(spec);
        return post;
    }
    private Category category(String name, String slug) {
        var category = new Category(); var metadata = new Metadata(); metadata.setName(name); category.setMetadata(metadata);
        var spec = new Category.CategorySpec(); spec.setSlug(slug); category.setSpec(spec);
        when(client.fetch(Category.class, name)).thenReturn(Mono.just(category));
        return category;
    }
    private byte[] body(String base, Post post) throws Exception {
        return mapper.writeValueAsBytes(base.equals(CONSOLE) ? Map.of("post", post, "content", Map.of("raw", "正文", "rawType", "markdown")) : post);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("owner", "unused", List.of());
    }
    private static MockServerWebExchange exchange(HttpMethod method, String path, byte[] bytes, MediaType type) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path)
            .header("Content-Type", type.toString())
            .header("X-Test-Original", "preserved")
            .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))));
    }
    private void pass(HttpMethod method, String path, byte[] bytes) { pass(method, path, bytes, MediaType.APPLICATION_JSON); }
    private void pass(HttpMethod method, String path, byte[] bytes, MediaType type) {
        var exchange = exchange(method, path, bytes, type);
        var called = new AtomicInteger();
        filter.filter(exchange, next -> {
            called.incrementAndGet();
            assertEquals(exchange.getRequest().getURI(), next.getRequest().getURI());
            assertEquals(exchange.getRequest().getHeaders(), next.getRequest().getHeaders());
            return DataBufferUtils.join(next.getRequest().getBody()).doOnNext(buffer -> {
                try {
                    var replay = new byte[buffer.readableByteCount()]; buffer.read(replay); assertArrayEquals(bytes, replay);
                } finally { DataBufferUtils.release(buffer); }
            }).then();
        }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication())).block();
        assertEquals(1, called.get());
        assertNull(exchange.getResponse().getStatusCode());
    }
    private void reject(HttpMethod method, String path, byte[] bytes, int status) {
        reject(method, path, bytes, status, MediaType.APPLICATION_JSON);
    }
    private void rejectPatch(String patch, int status) {
        reject(HttpMethod.PATCH, RAW + "/sample", bytes(patch), status, MediaType.valueOf("application/json-patch+json"));
    }
    private void reject(HttpMethod method, String path, byte[] bytes, int status, MediaType type) {
        var exchange = exchange(method, path, bytes, type);
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Rejected request reached core")))
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication())).block();
        assertEquals(status, exchange.getResponse().getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.getResponse().getHeaders().getContentType());
        var problem = exchange.getResponse().getBodyAsString().block();
        assertFalse(problem.contains("Private draft title"));
        assertFalse(problem.contains("old-section"));
        if (status == 422) { assertTrue(problem.contains("请在文章设置的分类中")); }
    }
}
