package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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
        return fragment.replaceFirst("<article><h1>.*?</h1><p class='summary'>.*?</p>", "<section class='aihyb-native'>")
            .replace("</article>", "</section>");
    }

    public String articleFragment(JsonNode payload, String body, boolean draft, String revision) {
        var content = payload.get("content");
        var title = content.get("title").asText();
        var html = new StringBuilder();
        if (draft) { html.append("<aside class='notice'>待审核预览 · 本页内容尚未公开</aside>"); }
        html.append("<article><h1>").append(escape(title)).append("</h1><p class='summary'>")
            .append(escape(content.get("summary").asText())).append("</p>").append(mediaBody(payload,body,draft,revision));
        html.append(assetList(payload,draft,revision));
        html.append(structured(payload.get("kind").asText(), payload.get("data")));
        html.append("<h2>内容来源</h2><ul>");
        for (var source : payload.get("sources")) {
            html.append("<li>");
            if (source.get("url").isTextual()) {
                html.append("<a target='_blank' rel='noopener noreferrer' href='")
                    .append(escape(source.get("url").asText())).append("'>查看完整原文 ↗</a>");
            } else { html.append("作者提供的文件或本站材料"); }
            if (source.get("author").isTextual()) {
                html.append(" · ").append(escape(source.get("author").asText()));
            }
            html.append("</li>");
        }
        html.append("</ul><small>版本 ").append(escape(revision)).append("</small></article>");
        return html.toString();
    }
    public static String mediaUrl(JsonNode payload,JsonNode asset,boolean preview,String revision) {
        var id=payload.get("content_id").asText();var assetId=asset.get("asset_id").asText();
        return preview?PackageService.ADMIN+"/entries/"+id+"/media/"+revision+"/"+assetId
            :"/apis/api.aihyb.run/v1alpha1/media/"+id+"/file/"+revision+"/"+assetId;
    }
    private String mediaBody(JsonNode payload,String body,boolean preview,String revision) {
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
        return document.body().html();
    }
    private String assetList(JsonNode payload,boolean preview,String revision){
        var html=new StringBuilder();
        for(var asset:payload.get("assets")){
            if(!preview&&!"public".equals(asset.get("visibility").asText())){continue;}
            if(html.isEmpty()){html.append("<section class='aihyb-assets'><h2>媒体与附件</h2>");}
            var url=escape(mediaUrl(payload,asset,preview,revision));var type=asset.get("content_type").asText();var alt=escape(asset.get("alt").asText());
            html.append("<figure>");
            if(type.startsWith("image/")){html.append("<img loading='lazy' style='max-width:100%;height:auto' src='").append(url).append("' alt='").append(alt).append("'>");}
            else if(type.startsWith("video/")){html.append("<video controls preload='metadata' style='max-width:100%;height:auto' src='").append(url).append("'></video>");}
            else{html.append("<a href='").append(url).append("'>下载附件：").append(alt).append("</a>");}
            html.append("<figcaption>").append(alt).append(preview&&asset.get("visibility").asText().equals("private")?"（仅审核可见）":"").append("</figcaption></figure>");
        }
        if(!html.isEmpty()){html.append("</section>");}return html.toString();
    }

    private String structured(String kind, JsonNode data) {
        var html = new StringBuilder();
        if (kind.equals("review")) {
            html.append("<h2>结论与依据</h2><ul>");
            for (var item : data.get("conclusions")) {
                html.append("<li>").append(escape(item.get("text").asText()))
                    .append("<small>依据：").append(escape(item.get("evidence").asText())).append("</small></li>");
            }
            html.append("</ul>");
        }
        if (kind.equals("tutorial")) {
            html.append("<p class='tag'>适用版本：").append(escape(data.get("tool_version").asText()))
                .append(" · 核验状态：").append(escape(data.get("verification_status").asText())).append("</p>");
        }
        if (kind.equals("leaderboard")) {
            html.append("<h2>任务榜单</h2><p>").append(escape(data.get("benchmark").asText()))
                .append(" · ").append(escape(data.get("score_unit").asText()))
                .append(" · ").append(escape(data.get("as_of").asText())).append("</p>")
                .append("<div class='scroll'><table><thead><tr><th>名次</th><th>工具</th><th>型号</th><th>成绩</th><th>理由</th></tr></thead><tbody>");
            for (var item : data.get("entries")) {
                html.append("<tr>");
                for (var field : List.of("rank", "tool_name", "model_version", "score", "reason")) {
                    html.append("<td>").append(escape(item.get(field).isNull() ? "未知" : item.get(field).asText())).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</tbody></table></div>");
        }
        if (kind.equals("pricing")) {
            html.append("<h2>价格与额度</h2><div class='filters'>筛选：<select id='offer-filter'><option value=''>全部类型</option><option value='subscription'>会员订阅</option><option value='api'>API</option></select>")
                .append("<select id='unit-filter'><option value=''>全部单位</option><option value='month'>月付</option><option value='year'>年付</option><option value='million_input_tokens'>百万输入 token</option><option value='million_output_tokens'>百万输出 token</option></select></div>")
                .append("<p>勾选最多三项，按相同币种和单位对比。</p><div class='scroll'><table id='plans'><thead><tr><th>对比</th><th>方案</th><th>金额</th><th>单位/扣款周期</th><th>地区/渠道</th><th>免费额度</th><th>限制</th></tr></thead><tbody>");
            for (var plan : data.get("plans")) {
                html.append("<tr data-offer='").append(escape(plan.get("offer_kind").asText()))
                    .append("' data-unit='").append(escape(plan.get("price_unit").asText()))
                    .append("' data-currency='").append(escape(plan.get("currency").asText())).append("'>")
                    .append("<td><input type='checkbox' aria-label='选择对比方案'></td><td>")
                    .append(escape(plan.get("name").asText())).append("</td><td>")
                    .append(plan.get("amount").isNull() ? "未知" : escape(plan.get("amount").asText()))
                    .append(" ").append(escape(plan.get("currency").asText())).append("</td><td>")
                    .append(escape(plan.get("price_unit").asText())).append(" / ")
                    .append(escape(plan.get("billing_cycle").asText())).append("</td><td>")
                    .append(escape(plan.get("region").asText())).append(" / ")
                    .append(escape(plan.get("channel").asText())).append("</td><td>");
                var quota = plan.get("free_quota");
                html.append(quota.get("amount").isNull() ? "未知" : escape(quota.get("amount").asText()))
                    .append(" ").append(escape(quota.get("unit").asText())).append(" / ")
                    .append(escape(quota.get("reset_period").asText())).append("</td><td>");
                for (var limit : plan.get("limits")) { html.append(escape(limit.asText())).append("<br>"); }
                html.append("</td></tr>");
            }
            html.append("</tbody></table></div><p id='compare-message' role='status'></p><div id='comparison' class='scroll'></div>");
        }
        return html.toString();
    }

    public String listing(String kind, PackageViews.Page<PackageViews.PublishedContent> entries) {
        return listing(kind, entries, null);
    }

    public String listing(String kind, PackageViews.Page<PackageViews.PublishedContent> entries, String task) {
        var labels = java.util.Map.of("review", "能力测评", "tutorial", "使用技巧与教程", "leaderboard", "评分与榜单", "pricing", "价格对比");
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
            + "<style>body{margin:0;background:#f5f7fa;color:#172036;font:16px/1.75 system-ui,sans-serif}main,nav{max-width:1080px;margin:auto;padding:24px}nav{display:flex;gap:20px;flex-wrap:wrap}a{color:#2454c6}article,.card{background:white;padding:26px;border:1px solid #dde3ee;border-radius:14px;margin:18px 0}h1{line-height:1.35}.summary{color:#536178}.tag,small{color:#617089}small{display:block;overflow-wrap:anywhere}.notice{background:#fff0c6;padding:16px;border-radius:10px}.scroll{overflow-x:auto}table{border-collapse:collapse;min-width:660px;width:100%}th,td{border:1px solid #dce2eb;padding:12px;text-align:left}th{background:#edf2fc}pre{overflow:auto;background:#eef2f8;padding:16px}img{max-width:100%}input,select,button{padding:9px;margin:5px}#compare-message{color:#9a3b12}</style></head><body>"
            + "<nav><strong>AI好用榜 · PoC</strong><a href='/aihyb'>首页</a><a href='/aihyb/review'>测评</a><a href='/aihyb/leaderboard'>榜单</a><a href='/aihyb/pricing'>价格</a><a href='/aihyb/tutorial'>教程</a><a href='/console/aihyb-packages'>内容审核</a></nav><main>"
            + content + "</main>" + PRICE_SCRIPT + "</body></html>";
    }

    private static final String PRICE_SCRIPT = """
        <script>
        (()=>{const table=document.querySelector('#plans');if(!table)return;
        const rows=[...table.querySelectorAll('tbody tr')],message=document.querySelector('#compare-message');
        const filter=()=>{const offer=document.querySelector('#offer-filter').value,unit=document.querySelector('#unit-filter').value;
          rows.forEach(row=>{row.hidden=(offer&&row.dataset.offer!==offer)||(unit&&row.dataset.unit!==unit)})};
        document.querySelector('#offer-filter').addEventListener('change',filter);document.querySelector('#unit-filter').addEventListener('change',filter);
        table.addEventListener('change',event=>{const selected=rows.filter(row=>row.querySelector('input').checked);
          if(selected.length>3){event.target.checked=false;message.textContent='最多对比三项';return}
          const basis=new Set(selected.map(row=>row.dataset.currency+'/'+row.dataset.unit));
          if(basis.size>1){event.target.checked=false;message.textContent='请选择币种和单位相同的方案';return}
          message.textContent=selected.length?'已选择 '+selected.length+' 项，同币种同单位对比':'请勾选方案';
          const target=document.querySelector('#comparison');target.replaceChildren();if(!selected.length)return;
          const result=document.createElement('table'),head=table.querySelector('thead').cloneNode(true);head.querySelector('th').remove();result.append(head);
          const body=document.createElement('tbody');selected.forEach(row=>{const copy=row.cloneNode(true);copy.querySelector('td').remove();copy.hidden=false;body.append(copy)});
          result.append(body);target.append(result);
        });})();
        </script>
        """;
}
