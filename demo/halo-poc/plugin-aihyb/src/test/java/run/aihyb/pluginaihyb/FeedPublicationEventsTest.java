package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import run.halo.app.core.extension.content.Post;
import run.halo.feed.RssCacheClearRequested;

class FeedPublicationEventsTest {
    @Test void reconciledPublicationAndWithdrawalClearAliasCachesAfterLabelUpdate() {
        var publisher=mock(ApplicationEventPublisher.class);var events=new FeedPublicationEvents(publisher);
        events.reconciled(new run.halo.app.event.post.PostPublishedEvent("core","ap-managed"));
        events.reconciled(new run.halo.app.event.post.PostUnpublishedEvent("core","ap-managed"));
        events.reconciled(new run.halo.app.event.post.PostUnpublishedEvent("core","ordinary"));
        verify(publisher,times(2)).publishEvent(any(RssCacheClearRequested.class));
    }
    @Test void notificationFailureCannotFailOrRepeatCommittedPublication() throws Exception {
        var publisher = mock(ApplicationEventPublisher.class);
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("listener failure"))
            .when(publisher).publishEvent(any(RssCacheClearRequested.class));
        var client = mock(run.halo.app.extension.ReactiveExtensionClient.class);
        var codec = new PackageCodec();
        var mirror = spy(new NativeMirror(client,codec,mock(PackageRenderer.class),new FeedPublicationEvents(publisher)));
        var post = publicationPost();
        when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(reactor.core.publisher.Mono.just(post));
        doReturn(reactor.core.publisher.Mono.just(publicationRevision())).when(mirror).revision("revision-b");
        when(client.update(post)).thenReturn(reactor.core.publisher.Mono.just(post));
        assertSame(post,mirror.publish("sample","revision-b","reviewer").block());
        assertEquals("revision-b",NativeMirror.annotations(post).get(NativeMirror.PUBLIC));
        verify(client,times(1)).update(post);
        verify(publisher,times(1)).publishEvent(any(RssCacheClearRequested.class));
    }
    @Test void failedPostCommitDoesNotClearFeedCaches() throws Exception {
        var client = mock(run.halo.app.extension.ReactiveExtensionClient.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var mirror = spy(new NativeMirror(client,new PackageCodec(),mock(PackageRenderer.class),new FeedPublicationEvents(publisher)));
        var post=publicationPost();
        when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(reactor.core.publisher.Mono.just(post));
        doReturn(reactor.core.publisher.Mono.just(publicationRevision())).when(mirror).revision("revision-b");
        when(client.update(post)).thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("storage unavailable")));
        assertThrows(IllegalStateException.class,()->mirror.publish("sample","revision-b","reviewer").block());
        verify(client,times(1)).update(post);verifyNoInteractions(publisher);
    }
    private Post publicationPost() {
        var post=new Post();var metadata=new run.halo.app.extension.Metadata();
        metadata.setName(NativeMirror.postName("sample"));
        metadata.setAnnotations(new java.util.HashMap<>(java.util.Map.of(NativeMirror.DRAFT,"revision-b",NativeMirror.APPROVED,"revision-b")));
        post.setMetadata(metadata);post.setSpec(new Post.PostSpec());return post;
    }
    private ContentRevision publicationRevision() throws Exception {
        var revision=new ContentRevision();var spec=new ContentRevision.Spec();revision.setSpec(spec);
        spec.setRawJson(java.nio.file.Files.readString(java.nio.file.Path.of("../package-fixtures/review.json")));return revision;
    }
    @Test void committedPublicationClearsAllOfficialFeedAliases() {
        var publisher = mock(ApplicationEventPublisher.class);
        var post = new Post();
        new FeedPublicationEvents(publisher).published(post);
        var event = ArgumentCaptor.forClass(RssCacheClearRequested.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals("plugin-aihyb",event.getValue().getSource());
        assertTrue(event.getValue().isApplyToAll());
        assertTrue(event.getValue().getRules().isEmpty());
    }
}
