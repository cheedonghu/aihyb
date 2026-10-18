package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class OperationsTest {
    Post post() {
        var p=new Post();var m=new Metadata();m.setName(NativeMirror.postName("sample"));m.setVersion(7L);
        m.setAnnotations(new HashMap<>(Map.of(NativeMirror.ID,"sample",NativeMirror.KIND,"review",NativeMirror.DRAFT,"b",
            NativeMirror.PUBLIC,"a",NativeMirror.APPROVED,"b",NativeMirror.JOURNAL,"a,b")));
        m.setLabels(new HashMap<>(Map.of(Post.PUBLISHED_LABEL,"false")));p.setMetadata(m);
        var s=new Post.PostSpec();s.setPublish(true);s.setTitle("Public A");s.setReleaseSnapshot("snapshot-a");p.setSpec(s);return p;
    }
    @Test void withdrawalAndArchivePreserveAllRevisionsAndClearApproval() {
        for(var action:new String[]{"withdraw","archive"}) {
            var p=post();var client=mock(ReactiveExtensionClient.class);var mirror=mock(NativeMirror.class);
            var packages=mock(PackageService.class);var events=mock(FeedPublicationEvents.class);
            when(packages.entry("sample")).thenReturn(Mono.just(new ContentEntry()));when(mirror.post("sample")).thenReturn(Mono.just(p));
            when(client.update(p)).thenReturn(Mono.just(p));
            var ops=new OperationsService(client,mirror,packages,new PackageCodec(),events);
            var result=ops.operate("sample",new OperationsService.Command(NativeMirror.businessVersion(p,new PackageCodec()),action,null),"reviewer").block();
            assertFalse(result.published());assertEquals(action.equals("archive"),result.archived());
            assertEquals("b",result.draft_revision());assertEquals("a",result.published_revision());assertNull(result.reviewed_revision());
            assertEquals("snapshot-a",p.getSpec().getReleaseSnapshot());assertEquals("Public A",p.getSpec().getTitle());
            assertEquals("a,b",NativeMirror.annotations(p).get(NativeMirror.JOURNAL));
            assertNotNull(NativeMirror.annotations(p).get(NativeMirror.OPERATION_EPOCH));verify(events).published(p);
        }
    }
    @Test void staleOperationalVersionCannotWithdrawNewerPublication() {
        var client=mock(ReactiveExtensionClient.class);var mirror=mock(NativeMirror.class);var packages=mock(PackageService.class);
        when(packages.entry("sample")).thenReturn(Mono.just(new ContentEntry()));when(mirror.post("sample")).thenReturn(Mono.just(post()));
        var ops=new OperationsService(client,mirror,packages,new PackageCodec(),mock(FeedPublicationEvents.class));
        assertThrows(PackageProblem.class,()->ops.operate("sample",new OperationsService.Command("stale-business-version","withdraw",null),"reviewer").block());
        verify(client,never()).update(any());
    }
    @Test void unarchiveNeverRepublishesAndRequiresNewApproval() {
        var p=post();NativeMirror.annotations(p).put(NativeMirror.ARCHIVED,"true");
        var client=mock(ReactiveExtensionClient.class);var mirror=mock(NativeMirror.class);var packages=mock(PackageService.class);
        when(packages.entry("sample")).thenReturn(Mono.just(new ContentEntry()));when(mirror.post("sample")).thenReturn(Mono.just(p));when(client.update(p)).thenReturn(Mono.just(p));
        var result=new OperationsService(client,mirror,packages,new PackageCodec(),mock(FeedPublicationEvents.class))
            .operate("sample",new OperationsService.Command(NativeMirror.businessVersion(p,new PackageCodec()),"unarchive",null),"reviewer").block();
        assertFalse(result.published());assertFalse(result.archived());assertNull(result.reviewed_revision());
    }
    @Test void publicationRetryCannotCrossWithdrawalEpochEvenIfRevisionApprovedAgain() throws Exception {
        var initial=post();var first=post();var withdrawn=post();NativeMirror.annotations(withdrawn).put(NativeMirror.OPERATION_EPOCH,"withdrawn");
        var client=mock(ReactiveExtensionClient.class);var mirror=spy(new NativeMirror(client,new PackageCodec(),new PackageRenderer(),mock(FeedPublicationEvents.class)));
        when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.just(initial),Mono.just(first),Mono.just(withdrawn));
        var rev=new ContentRevision();var s=new ContentRevision.Spec();s.setRawJson(Files.readString(Path.of("../package-fixtures/review.json")));rev.setSpec(s);
        doReturn(Mono.just(rev)).when(mirror).revision("b");when(client.update(first)).thenReturn(Mono.error(new OptimisticLockingFailureException("competing withdrawal")));
        var error=assertThrows(PackageProblem.class,()->mirror.publish("sample","b","reviewer").block());
        assertTrue(error.getMessage().contains("运营状态"));verify(client,times(1)).update(any());
    }
    @Test void removedArchivedPostCannotBeUnarchived() {
        var p=post();NativeMirror.annotations(p).put(NativeMirror.ARCHIVED,"true");p.getSpec().setDeleted(true);
        var client=mock(ReactiveExtensionClient.class);var mirror=mock(NativeMirror.class);var packages=mock(PackageService.class);
        when(packages.entry("sample")).thenReturn(Mono.just(new ContentEntry()));when(mirror.post("sample")).thenReturn(Mono.just(p));
        var ops=new OperationsService(client,mirror,packages,new PackageCodec(),mock(FeedPublicationEvents.class));
        assertThrows(PackageProblem.class,()->ops.operate("sample",new OperationsService.Command(NativeMirror.businessVersion(p,new PackageCodec()),"unarchive",null),"publisher").block());
        verify(client,never()).update(any());
    }
    @Test void archivedDraftCannotBeSavedReviewedOrPublished() {
        var p=post();NativeMirror.annotations(p).put(NativeMirror.ARCHIVED,"true");
        var client=mock(ReactiveExtensionClient.class);when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.just(p));
        var mirror=new NativeMirror(client,new PackageCodec(),new PackageRenderer(),mock(FeedPublicationEvents.class));
        assertThrows(PackageProblem.class,()->mirror.review("sample",new PackageViews.ReviewCommand("b","approve",""),"reviewer").block());
        assertThrows(PackageProblem.class,()->mirror.publish("sample","b","reviewer").block());
        var entry=new ContentEntry();var spec=new ContentEntry.Spec();spec.setContentId("sample");entry.setSpec(spec);
        assertThrows(PackageProblem.class,()->mirror.commit(entry,"c","b").block());verify(client,never()).update(any());
    }
    @Test void reviewRetryCannotRestoreApprovalAfterWithdrawal() {
        var first=post();var withdrawn=post();NativeMirror.annotations(withdrawn).put(NativeMirror.OPERATION_EPOCH,"new-state");
        NativeMirror.annotations(withdrawn).remove(NativeMirror.APPROVED);
        var client=mock(ReactiveExtensionClient.class);when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.just(first),Mono.just(withdrawn));
        var mirror=spy(new NativeMirror(client,new PackageCodec(),new PackageRenderer(),mock(FeedPublicationEvents.class)));
        doReturn(Mono.just(new ContentRevision())).when(mirror).revision("b");
        when(client.update(first)).thenReturn(Mono.error(new OptimisticLockingFailureException("withdrawal won")));
        assertThrows(PackageProblem.class,()->mirror.review("sample",new PackageViews.ReviewCommand("b","approve","","0"),"reviewer").block());
        assertNull(NativeMirror.annotations(withdrawn).get(NativeMirror.APPROVED));verify(client,times(1)).update(any());
    }
    @Test void orphanOrForeignRevisionIsNotARecoverableHistoryItem() {
        var client=mock(ReactiveExtensionClient.class);when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.just(post()));
        var mirror=new NativeMirror(client,new PackageCodec(),new PackageRenderer(),mock(FeedPublicationEvents.class));
        assertThrows(PackageProblem.class,()->mirror.acceptedRevision("sample","orphan").block());verify(client,never()).fetch(ContentRevision.class,"orphan");
    }
    @Test void businessTokenIgnoresCoreCountersButDetectsEditingDeletionAndAba() {
        var post=post();var codec=new PackageCodec();var first=NativeMirror.businessVersion(post,codec);
        post.getMetadata().setVersion(999L);post.getMetadata().getAnnotations().put("halo.run/visit-stats","42");
        assertEquals(first,NativeMirror.businessVersion(post,codec));
        NativeMirror.advance(post);assertNotEquals(first,NativeMirror.businessVersion(post,codec));
        post.getMetadata().setDeletionTimestamp(java.time.Instant.now());
        assertThrows(PackageProblem.class,()->NativeMirror.requireActive(post));
    }
}
