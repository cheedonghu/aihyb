package run.aihyb.pluginaihyb;

import reactor.core.publisher.Mono;

public interface PortalFinder {
    Mono<String> home();
    Mono<String> footer();
    Mono<String> footerContent();
    Mono<String> article(String contentId, String releaseRevision, String fallback);
}
