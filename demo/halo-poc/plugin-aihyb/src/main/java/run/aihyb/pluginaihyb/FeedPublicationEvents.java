package run.aihyb.pluginaihyb;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.halo.app.core.extension.content.Post;
import run.halo.feed.RssCacheClearRequested;
import org.springframework.context.event.EventListener;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.event.post.PostUnpublishedEvent;

/** Feed 1.5's native PostUpdated listener omits /feed.xml and tag routes; use its public shared API. */
@Component
public class FeedPublicationEvents {
    private static final Logger log = LoggerFactory.getLogger(FeedPublicationEvents.class);
    private final ApplicationEventPublisher publisher;
    public FeedPublicationEvents(ApplicationEventPublisher publisher) { this.publisher = publisher; }
    // Core emits these after the reconciled published label has committed. Clear a second time
    // so an immediate post-CAS reader cannot repopulate feed aliases using the old label.
    @EventListener public void reconciled(PostPublishedEvent event) {
        if (event.getName().startsWith("ap-")) { invalidate(); }
    }
    @EventListener public void reconciled(PostUnpublishedEvent event) {
        if (event.getName().startsWith("ap-")) { invalidate(); }
    }
    public void published(Post committed) {
        invalidate();
    }
    private void invalidate() {
        // The immutable release snapshot and all public metadata already committed on one Post.
        try {
            publisher.publishEvent(RssCacheClearRequested.forAll("plugin-aihyb"));
        } catch (RuntimeException failure) {
            // A notification cannot undo the committed Post or trigger another publication CAS.
            // No content/title/package is logged; operators can retry cache invalidation separately.
            log.warn("Public revision committed; Feed cache invalidation notification failed ({})",
                failure.getClass().getSimpleName());
        }
    }
}
