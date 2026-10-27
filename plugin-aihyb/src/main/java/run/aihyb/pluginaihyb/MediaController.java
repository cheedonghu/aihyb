package run.aihyb.pluginaihyb;

import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.time.Duration;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class MediaController implements CustomEndpoint {
    private final MediaService media;
    private final PackageCodec codec;
    private final WebClient http=WebClient.builder().clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(
        java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(10)).build())).build();
    public MediaController(MediaService media,PackageCodec codec){this.media=media;this.codec=codec;}
    public GroupVersion groupVersion(){return new GroupVersion("console.api.aihyb.run","v1alpha1");}
    public RouterFunction<ServerResponse> endpoint(){return SpringdocRouteBuilder.route()
        .GET("/media-configuration",r -> media.configuration().flatMap(v -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(codec.serialize(v))),
            b -> b.operationId("mediaConfiguration").tag("AIHYBMedia").response(responseBuilder().implementation(MediaService.MediaConfiguration.class)))
        .POST("/entries/{id}/assets",r -> r.principal().flatMap(actor -> {
            // Raw bounded stream: never let a multipart parser spool unlimited input or extra parts.
            FilePart file=new FilePart(){
                public String filename(){return r.queryParam("filename").orElse("attachment");}
                public String name(){return "file";}
                public HttpHeaders headers(){var headers=new HttpHeaders();headers.setContentType(r.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM));return headers;}
                public Flux<DataBuffer> content(){return r.bodyToFlux(DataBuffer.class);}
                public Mono<Void> transferTo(java.nio.file.Path p){return Mono.error(new UnsupportedOperationException());}
            };
            return media.upload(r.pathVariable("id"),actor.getName(),file)
                .flatMap(value -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(codec.serialize(value)));
        }),b -> b.operationId("uploadEntryAsset").tag("AIHYBMedia")
            .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
            .parameter(parameterBuilder().name("filename").in(ParameterIn.QUERY).required(true).implementation(String.class))
            .requestBody(binaryBody())
            .response(responseBuilder().implementation(MediaService.Upload.class)))
        .GET("/entries/{id}/media/{revision}/{assetId}",r -> proxy(r,true),b -> b.operationId("previewEntryAsset").tag("AIHYBMedia")
            .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
            .parameter(parameterBuilder().name("revision").in(ParameterIn.PATH).required(true).implementation(String.class))
            .parameter(parameterBuilder().name("assetId").in(ParameterIn.PATH).required(true).implementation(String.class)))
        .build().filter((r,next)->Mono.defer(()->next.handle(r)).onErrorResume(PackageProblem.class,AdminController::error));}
    private org.springdoc.core.fn.builders.requestbody.Builder binaryBody(){
        var body=requestBodyBuilder().required(true).description("Raw file bytes. Set Content-Type to the actual file MIME type; never JSON or multipart.");
        MediaService.TYPES.stream().sorted().forEach(type -> body.content(contentBuilder().mediaType(type).schema(schemaBuilder().type("string").format("binary"))));
        return body;
    }
    public Mono<ServerResponse> proxy(ServerRequest request,boolean preview){
        var range=request.headers().firstHeader(HttpHeaders.RANGE);
        if(range!=null&&!range.matches("bytes=(?:[0-9]{1,16}-[0-9]{0,16}|-[0-9]{1,16})")){
            return ServerResponse.status(416).header("Cache-Control","no-store").build();
        }
        return media.resolve(request.pathVariable("id"),request.pathVariable("revision"),request.pathVariable("assetId"),preview)
            .flatMap(attachment -> media.signed(attachment)
                .switchIfEmpty(Mono.error(new PackageProblem(503,"媒体签名暂不可用，请稍后重试"))).flatMap(uri -> {
                if(!preview&&attachment.getSpec().getMediaType().startsWith("image/")){
                    return ServerResponse.temporaryRedirect(uri).header("Cache-Control","private, no-store")
                        .header("Referrer-Policy","no-referrer").build();
                }
                var upstream=http.get().uri(uri);
                if(range!=null){upstream.header(HttpHeaders.RANGE,range);}
                return upstream.retrieve()
                    .onStatus(s -> s.value()!=200&&s.value()!=206&&s.value()!=416,
                        response -> response.releaseBody().thenReturn(new PackageProblem(502,"媒体存储暂不可用")))
                    .onStatus(s -> s.value()==416,response -> Mono.empty())
                    .toEntityFlux(DataBuffer.class).flatMap(response -> {
                    int status=response.getStatusCode().value();
                    var builder=ServerResponse.status(status).header("Cache-Control","private, no-store")
                        .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer")
                        .header("Content-Security-Policy","default-src 'none'; sandbox");
                    var type=attachment.getSpec().getMediaType();
                    builder.contentType(MediaType.parseMediaType(type));
                    if(!type.startsWith("image/")&&!type.startsWith("video/")){
                        builder.header(HttpHeaders.CONTENT_DISPOSITION,org.springframework.http.ContentDisposition.attachment()
                            .filename(attachment.getSpec().getDisplayName(),java.nio.charset.StandardCharsets.UTF_8).build().toString());
                    }
                    for(var header:java.util.List.of(HttpHeaders.CONTENT_LENGTH,HttpHeaders.CONTENT_RANGE,HttpHeaders.ACCEPT_RANGES)){
                        var value=response.getHeaders().get(header);if(value!=null&&!value.isEmpty()){builder.header(header,value.getFirst());}
                    }
                    return builder.body(response.getBody().timeout(Duration.ofSeconds(30))
                        .onErrorMap(failure -> new PackageProblem(502,"媒体传输中断，请重试")),DataBuffer.class);
                });
            })).timeout(Duration.ofSeconds(30))
            .onErrorMap(failure -> !(failure instanceof PackageProblem),failure -> new PackageProblem(502,"媒体连接暂不可用，请稍后重试"))
            .onErrorResume(PackageProblem.class,AdminController::error);
    }
}
