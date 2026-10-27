package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class MediaTest {
    ObjectNode payload() throws Exception {return (ObjectNode)new PackageCodec().parse(Files.readString(Path.of("../contracts/examples/tutorial.json")));}
    ObjectNode asset(ObjectNode payload,String id,String visibility,String type) {
        var asset=((com.fasterxml.jackson.databind.node.ArrayNode)payload.get("assets")).addObject();
        asset.put("asset_id",id);asset.put("object_key","attachments/native-asset");asset.put("sha256","a".repeat(64));
        asset.put("content_type",type);asset.put("alt",visibility.equals("private")?"PRIVATE_FILENAME":"示例媒体");asset.put("visibility",visibility);return asset;
    }
    Attachment attachment(){
        var attachment=new Attachment();var m=new Metadata();m.setName("native-asset");
        m.setAnnotations(new HashMap<>(Map.of(MediaService.OWNER,"sample",MediaService.HASH,"a".repeat(64))));attachment.setMetadata(m);
        var s=new Attachment.AttachmentSpec();s.setPolicyName(MediaService.POLICY);s.setMediaType("video/mp4");s.setDisplayName("demo.mp4");attachment.setSpec(s);return attachment;
    }
    @Test void gatewayStreamsBytesAndSingleRangeWithoutLeakingSignedUrl() throws Exception {
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] bytes="0123456789".getBytes();
        upstream.createContext("/private",exchange->{
            var range=exchange.getRequestHeaders().getFirst("Range");byte[] body=bytes;
            if(range!=null){body="2345".getBytes();exchange.getResponseHeaders().add("Content-Range","bytes 2-5/10");}
            exchange.getResponseHeaders().add("Accept-Ranges","bytes");exchange.sendResponseHeaders(range==null?200:206,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });upstream.start();
        try {
            var media=mock(MediaService.class);var attachment=attachment();
            when(media.resolve("sample","revision","asset",false)).thenReturn(Mono.just(attachment));
            when(media.signed(attachment)).thenReturn(Mono.just(java.net.URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/private?X-Amz-Signature=secret")));
            var web=WebTestClient.bindToRouterFunction(new PublicMediaController(new MediaController(media,new PackageCodec())).endpoint()).build();
            web.get().uri("/media/sample/file/revision/asset").exchange().expectStatus().isOk().expectHeader().doesNotExist("Location")
                .expectHeader().valueEquals("Cache-Control","private, no-store").expectBody().consumeWith(r -> assertArrayEquals(bytes,r.getResponseBody()));
            web.get().uri("/media/sample/file/revision/asset").header("Range","bytes=2-5").exchange().expectStatus().isEqualTo(206)
                .expectHeader().valueEquals("Content-Range","bytes 2-5/10").expectBody().consumeWith(r -> assertArrayEquals("2345".getBytes(),r.getResponseBody()));
            web.get().uri("/media/sample/file/revision/asset").header("Range","bytes=0-1,4-5").exchange().expectStatus().isEqualTo(416);
        } finally {upstream.stop(0);}
    }
    @Test void retiredRevisionPrivateAssetAndForeignOwnerAreRejected() throws Exception {
        var client=mock(ReactiveExtensionClient.class);var storage=mock(AttachmentService.class);var mirror=mock(NativeMirror.class);
        var media=new MediaService(client,storage,mirror,new PackageCodec());
        var post=new OperationsTest().post();when(mirror.post("sample")).thenReturn(Mono.just(post));
        assertThrows(PackageProblem.class,()->media.resolve("sample","old-revision","asset",false).block());
        var payload=payload();asset(payload,"secret","private","video/mp4");
        var rev=new ContentRevision();var spec=new ContentRevision.Spec();spec.setRawJson(new PackageCodec().canonical(payload));rev.setSpec(spec);
        when(mirror.acceptedRevision("sample","a")).thenReturn(Mono.just(rev));
        assertThrows(PackageProblem.class,()->media.resolve("sample","a","secret",false).block());
        var policy=new Policy();var ps=new Policy.PolicySpec();ps.setTemplateName("s3os");policy.setSpec(ps);
        when(client.fetch(Policy.class,MediaService.POLICY)).thenReturn(Mono.just(policy));
        var attachment=attachment();attachment.getMetadata().getAnnotations().put(MediaService.OWNER,"another-entry");
        when(client.fetch(Attachment.class,"native-asset")).thenReturn(Mono.just(attachment));
        assertThrows(PackageProblem.class,()->media.resolve("sample","a","secret",true).block());verifyNoInteractions(storage);
    }
    @Test void accidentalPublicBucketFailsClosedBeforePublication() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/object",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {
            var client=mock(ReactiveExtensionClient.class);var storage=mock(AttachmentService.class);var policy=new Policy();
            var ps=new Policy.PolicySpec();ps.setTemplateName("s3os");policy.setSpec(ps);
            when(client.fetch(Policy.class,MediaService.POLICY)).thenReturn(Mono.just(policy));
            var attachment=attachment();when(client.fetch(Attachment.class,"native-asset")).thenReturn(Mono.just(attachment));
            when(storage.getPermalink(attachment)).thenReturn(Mono.just(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/object")));
            var payload=payload();asset(payload,"asset","public","video/mp4");
            assertThrows(PackageProblem.class,()->new MediaService(client,storage,mock(NativeMirror.class),new PackageCodec()).validate("sample",payload).block());
        } finally {server.stop(0);}
    }
    @Test void markdownUsesVersionedGatewayAndPrivateMetadataStaysInPreview() throws Exception {
        var payload=payload();asset(payload,"picture","public","image/png");asset(payload,"secret","private","application/pdf");
        ((ObjectNode)payload.get("content")).put("body_markdown","![截图](asset://picture)\n\n[video:youtube:dQw4w9WgXcQ]\n\n<script>alert(1)</script>");
        var renderer=new PackageRenderer();var body=renderer.markdown(payload.get("content").get("body_markdown").asText());
        var published=renderer.articleFragment(payload,body,false,"revision-a");var preview=renderer.articleFragment(payload,body,true,"revision-b");
        assertTrue(published.contains("/media/poc-tutorial/file/revision-a/picture"));assertFalse(published.contains("PRIVATE_FILENAME"));
        assertFalse(published.contains("<script>"));assertTrue(published.contains("youtube-nocookie.com/embed/dQw4w9WgXcQ"));
        assertTrue(preview.contains("PRIVATE_FILENAME"));assertTrue(preview.contains("/media/revision-b/secret"));
    }
    @Test void rawStorageSignedLinksExternalImagesAndHandwrittenGatewayAreRejected() throws Exception {
        var codec=new PackageCodec();
        for(var text:new String[]{"![x](https://external.invalid/image.png)","![x][source]\n\n[source]: https://external.invalid/image.png", "[x](https://bucket.cos.ap-shanghai.myqcloud.com/file)",
            "[x](https://other.invalid/file?X-Amz-Signature=secret)","[x](/apis/api.aihyb.run/v1alpha1/media/id/rev/asset)","![x](asset://missing)"}){
            var payload=payload();((ObjectNode)payload.get("content")).put("body_markdown",text);
            assertThrows(PackageProblem.class,()->codec.validate(codec.canonical(payload)),text);
        }
    }
    @Test void chunkedOversizeUploadIsBoundedBeforeStorageWrites() {
        var client=mock(ReactiveExtensionClient.class);var storage=mock(AttachmentService.class);var mirror=mock(NativeMirror.class);
        when(mirror.post("sample")).thenReturn(Mono.just(new OperationsTest().post()));
        var policy=new Policy();var ps=new Policy.PolicySpec();ps.setTemplateName("s3os");policy.setSpec(ps);
        when(client.fetch(Policy.class,MediaService.POLICY)).thenReturn(Mono.just(policy));
        var media=new MediaService(client,storage,mirror,new PackageCodec());
        var file=mock(org.springframework.http.codec.multipart.FilePart.class);var headers=new org.springframework.http.HttpHeaders();headers.setContentType(MediaType.IMAGE_PNG);
        when(file.headers()).thenReturn(headers);when(file.filename()).thenReturn("large.png");
        when(file.content()).thenReturn(Flux.range(0,17).map(i -> (DataBuffer)DefaultDataBufferFactory.sharedInstance.wrap(new byte[1024*1024])));
        assertThrows(PackageProblem.class,()->media.upload("sample","editor",file).block());verifyNoInteractions(storage);
    }
    @Test void sourcesCannotSmuggleStorageSignaturesOrPrivateGatewayLinks() throws Exception {
        var codec=new PackageCodec();
        for(var url:java.util.List.of("https://bucket.cos.ap-shanghai.myqcloud.com/file?X-Amz-Signature=secret",
            "https://site.invalid/apis/console.api.aihyb.run/v1alpha1/entries/id/preview")){
            var payload=payload();((ObjectNode)payload.get("sources").get(0)).put("url",url);
            assertThrows(PackageProblem.class,()->codec.validate(codec.canonical(payload)));
        }
    }
}
