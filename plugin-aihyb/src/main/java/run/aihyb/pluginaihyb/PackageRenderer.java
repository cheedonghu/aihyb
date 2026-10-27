package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class PackageRenderer {
    private final Parser parser = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(true)
        .sanitizeUrls(true).extensions(List.of(TablesExtension.create())).build();

    public String markdown(String value) {
        value=value.replaceAll("asset://([a-z0-9][a-z0-9-]{0,79})","/aihyb-asset/$1");
        var safe = Jsoup.clean(renderer.render(parser.parse(value)), "https://aihyb.invalid/", Safelist.relaxed().preserveRelativeLinks(true)
            .addTags("h1", "h2", "h3", "h4", "h5", "h6")
            .addAttributes("code", "class")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer"));
        var document = Jsoup.parseBodyFragment(safe);
        document.select("table").forEach(table -> table.wrap("<div class='scroll'></div>"));
        return document.body().html();
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public String article(JsonNode payload, String body, boolean draft, String revision) {
        return page(payload.get("content").get("title").asText(), payload.get("content").get("summary").asText(),
            articleFragment(payload, body, draft, revision));
    }

    public String nativeBody(JsonNode payload, String body, String revision) {
        var fragment = articleFragment(payload, body, false, revision);
        var document=Jsoup.parseBodyFragment(fragment); document.outputSettings().prettyPrint(false);
        var article=document.selectFirst("article");
        if(article!=null) {
            for(var child:new java.util.ArrayList<>(article.children())) {
                if(child.tagName().equals("h1") || (child.tagName().equals("p") && child.hasClass("summary"))) child.remove();
            }
            article.tagName("section").addClass("aihyb-native");
        }
        return document.body().html();
    }

    public String nativeBody(JsonNode payload,String body,String revision,SiteSettings.Settings settings) {
        var document=Jsoup.parseBodyFragment(nativeBody(payload,body,revision));document.outputSettings().prettyPrint(false);
        var task=payload.path("data").path("task").asText();
        var label=task.equals("overall")?"综合":settings.tags().stream().filter(tag->tag.id().equals(task)).map(SiteSettings.Tag::label).findFirst().orElse(task);
        for(var fact:document.select(".report-facts > div")) if(fact.select("dt").text().equals("任务场景")) fact.select("dd").forEach(value->value.text(label));
        var tags=payload.path("content").path("task_tags");var ids=new java.util.HashSet<String>();
        if(tags.isArray()) tags.forEach(tag->ids.add(tag.asText())); else ids.add(task);
        var nav=new org.jsoup.nodes.Element("nav").addClass("aihyb-task-tags").attr("aria-label","相关任务标签");
        for(var tag:settings.tags()) if(tag.enabled()&&ids.contains(tag.id())) nav.appendElement("a")
            .attr("href","/aihyb/"+ContentSection.of(payload)+"?task="+tag.id()).text(tag.label());
        var section=document.selectFirst(".aihyb-native");if(section!=null&&!nav.children().isEmpty()) section.prependChild(nav);
        return document.body().html();
    }

    public String articleFragment(JsonNode payload, String body, boolean draft, String revision) {
        var content = payload.get("content");
        var title = content.get("title").asText();
        var html = new StringBuilder();
        if (draft) { html.append("<aside class='notice'>待审核预览 · 本页内容尚未公开</aside>"); }
        html.append("<article><h1>").append(escape(title)).append("</h1><p class='summary'>")
            .append(escape(content.get("summary").asText())).append("</p>");
        var resolved = mediaBody(payload, body, draft, revision);
        var attachments = assetList(payload, draft, revision, resolved);
        var report = ContentSection.news(payload)
            ? NewsBodyRenderer.render(payload, resolved.body().html(), attachments, revision)
            : "review".equals(payload.path("kind").asText())
            ? ReviewBodyRenderer.render(payload, resolved.body().html(), attachments, revision)
            : ModuleBodyRenderer.render(payload, resolved.body().html(), attachments, revision);
        var fragment=Jsoup.parseBodyFragment(html.append(report).append("</article>").toString());
        var sources=new java.util.HashSet<String>(); payload.path("sources").forEach(source->sources.add(source.path("url").asText()));
        for(var link:fragment.select("a[href]")) if(sources.contains(link.attr("href"))) link.attr("data-link-kind","reference");
        return fragment.body().html();
    }
    public static String mediaUrl(JsonNode payload,JsonNode asset,boolean preview,String revision) {
        var id=payload.get("content_id").asText();var assetId=asset.get("asset_id").asText();
        return preview?PackageService.ADMIN+"/entries/"+id+"/media/"+revision+"/"+assetId
            :"/apis/api.aihyb.run/v1alpha1/media/"+id+"/file/"+revision+"/"+assetId;
    }
    private org.jsoup.nodes.Document mediaBody(JsonNode payload,String body,boolean preview,String revision) {
        var document=Jsoup.parseBodyFragment(body);
        for(var element:document.select("img[src],a[href]")) {
            var attribute=element.tagName().equals("img")?"src":"href";var target=element.attr(attribute);
            if(!target.startsWith("/aihyb-asset/")){continue;}
            var id=target.substring("/aihyb-asset/".length());
            JsonNode selected=null;for(var asset:payload.get("assets")){if(id.equals(asset.get("asset_id").asText())){selected=asset;break;}}
            if(selected==null||(!preview&&!"public".equals(selected.get("visibility").asText()))){element.remove();continue;}
            var url=mediaUrl(payload,selected,preview,revision);
            if(selected.get("content_type").asText().startsWith("video/")){
                var video=new org.jsoup.nodes.Element("video").attr("controls","").attr("preload","metadata")
                    .attr("src",url).attr("style","max-width:100%;height:auto");element.replaceWith(video);
            }else{element.attr(attribute,url);if(element.tagName().equals("img")){element.attr("loading","lazy").attr("style","max-width:100%;height:auto");}}
        }
        for(var p:document.select("p")){
            var match=java.util.regex.Pattern.compile("^\\[video:(bilibili|youtube):([A-Za-z0-9_-]+)]$").matcher(p.text());
            if(!match.matches()){continue;}
            var provider=match.group(1);var id=match.group(2);String src=null;
            if(provider.equals("bilibili")&&id.matches("BV[A-Za-z0-9]{10}")){src="https://player.bilibili.com/player.html?bvid="+id+"&autoplay=0";}
            if(provider.equals("youtube")&&id.matches("[A-Za-z0-9_-]{11}")){src="https://www.youtube-nocookie.com/embed/"+id;}
            if(src!=null){p.empty().appendElement("iframe").attr("src",src).attr("title","视频播放器")
                .attr("loading","lazy").attr("referrerpolicy","no-referrer").attr("allowfullscreen","")
                .attr("sandbox","allow-scripts allow-same-origin allow-presentation")
                .attr("style","width:100%;aspect-ratio:16/9;border:0");}
        }
        return document;
    }
    private String assetList(JsonNode payload,boolean preview,String revision,org.jsoup.nodes.Document body){
        var links = Set.copyOf(body.select("a[href]").eachAttr("href"));
        var images = Set.copyOf(body.select("img[src]").eachAttr("src"));
        var videos = Set.copyOf(body.select("video[src],video source[src]").eachAttr("src"));
        var html=new StringBuilder();
        for(var asset:payload.get("assets")){
            if(!preview&&!"public".equals(asset.get("visibility").asText())){continue;}
            var mediaUrl = mediaUrl(payload,asset,preview,revision);
            var type = asset.get("content_type").asText();
            if(links.contains(mediaUrl)||(type.startsWith("image/")&&images.contains(mediaUrl))
                ||(type.startsWith("video/")&&videos.contains(mediaUrl))){continue;}
            if(html.isEmpty()){html.append("<section class='aihyb-assets'><h2>媒体与附件</h2>");}
            var url=escape(mediaUrl);var alt=escape(asset.get("alt").asText());
            html.append("<figure>");
            if(type.startsWith("image/")){html.append("<img loading='lazy' style='max-width:100%;height:auto' src='").append(url).append("' alt='").append(alt).append("'>");}
            else if(type.startsWith("video/")){html.append("<video controls preload='metadata' style='max-width:100%;height:auto' src='").append(url).append("'></video>");}
            else{html.append("<a href='").append(url).append("'>下载附件：").append(alt).append("</a>");}
            html.append("<figcaption>").append(alt).append(preview&&asset.get("visibility").asText().equals("private")?"（仅审核可见）":"").append("</figcaption></figure>");
        }
        if(!html.isEmpty()){html.append("</section>");}return html.toString();
    }

    public String listing(String kind, PackageViews.Page<PackageViews.PublishedContent> entries) {
        return listing(kind, entries, null);
    }

    public String listing(String kind, PackageViews.Page<PackageViews.PublishedContent> entries, String task) {
        var labels = PortalRenderer.LABELS;
        var label = kind == null ? "四模块内容" : labels.getOrDefault(kind, "四模块内容");
        var html = new StringBuilder("<h1>" + label + "</h1><p>按任务筛选：</p><form><input name='task' value='" + escape(task == null ? "" : task) + "' placeholder='例如 office、image、presentation' aria-label='任务分类'><button>筛选</button></form><section class='cards'>");
        for (var item : entries.items()) {
            html.append("<article class='card'><p class='tag'>").append(labels.get(item.kind())).append("</p><h2><a href='/aihyb/content/")
                .append(escape(item.content_id())).append("'>").append(escape(item.content().get("title").toString()))
                .append("</a></h2><p>").append(escape(item.content().get("summary").toString())).append("</p></article>");
        }
        if (entries.items().isEmpty()) { html.append("<p>暂时没有符合条件的已发布内容。</p>"); }
        html.append("</section><p>共 ").append(entries.total()).append(" 条 · 第 ").append(entries.page()).append(" 页</p>");
        var filter = (task == null || task.isBlank() ? "" : "task=" + java.net.URLEncoder.encode(task, java.nio.charset.StandardCharsets.UTF_8) + "&amp;") + "size=" + entries.size() + "&amp;";
        if (entries.page() > 1) { html.append("<a href='?").append(filter).append("page=").append(entries.page() - 1).append("'>上一页</a> "); }
        if (entries.page() * entries.size() < entries.total()) {
            html.append("<a href='?").append(filter).append("page=").append(entries.page() + 1).append("'>下一页</a>");
        }
        return page(label, "AI好用榜四模块已发布内容", html.toString());
    }

    public static String priceScript() { return PRICE_SCRIPT; }

    private String page(String title, String summary, String content) {
        return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1'>"
            + "<title>" + escape(title) + " · AI好用榜</title><meta name='description' content='" + escape(summary) + "'>"
            + "<link rel='stylesheet' href='/aihyb/assets/pricing.css?v=20261021'>"
            + "<link rel='stylesheet' href='/aihyb/assets/review.css?v=20261023-body3'>"
            + "<style>body{margin:0;background:#f5f7fa;color:#172036;font:16px/1.75 system-ui,sans-serif}main,nav{max-width:1080px;margin:auto;padding:24px}nav{display:flex;gap:20px;flex-wrap:wrap}a{color:#2454c6}article,.card{background:white;padding:26px;border:1px solid #dde3ee;border-radius:14px;margin:18px 0}h1{line-height:1.35}.summary{color:#536178}.tag,small{color:#617089}small{display:block;overflow-wrap:anywhere}.notice{background:#fff0c6;padding:16px;border-radius:10px}.scroll{overflow-x:auto}table{border-collapse:collapse;min-width:660px;width:100%}th,td{border:1px solid #dce2eb;padding:12px;text-align:left}th{background:#edf2fc}pre{overflow:auto;background:#eef2f8;padding:16px}img{max-width:100%}input,select,button{padding:9px;margin:5px}#compare-message{color:#9a3b12}</style></head><body>"
            + "<nav><strong>AI好用榜 · PoC</strong><a href='/aihyb'>首页</a><a href='/aihyb/review'>测评</a><a href='/aihyb/leaderboard'>榜单</a><a href='/aihyb/pricing'>价格</a><a href='/aihyb/tutorial'>教程</a><a href='/console/aihyb-packages'>内容审核</a></nav><main>"
            + content + "</main>" + PRICE_SCRIPT + "</body></html>";
    }

    private static final String PRICE_SCRIPT = """
        <script>
        (()=>{const table=document.querySelector('#plans');if(!table)return;
          table.querySelectorAll('tr').forEach(row=>{const cell=row.cells[0];if(cell&&(cell.querySelector('input[type=checkbox]')||cell.textContent.trim()==='对比'))cell.remove()});
          const previous=table.closest('.scroll')?.previousElementSibling;
          if(previous&&previous.textContent.includes('勾选'))previous.remove();
          document.querySelector('#compare-message')?.remove();document.querySelector('#comparison')?.remove();
        })();
        </script>
        """;
}
