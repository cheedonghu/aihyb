package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.result.view.View;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.theme.TemplateNameResolver;

class AutomaticBoardRoutesTest {
    static final String ID = "auto-open-models";
    static final String PUBLIC_ID = "module:" + ID;
    static final String URL = "/aihyb/boards/" + ID;

    @Test void catalogJsonAndThemedPageShareTheStableBoardWithoutAPost() throws Exception {
        var codec = new PackageCodec();
        var renderer = new PackageRenderer();
        var root = (ObjectNode) codec.validate(Files.readString(Path.of("../contracts/examples/leaderboard-open-models.json")));
        root.put("content_id", ID);
        var published = new PackageViews.PublishedContent(PUBLIC_ID, "batch-1", "leaderboard", codec.toMap(root.get("content")),
            codec.toMap(root.get("data")), List.of(), List.of(), URL);
        var auto = mock(AutomaticBoards.class);
        when(auto.published()).thenReturn(Mono.just(List.of(published)));
        when(auto.byId(ID)).thenReturn(Mono.just(published));
        when(auto.payload(ID)).thenReturn(Mono.just(root));
        when(auto.payload("unknown")).thenReturn(Mono.empty());
        var client = mock(ReactiveExtensionClient.class);
        when(client.listAll(eq(ContentEntry.class), any(ListOptions.class), any())).thenReturn(Flux.empty());
        var natives = mock(NativeCatalog.class);
        when(natives.published()).thenReturn(Mono.just(List.of()));
        var service = new PackageService(client, codec, renderer, null, null, natives, auto);
        var items = service.publicSelection("leaderboard", null, null, 1, 20).block();
        assertEquals(List.of(published), items.items());
        var portal = new PortalRenderer(codec);
        assertTrue(portal.home(service.publicCatalog(null, null).block()).contains(URL));
        assertTrue(portal.listing("leaderboard", items, null).contains(URL));
        assertTrue(service.publicCatalog("news", null).block().isEmpty());

        var api = WebTestClient.bindToRouterFunction(new PublicController(service, codec).endpoint()).build();
        api.get().uri("/contents/" + PUBLIC_ID).exchange().expectStatus().isOk().expectBody().jsonPath("$.url").isEqualTo(URL);
        var templates = mock(TemplateNameResolver.class);
        when(templates.resolveTemplateNameOrDefault(any(), eq("aihyb-portal"))).thenReturn(Mono.just("aihyb-portal"));
        View view = new View() {
            public Mono<Void> render(Map<String, ?> model, MediaType type, ServerWebExchange exchange) {
                assertEquals(URL, model.get("pageCanonical"));
                var body = model.get("pageBody").toString().getBytes(StandardCharsets.UTF_8);
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
        var web = WebTestClient.bindToRouterFunction(new WebController(service, renderer, templates, portal, null).aihybPages())
            .handlerStrategies(HandlerStrategies.builder().viewResolver((name, locale) -> Mono.just(view)).build()).build();
        web.get().uri("/aihyb/content/" + PUBLIC_ID).exchange().expectStatus().isFound().expectHeader().valueEquals("Location", URL);
        var html = web.get().uri(URL).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        var doc = Jsoup.parse(html);
        assertEquals(root.at("/content/title").asText(), doc.selectFirst("h1").text());
        assertEquals(root.at("/data/entries").size(), doc.select("[data-ranking-row]").size());
        assertFalse(doc.select("[data-hardware-budget]").isEmpty());
        web.get().uri("/aihyb/boards/unknown").exchange().expectStatus().isNotFound();
    }

    @Test void onlyLegacySnapshotsLeaveNavigationWhileOrdinaryAndHistoricalIdsStillResolve() {
        var legacyId = "md-backend-" + "a".repeat(48);
        var stable = "auto-modeldial-backend";
        var data = Map.<String,Object>of("benchmark", "ModelDial/backend/policy", "method_version", "v1");
        var content = Map.<String,Object>of("title", "后端开发榜");
        var legacy = new PackageViews.PublishedContent(legacyId, "r-1", "leaderboard", content, data, List.of(), List.of());
        var tutorial = new PackageViews.PublishedContent(stable, "post-1", "tutorial", Map.of("title", "教程"),
            Map.of(), List.of(), List.of(), "/archives/tutorial");
        var current = new PackageViews.PublishedContent("module:" + stable, "batch-1", "leaderboard", content,
            data, List.of(), List.of(), "/aihyb/boards/" + stable);
        var client = mock(ReactiveExtensionClient.class);
        when(client.listAll(eq(ContentEntry.class), any(ListOptions.class), any())).thenReturn(Flux.empty());
        var natives = mock(NativeCatalog.class);
        when(natives.published()).thenReturn(Mono.just(List.of(legacy, tutorial)));
        when(natives.byId(stable)).thenReturn(Mono.just(tutorial));
        when(natives.byId(legacyId)).thenReturn(Mono.just(legacy));
        var auto = mock(AutomaticBoards.class);
        when(auto.published()).thenReturn(Mono.just(List.of(current)));
        when(auto.byId(stable)).thenReturn(Mono.just(current));
        var service = spy(new PackageService(client, new PackageCodec(), new PackageRenderer(), null, null, natives, auto));
        doReturn(Mono.error(new PackageProblem(404, "内容不存在"))).when(service).entry(anyString());
        assertEquals(List.of(current), service.publicCatalog("leaderboard", null).block());
        assertEquals(List.of(tutorial), service.publicCatalog("tutorial", null).block());
        assertEquals(2, service.publicCatalog(null, null).block().size());
        assertEquals(tutorial, service.publicContent(stable).block());
        assertEquals(legacy, service.publicContent(legacyId).block());
        assertEquals(current, service.publicContent(current.content_id()).block());
        when(auto.published()).thenReturn(Mono.just(List.of()));
        assertEquals(List.of(legacy), service.publicCatalog("leaderboard", null).block());
    }
}
