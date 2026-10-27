package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class MediaRedirectTest {
    private static final String PUBLIC_PATH="/media/sample/file/a/picture";

    private static class Fixture {
        final PackageCodec codec=new PackageCodec();
        final ReactiveExtensionClient client=mock(ReactiveExtensionClient.class);
        final AttachmentService storage=mock(AttachmentService.class);
        final Post post=new Post();
        final Attachment attachment=new Attachment();
        final ContentRevision revision=new ContentRevision();
        final ObjectNode payload=(ObjectNode)codec.parse("{\"assets\":[]}");
        final ObjectNode asset=((com.fasterxml.jackson.databind.node.ArrayNode)payload.get("assets")).addObject();
        final WebTestClient web;

        Fixture(URI signed) {
            var metadata=new Metadata();metadata.setName(NativeMirror.postName("sample"));
            metadata.setAnnotations(new HashMap<>(Map.of(NativeMirror.ID,"sample",NativeMirror.KIND,"review",
                NativeMirror.PUBLIC,"a",NativeMirror.JOURNAL,"a")));post.setMetadata(metadata);
            var postSpec=new Post.PostSpec();postSpec.setPublish(true);post.setSpec(postSpec);
            when(client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.just(post));

            asset.put("asset_id","picture");asset.put("object_key","attachments/native-asset");
            asset.put("sha256","a".repeat(64));asset.put("content_type","image/png");asset.put("visibility","public");
            var revisionSpec=new ContentRevision.Spec();revisionSpec.setContentId("sample");revisionSpec.setKind("review");
            revisionSpec.setRenderedHtml("");revisionSpec.setRenderedHash(PackageCodec.hash(""));revision.setSpec(revisionSpec);
            updateRevision();when(client.fetch(ContentRevision.class,"a")).thenReturn(Mono.just(revision));

            var attachmentMetadata=new Metadata();attachmentMetadata.setName("native-asset");
            attachmentMetadata.setAnnotations(new HashMap<>(Map.of(MediaService.OWNER,"sample",MediaService.HASH,"a".repeat(64))));
            attachment.setMetadata(attachmentMetadata);var attachmentSpec=new Attachment.AttachmentSpec();
            attachmentSpec.setPolicyName(MediaService.POLICY);attachmentSpec.setMediaType("image/png");
            attachmentSpec.setDisplayName("picture.png");attachment.setSpec(attachmentSpec);
            when(client.fetch(Attachment.class,"native-asset")).thenReturn(Mono.just(attachment));
            var policy=new Policy();var policySpec=new Policy.PolicySpec();policySpec.setTemplateName("s3os");policy.setSpec(policySpec);
            when(client.fetch(Policy.class,MediaService.POLICY)).thenReturn(Mono.just(policy));
            when(storage.getSharedURL(any(Attachment.class),any(Duration.class))).thenReturn(Mono.just(signed));

            var mirror=new NativeMirror(client,codec,new PackageRenderer(),mock(FeedPublicationEvents.class));
            var media=new MediaService(client,storage,mirror,codec);var controller=new MediaController(media,codec);
            web=WebTestClient.bindToRouterFunction(new PublicMediaController(controller).endpoint().and(controller.endpoint())).build();
        }

        void updateRevision() {
            var json=codec.canonical(payload);revision.getSpec().setRawJson(json);
            revision.getSpec().setRequestHash(PackageCodec.hash(json));
        }
    }

    @Test void missingSignatureReturnsServiceUnavailableWithoutLocation() {
        var fixture=new Fixture(URI.create("https://storage.invalid/image?test-signature=fixture"));
        when(fixture.storage.getSharedURL(any(Attachment.class),any(Duration.class))).thenReturn(Mono.empty());
        fixture.web.get().uri(PUBLIC_PATH).exchange().expectStatus().isEqualTo(503)
            .expectHeader().doesNotExist("Location").expectHeader().valueEquals("Cache-Control","private, no-store")
            .expectBody().jsonPath("$.message").isEqualTo("媒体签名暂不可用，请稍后重试");
    }

    @Test void privatePreviewImageStillStreamsThroughTheWebsite() throws Exception {
        var requests=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] bytes="private image bytes".getBytes();
        upstream.createContext("/image",exchange->{
            requests.incrementAndGet();exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();
        try {
            var fixture=new Fixture(URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/image"));
            fixture.asset.put("visibility","private");fixture.updateRevision();fixture.post.getSpec().setPublish(false);
            fixture.web.get().uri("/entries/sample/media/a/picture").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("Location").expectHeader().contentType("image/png")
                .expectHeader().valueEquals("Cache-Control","private, no-store")
                .expectBody().consumeWith(result -> assertArrayEquals(bytes,result.getResponseBody()));
            assertEquals(1,requests.get(),"Preview image bytes must still pass through the website");
            verify(fixture.storage).getSharedURL(fixture.attachment,Duration.ofSeconds(60));
        } finally {upstream.stop(0);}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"bytes=2-5","bytes=-4"})
    void publicImageWithValidRangeRedirectsToStorage(String range) {
        var signed=URI.create("https://storage.invalid/image?test-signature=fixture");var fixture=new Fixture(signed);
        fixture.web.get().uri(PUBLIC_PATH).header("Range",range).exchange().expectStatus().isTemporaryRedirect()
            .expectHeader().valueEquals("Location",signed.toString()).expectBody().isEmpty();
        verify(fixture.storage).getSharedURL(fixture.attachment,Duration.ofSeconds(60));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"bytes=0-1,4-5","items=0-1","bytes=-","bytes=0-99999999999999999"})
    void malformedRangeIsRejectedWithoutSigning(String range) {
        var fixture=new Fixture(URI.create("https://storage.invalid/image"));
        fixture.web.get().uri(PUBLIC_PATH).header("Range",range).exchange().expectStatus().isEqualTo(416)
            .expectHeader().doesNotExist("Location").expectHeader().valueEquals("Cache-Control","no-store")
            .expectBody().isEmpty();
        verifyNoInteractions(fixture.storage);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"missing-post,404","unpublished,404","archived,404","deleted,404",
        "retired-revision,404","unaccepted-revision,404","foreign-revision,409","missing-asset,404","private-asset,404",
        "foreign-owner,422","invalid-sha,422","invalid-type,422","invalid-policy,422","deleted-attachment,422",
        "missing-attachment,422","invalid-object-key,422","corrupt-revision,503"})
    void inaccessibleImageNeverIssuesSignedLocation(String problem,int status) {
        var fixture=new Fixture(URI.create("https://storage.invalid/image"));
        switch(problem) {
            case "missing-post" -> when(fixture.client.fetch(Post.class,NativeMirror.postName("sample"))).thenReturn(Mono.empty());
            case "unpublished" -> fixture.post.getSpec().setPublish(false);
            case "archived" -> NativeMirror.annotations(fixture.post).put(NativeMirror.ARCHIVED,"true");
            case "deleted" -> fixture.post.getSpec().setDeleted(true);
            case "retired-revision" -> NativeMirror.annotations(fixture.post).put(NativeMirror.PUBLIC,"new-revision");
            case "unaccepted-revision" -> NativeMirror.annotations(fixture.post).put(NativeMirror.JOURNAL,"another-revision");
            case "foreign-revision" -> fixture.revision.getSpec().setContentId("another-entry");
            case "missing-asset" -> fixture.asset.put("asset_id","another-asset");
            case "private-asset" -> fixture.asset.put("visibility","private");
            case "foreign-owner" -> fixture.attachment.getMetadata().getAnnotations().put(MediaService.OWNER,"another-entry");
            case "invalid-sha" -> fixture.attachment.getMetadata().getAnnotations().put(MediaService.HASH,"b".repeat(64));
            case "invalid-type" -> fixture.attachment.getSpec().setMediaType("image/jpeg");
            case "invalid-policy" -> fixture.attachment.getSpec().setPolicyName("another-policy");
            case "deleted-attachment" -> fixture.attachment.getMetadata().setDeletionTimestamp(java.time.Instant.EPOCH);
            case "missing-attachment" -> when(fixture.client.fetch(Attachment.class,"native-asset")).thenReturn(Mono.empty());
            case "invalid-object-key" -> fixture.asset.put("object_key","https://storage.invalid/image");
            case "corrupt-revision" -> { }
            default -> fail("Unknown fixture condition: "+problem);
        }
        fixture.updateRevision();
        if(problem.equals("corrupt-revision")){fixture.revision.getSpec().setRequestHash("invalid");}
        fixture.web.get().uri(PUBLIC_PATH).exchange().expectStatus().isEqualTo(status)
            .expectHeader().doesNotExist("Location").expectHeader().valueEquals("Cache-Control","private, no-store");
        verifyNoInteractions(fixture.storage);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"video/mp4","application/pdf"})
    void videoAndDownloadsPreserveFullPartialAndUnsatisfiableResponses(String type) throws Exception {
        var requests=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] bytes="0123456789".getBytes();
        upstream.createContext("/file",exchange->{
            requests.incrementAndGet();var range=exchange.getRequestHeaders().getFirst("Range");
            exchange.getResponseHeaders().add("Accept-Ranges","bytes");
            if("bytes=20-".equals(range)){
                exchange.getResponseHeaders().add("Content-Range","bytes */10");exchange.sendResponseHeaders(416,-1);
            } else {
                var body=range==null?bytes:"2345".getBytes();
                if(range!=null){exchange.getResponseHeaders().add("Content-Range","bytes 2-5/10");}
                exchange.sendResponseHeaders(range==null?200:206,body.length);exchange.getResponseBody().write(body);
            }
            exchange.close();
        });upstream.start();
        try {
            var fixture=new Fixture(URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/file"));
            fixture.asset.put("content_type",type);fixture.updateRevision();
            fixture.attachment.getSpec().setMediaType(type);fixture.attachment.getSpec().setDisplayName("report.pdf");
            var full=fixture.web.get().uri(PUBLIC_PATH).exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("Location").expectHeader().contentType(type)
                .expectHeader().valueEquals("Cache-Control","private, no-store")
                .expectHeader().valueEquals("Content-Length","10").expectHeader().valueEquals("Accept-Ranges","bytes");
            if(type.equals("application/pdf")){full.expectHeader().valueMatches("Content-Disposition","attachment;.*report\\.pdf.*");}
            else{full.expectHeader().doesNotExist("Content-Disposition");}
            full.expectBody().consumeWith(result -> assertArrayEquals(bytes,result.getResponseBody()));
            fixture.web.get().uri(PUBLIC_PATH).header("Range","bytes=2-5").exchange().expectStatus().isEqualTo(206)
                .expectHeader().doesNotExist("Location").expectHeader().valueEquals("Content-Range","bytes 2-5/10")
                .expectHeader().valueEquals("Content-Length","4")
                .expectBody().consumeWith(result -> assertArrayEquals("2345".getBytes(),result.getResponseBody()));
            fixture.web.get().uri(PUBLIC_PATH).header("Range","bytes=20-").exchange().expectStatus().isEqualTo(416)
                .expectHeader().doesNotExist("Location").expectHeader().valueEquals("Content-Range","bytes */10")
                .expectBody().isEmpty();
            assertEquals(3,requests.get());
        } finally {upstream.stop(0);}
    }

    @Test void signingFailureReturnsGatewayErrorWithoutLocation() {
        var fixture=new Fixture(URI.create("https://storage.invalid/image"));
        when(fixture.storage.getSharedURL(any(Attachment.class),any(Duration.class))).thenReturn(Mono.error(new IllegalStateException("Unavailable")));
        fixture.web.get().uri(PUBLIC_PATH).exchange().expectStatus().isEqualTo(502)
            .expectHeader().doesNotExist("Location").expectHeader().valueEquals("Cache-Control","private, no-store");
    }

    @Test void publicImageRedirectsWithoutFetchingBytesAndUsesSixtySecondSignature() throws Exception {
        var requests=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/image",exchange->{
            requests.incrementAndGet();byte[] bytes="image bytes".getBytes();
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();
        try {
            var signed=URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/image?test-signature=fixture");
            var fixture=new Fixture(signed);
            fixture.web.get().uri(PUBLIC_PATH).exchange().expectStatus().isTemporaryRedirect()
                .expectHeader().valueEquals("Location",signed.toString())
                .expectHeader().valueEquals("Cache-Control","private, no-store")
                .expectHeader().valueEquals("Referrer-Policy","no-referrer").expectBody().isEmpty();
            assertEquals(0,requests.get(),"The website must not request public image bytes");
            verify(fixture.storage).getSharedURL(fixture.attachment,Duration.ofSeconds(60));
        } finally {upstream.stop(0);}
    }
}
