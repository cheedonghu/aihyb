package run.aihyb.pluginaihyb;

import reactor.core.publisher.Mono;
import run.halo.app.theme.finders.Finder;

@Finder("aihybPortalFinder")
public class PortalFinderImpl implements PortalFinder {
    private final PackageService service;
    private final PortalRenderer renderer;
    private final SiteSettings settings;
    public PortalFinderImpl(PackageService service, PortalRenderer renderer, SiteSettings settings) { this.service = service; this.renderer = renderer; this.settings=settings; }
    @Override public Mono<String> home() { return service.publicCatalog(null, null).zipWith(settings.get()).map(pair -> renderer.home(pair.getT1(),pair.getT2())); }
    @Override public Mono<String> footer() { return settings.get().map(SiteFooter::render); }
    @Override public Mono<String> article(String contentId, String releaseRevision, String fallback) {
        return service.publicNativeBody(contentId, releaseRevision).defaultIfEmpty(fallback);
    }
}
