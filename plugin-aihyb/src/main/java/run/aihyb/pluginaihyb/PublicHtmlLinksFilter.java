package run.aihyb.pluginaihyb;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

/** Apply policy to final public HTML, including old posts and theme-generated links. */
@Component
public class PublicHtmlLinksFilter implements AdditionalWebFilter {
    private final SiteSettings settings;
    public PublicHtmlLinksFilter(SiteSettings settings) { this.settings=settings; }
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        var path=exchange.getRequest().getURI().getPath();
        if(!"GET".equals(exchange.getRequest().getMethod().name()) || path.startsWith("/apis/") || path.startsWith("/console")
            || path.startsWith("/login") || path.startsWith("/uc") || path.startsWith("/actuator") || path.startsWith("/v3/")
            || path.startsWith("/themes/") || path.startsWith("/aihyb/assets/") || path.startsWith("/aihyb/site-media/")) return chain.filter(exchange);
        var response=new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                var type=getHeaders().getContentType();
                if(type==null || !MediaType.TEXT_HTML.isCompatibleWith(type) || (getStatusCode()!=null && (getStatusCode().is3xxRedirection() || getStatusCode().is5xxServerError()))) return super.writeWith(body);
                return DataBufferUtils.join(body,4*1024*1024).flatMap(buffer->{
                    byte[] bytes=new byte[buffer.readableByteCount()]; buffer.read(bytes); DataBufferUtils.release(buffer);
                    return settings.get().flatMap(config->{
                        var document=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8)); document.outputSettings().prettyPrint(false);
                        if(document.select(".aihyb-site-footer").isEmpty()) document.body().appendElement("footer")
                            .addClass("aihyb-site-footer").html(SiteFooter.render(config));
                        OutboundLinks.apply(document,exchange.getRequest().getURI(),config.links());
                        if(document.select("link[href^='/aihyb/assets/site.css']").isEmpty()) document.head().appendElement("link").attr("rel","stylesheet").attr("href","/aihyb/assets/site.css?v=20261023");
                        var result=document.outerHtml().getBytes(StandardCharsets.UTF_8);
                        getHeaders().remove("ETag"); getHeaders().remove("Content-Length");getHeaders().set("Cache-Control","no-cache");
                        return super.writeWith(Mono.just(bufferFactory().wrap(result)));
                    });
                });
            }
            @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) { return writeWith(Flux.from(body).concatMap(Flux::from)); }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }
}
