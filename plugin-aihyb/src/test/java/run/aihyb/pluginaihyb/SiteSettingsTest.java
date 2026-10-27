package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import com.fasterxml.jackson.databind.node.ObjectNode;

class SiteSettingsTest {
    final PackageCodec codec=new PackageCodec();
    @Test void multipleTagsMatchAcrossScenesAndLegacyTasksRemainReadable() {
        var tags=new ArrayList<>(SiteSettings.defaults().tags());
        tags.add(new SiteSettings.Tag("paper-chart","论文图表",true,List.of("research","office")));
        var settings=new SiteSettings.Settings("0",tags,SiteSettings.defaults().qrs(),List.of());
        var item=new PackageViews.PublishedContent("test","published","tutorial",Map.of("task_tags",List.of("paper-chart","image")),Map.of("task","research"),List.of(),List.of());
        assertTrue(SiteSettings.matches(settings,item,"paper-chart","office"));
        assertTrue(SiteSettings.matches(settings,item,null,"research"));
        assertTrue(SiteSettings.matches(settings,item,"image","image"));
        assertFalse(SiteSettings.matches(settings,item,null,"overall"));
        assertFalse(SiteSettings.matches(settings,item,"programming","office"));
        var legacy=new PackageViews.PublishedContent("old","published","review",Map.of(),Map.of("task","presentation"),List.of(),List.of());
        assertTrue(SiteSettings.matches(settings,legacy,"presentation","office"));
    }
    @Test void sharedRenameChangesNavigationAndArticleLabelsWithoutChangingThePublication() throws Exception {
        var original=SiteSettings.defaults(); var tags=original.tags().stream().map(tag->tag.id().equals("presentation")?new SiteSettings.Tag("presentation","办公新名称",true,List.of("office","research")):tag).toList();
        var config=new SiteSettings.Settings("0",tags,original.qrs(),List.of());
        var payload=codec.validate(Files.readString(Path.of("../contracts/examples/review.json")));
        ((ObjectNode)payload.path("content")).putArray("tags").add("虚构演示");
        var renderer=new PackageRenderer();
        var body=renderer.nativeBody(payload,renderer.markdown(payload.path("content").path("body_markdown").asText()),"release",config);
        assertTrue(body.contains("办公新名称"));assertTrue(body.contains("虚构演示"));assertTrue(body.contains("?task=presentation"));
        var navigation=new PortalRenderer(codec).listing("review",new PackageViews.Page<>(List.of(),0,1,20),null,null,config);
        assertTrue(Jsoup.parse(navigation).select(".filter-buttons a[href='/aihyb/review?task=presentation']").text().contains("办公新名称"));
        assertFalse(Jsoup.parse(body).select("h1").size()>0);
    }
    @Test void settingsValidateNamesScopesImagesAndRelations() {
        var initial=SiteSettings.defaults(); assertEquals(initial,SiteSettings.validate(initial));
        var tags=new ArrayList<>(initial.tags());tags.add(initial.tags().getFirst());
        assertThrows(PackageProblem.class,()->SiteSettings.validate(new SiteSettings.Settings("0",tags,initial.qrs(),List.of())));
        assertThrows(PackageProblem.class,()->SiteSettings.validate(new SiteSettings.Settings("0",List.of(new SiteSettings.Tag("bad","Bad",true,List.of("overall"))),initial.qrs(),List.of())));
        assertThrows(PackageProblem.class,()->SiteSettings.validate(new SiteSettings.Settings("0",initial.tags(),initial.qrs(),List.of(new SiteSettings.LinkRule("javascript:alert(1)","reference")))));
        assertThrows(PackageProblem.class,()->SiteSettings.imageBytes(new SiteSettings.Qr("group","群","","","data:image/svg+xml;base64,abc")));
    }
    @Test void unconfiguredQrSlotsAreHiddenAndExpiredCodesCannotBeRead() throws Exception {
        assertTrue(Jsoup.parse(SiteFooter.render(SiteSettings.defaults())).select(".aihyb-footer-qrs").isEmpty());
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"png",output);
        String image="data:image/png;base64,"+Base64.getEncoder().encodeToString(output.toByteArray());
        var valid=new SiteSettings.Qr("support","客服","客服说明","",image);
        assertTrue(SiteSettings.active(valid)); assertArrayEquals(output.toByteArray(),SiteSettings.imageBytes(valid));
        var expired=new SiteSettings.Qr("group","微信群","","2000-01-01",image);
        assertFalse(SiteSettings.active(expired));
        var footer=Jsoup.parse(SiteFooter.render(new SiteSettings.Settings("0",SiteSettings.defaults().tags(),List.of(expired,new SiteSettings.Qr("account","公众号","","",""),valid),List.of())));
        assertEquals(1,footer.select(".aihyb-footer-qrs img").size());assertTrue(footer.text().contains("通过客服"));
        var unavailable=SiteSettings.defaults().qrs().stream().map(qr->qr.id().equals("group")?expired:qr).toList();
        var unavailableFooter=Jsoup.parse(SiteFooter.render(new SiteSettings.Settings("0",SiteSettings.defaults().tags(),unavailable,List.of())));
        assertTrue(unavailableFooter.text().contains("等待管理员更新"));assertFalse(unavailableFooter.text().contains("通过客服"));
    }
    @Test void settingsRejectStaleVersionsAndRemovingReferencedIdentifiers() {
        var client=mock(ReactiveExtensionClient.class);var service=new SiteSettings(client);var resource=new SiteConfiguration();var meta=new Metadata();meta.setName("aihyb-site-settings");meta.setVersion(7L);resource.setMetadata(meta);resource.setConfiguration(SiteSettings.serialize(SiteSettings.defaults()));
        when(client.fetch(eq(SiteConfiguration.class),anyString())).thenReturn(Mono.just(resource));
        var stale=assertThrows(PackageProblem.class,()->service.save(SiteSettings.serialize(SiteSettings.defaults())).block());assertEquals(409,stale.status());
        var dropped=new SiteSettings.Settings("r7",SiteSettings.defaults().tags().subList(1,10),SiteSettings.defaults().qrs(),List.of());
        assertEquals(422,assertThrows(PackageProblem.class,()->service.save(SiteSettings.serialize(dropped)).block()).status());
        verify(client,never()).update(any(SiteConfiguration.class));
    }
    @Test void draftTagsDoNotAlterPublicProjectionOrSceneMembership() throws Exception {
        var client=mock(ReactiveExtensionClient.class);var service=spy(new PackageService(client,codec,new PackageRenderer(),mock(NativeMirror.class),null,mock(NativeCatalog.class)));
        var payload=(ObjectNode)codec.validate(Files.readString(Path.of("../contracts/examples/review.json")));
        ((ObjectNode)payload.path("content")).putArray("task_tags").add("presentation");
        var revision=new ContentRevision();var meta=new Metadata();meta.setName("release");revision.setMetadata(meta);var spec=new ContentRevision.Spec();spec.setRawJson(codec.canonical(payload));spec.setRenderedHtml("");revision.setSpec(spec);
        spec.setRequestHash(PackageCodec.hash(spec.getRawJson()));spec.setRenderedHash(PackageCodec.hash(spec.getRenderedHtml()));
        var entry=new ContentEntry();entry.setSpec(new ContentEntry.Spec());entry.getSpec().setContentId("poc-review");entry.getSpec().setPublished(true);entry.getSpec().setPublishedRevision("release");entry.getSpec().setDraftRevision("draft-tags");
        doReturn(Mono.just(entry)).when(service).entry("poc-review");when(client.fetch(ContentRevision.class,"release")).thenReturn(Mono.just(revision));
        var view=service.publicContent("poc-review").block();assertEquals(List.of("presentation"),view.content().get("task_tags"));
        assertTrue(SiteSettings.matches(SiteSettings.defaults(),view,null,"office"));assertFalse(SiteSettings.matches(SiteSettings.defaults(),view,null,"research"));
        verify(client,never()).fetch(ContentRevision.class,"draft-tags");
    }
    @Test void aNewlyCreatedResourceRejectsThePreCreationDefaultVersion() {
        var client=mock(ReactiveExtensionClient.class);var service=new SiteSettings(client);
        var resource=new SiteConfiguration();var metadata=new Metadata();metadata.setName("aihyb-site-settings");metadata.setVersion(0L);resource.setMetadata(metadata);
        resource.setConfiguration(SiteSettings.serialize(SiteSettings.defaults()));
        when(client.fetch(eq(SiteConfiguration.class),anyString())).thenReturn(Mono.just(resource));
        assertEquals("r0",service.get().block().version());
        assertEquals(409,assertThrows(PackageProblem.class,()->service.save(SiteSettings.serialize(SiteSettings.defaults())).block()).status());
        verify(client,never()).update(any(SiteConfiguration.class));
    }
    @Test void overallRequiresEditorialOrderAndDistinctModelVersions() throws Exception {
        var payload=(ObjectNode)codec.parse(Files.readString(Path.of("../contracts/examples/leaderboard.json")));var data=(ObjectNode)payload.path("data");data.put("task","overall");
        assertThrows(PackageProblem.class,()->codec.validate(codec.canonical(payload)));
        data.put("ranking_mode","editorial_order");assertDoesNotThrow(()->codec.validate(codec.canonical(payload)));
        ((ObjectNode)data.path("entries").get(0)).put("product_url","https://tools.example/model");assertDoesNotThrow(()->codec.validate(codec.canonical(payload)));
        var first=data.path("entries").get(0);((ObjectNode)data.path("entries").get(1)).put("tool_name",first.path("tool_name").asText()).put("model_version",first.path("model_version").asText());
        assertThrows(PackageProblem.class,()->codec.validate(codec.canonical(payload)));
    }
}
