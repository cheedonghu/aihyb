package run.aihyb.pluginaihyb;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class PublicMediaController implements CustomEndpoint {
    private final MediaController media;
    public PublicMediaController(MediaController media){this.media=media;}
    public GroupVersion groupVersion(){return new GroupVersion("api.aihyb.run","v1alpha1");}
    public RouterFunction<ServerResponse> endpoint(){return SpringdocRouteBuilder.route()
        .GET("/media/{id}/file/{revision}/{assetId}",r -> media.proxy(r,false),b -> b.operationId("getPublishedAsset").tag("AIHYBMedia")
            .parameter(parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class))
            .parameter(parameterBuilder().name("revision").in(ParameterIn.PATH).required(true).implementation(String.class))
            .parameter(parameterBuilder().name("assetId").in(ParameterIn.PATH).required(true).implementation(String.class)))
        .build();}
}
