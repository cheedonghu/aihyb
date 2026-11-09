package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;

class LeaderboardAssetsTest {
    String nativeHead(boolean managed) {
        var context = mock(ITemplateContext.class);
        var model = mock(IModel.class);
        var factory = mock(IModelFactory.class);
        when(context.getModelFactory()).thenReturn(factory);
        when(context.getVariable("post")).thenReturn(managed ? Map.of("metadata", Map.of("annotations",
            Map.of(NativeMirror.ID, "fictional-ranking"))) : null);
        new NativeThemeHead(new PackageCodec()).process(context, model, mock(IElementModelStructureHandler.class)).block();
        var markup = ArgumentCaptor.forClass(String.class);
        verify(factory).parse(eq(context.getTemplateData()), markup.capture());
        return markup.getValue();
    }

    @Test void realHeadLoadsScopedRankingStylesOnHomeAndManagedContent() {
        for (var managed : new boolean[]{false, true}) {
            var document = Jsoup.parse(nativeHead(managed));
            assertEquals(1, document.select("link[href^='/aihyb/assets/leaderboard.css']").size());
            assertTrue(document.select("link[href^='/aihyb/assets/portal.css']").isEmpty());
        }
    }

    @Test void publicAssetAndPortalTemplateProvideTheSharedStylesheet() throws Exception {
        var web = WebTestClient.bindToRouterFunction(new WebController(null, null, null, null, null).aihybPages()).build();
        web.get().uri("/aihyb/assets/leaderboard.css").exchange().expectStatus().isOk()
            .expectHeader().contentType("text/css").expectBody(String.class).consumeWith(response -> {
                var css = response.getResponseBody();
                assertNotNull(css);
                assertTrue(css.contains(".ranking-catalog .ranking-data-table"));
                assertFalse(css.contains(":root"));
                assertFalse(css.matches("(?s).*\\bbody\\s*\\{.*"));
            });
        var template = Files.readString(Path.of("src/main/resources/templates/aihyb-portal.html"));
        assertTrue(template.contains("/aihyb/assets/leaderboard.css"));
    }

    @Test void actualPreviewHeadUsesSharedStylesWithoutPortalResets() throws Exception {
        var codec = new PackageCodec();
        var payload = codec.validate(Files.readString(Path.of("../contracts/examples/leaderboard.json")));
        var renderer = new PackageRenderer();
        var html = renderer.article(payload, renderer.markdown(payload.path("content").path("body_markdown").asText()), true, "fictional-revision");
        var document = Jsoup.parse(html);
        assertEquals(1, document.select("head link[href^='/aihyb/assets/leaderboard.css']").size());
        assertTrue(document.select("head link[href^='/aihyb/assets/portal.css']").isEmpty());
        assertEquals(3, document.select(".ranking-data-table thead th").size());
    }

    @Test void exportNativePreviewUsingTheRealHeadProcessorAndEarthContext() throws Exception {
        var codec = new PackageCodec();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) codec.validate(Files.readString(Path.of("../contracts/examples/leaderboard.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("content")).put("title", "虚构演示 · 后端工程榜");
        var renderer = new PackageRenderer();
        var editorial = Path.of("../themes/shared/earth-editorial.css").toAbsolutePath().toUri();
        var html = "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<link rel='stylesheet' href='" + editorial + "'>" + nativeHead(true) +
            "</head><body class='aihyb-article-page'><main id='content' style='max-width:1080px;margin:24px auto;padding:16px'>" +
            "<h1>虚构演示 · 后端工程榜</h1>" + renderer.nativeBody(payload, renderer.markdown(payload.path("content").path("body_markdown").asText()), "fictional-revision") +
            "</main></body></html>";
        var output = Path.of("build/reports/leaderboard"); Files.createDirectories(output);
        Files.writeString(output.resolve("native-preview.html"), LeaderboardRendererTest.localAssets(html));
        var home = Jsoup.parse(Files.readString(Path.of("../themes/shared/earth-portal-index.html")), "", org.jsoup.parser.Parser.xmlParser());
        home.head().append(nativeHead(false));
        home.getElementsByAttribute("th:replace").remove();
        home.getElementsByAttribute("th:unless").remove();
        var item = new PackageViews.PublishedContent("fictional-home", "fictional-revision", "leaderboard",
            codec.toMap(payload.path("content")), codec.toMap(payload.path("data")), codec.toList(payload.path("sources")), java.util.List.of());
        home.getElementById("aihyb-portal").html(new PortalRenderer(codec).home(java.util.List.of(item)));
        for (var link : home.select("link[href^='/themes/theme-earth/assets/aihyb-']")) {
            var shared = link.attr("href").contains("editorial") ? "earth-editorial.css" : "earth-navigation.css";
            link.attr("href", Path.of("../themes/shared", shared).toAbsolutePath().toUri().toString());
        }
        Files.writeString(output.resolve("home-preview.html"), LeaderboardRendererTest.localAssets(home.outerHtml()));
    }
}
