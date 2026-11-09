package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Source-independent presentation of published ranking snapshots. */
final class LeaderboardRenderer {
    private LeaderboardRenderer() {}

    static String render(List<PackageViews.PublishedContent> items, PackageCodec codec) {
        var boards = items.stream().filter(item -> !item.articleOnly())
            .sorted(Comparator.comparingInt(item -> "overall".equals(item.data().get("task")) ? 0 : 1)).toList();
        if (boards.isEmpty()) {
            return "<div class='empty-state'><h2>榜单正在准备中</h2><p>核验并发布后的榜单会在这里展示。</p></div>";
        }
        var sourceNames = new TreeSet<String>();
        boards.forEach(item -> sourceNames.addAll(sourceNames(codec.tree(item.sources()))));
        var html = new StringBuilder("<section class='ranking-catalog' data-ranking-catalog aria-label='评分与榜单'>")
            .append("<div class='ranking-overview'><div><span class='ranking-kicker'>按任务选择 · 同口径比较</span><h2>找到适合你的 AI</h2><p>选择一份榜单，查看模型版本、来源成绩与排名依据。</p></div><div class='ranking-overview-count'><strong>")
            .append(boards.size()).append("</strong><span>本页榜单</span></div></div>")
            .append("<div class='ranking-controls' data-ranking-controls hidden><label>数据来源<select data-ranking-source><option value=''>全部来源</option>");
        for (var source : sourceNames) {
            html.append("<option value='").append(esc(source)).append("'>").append(esc(source)).append("</option>");
        }
        html.append("</select></label><label class='ranking-search'>搜索模型<input type='search' data-ranking-search placeholder='输入模型名称或版本' autocomplete='off'></label>")
            .append("<label>表格顺序<select data-ranking-sort><option value='rank'>来源名次</option><option value='name'>模型名称</option></select></label>")
            .append("<button type='button' class='ranking-reset' data-ranking-reset>重置筛选</button></div>")
            .append("<nav class='ranking-tabs' aria-label='选择榜单'>");
        for (var item : boards) {
            html.append("<a href='#").append(id(item)).append("' data-ranking-tab data-sources='")
                .append(esc(codec.tree(sourceNames(codec.tree(item.sources()))).toString())).append("'>")
                .append(esc(String.valueOf(item.content().get("title")))).append("</a>");
        }
        html.append("</nav><p class='ranking-navigation-note'>每份榜单保留自己的评分方法、日期和来源。</p>");
        for (var item : boards) {
            appendBoard(html, item, codec);
        }
        return html.append("</section>").toString();
    }

    private static void appendBoard(StringBuilder html, PackageViews.PublishedContent item, PackageCodec codec) {
        var data = codec.tree(item.data());
        var sources = codec.tree(item.sources());
        var entries = java.util.stream.StreamSupport.stream(data.path("entries").spliterator(), false)
            .sorted(Comparator.comparingInt(entry -> entry.path("rank").asInt())).toList();
        boolean editorial = "editorial_order".equals(data.path("ranking_mode").asText());
        boolean higher = data.path("higher_is_better").asBoolean();
        var max = entries.stream().filter(entry -> !entry.path("score").isNull())
            .map(entry -> decimal(entry.path("score"))).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        var unit = unit(data.path("score_unit").asText());
        var articleUrl = SiteSettings.safeUrl(item.url()) || item.url() != null && item.url().startsWith("/") && !item.url().startsWith("//")
            ? item.url() : "/aihyb/content/" + item.content_id();
        var mode = editorial ? "编辑排序" : "source_rank".equals(data.path("ranking_mode").asText()) ? "沿用来源名次" : "按本榜成绩排名";
        html.append("<section class='ranking-board' data-ranking-board id='").append(id(item))
            .append("' aria-labelledby='").append(id(item)).append("-title' data-sources='").append(esc(codec.tree(sourceNames(sources)).toString())).append("'>")
            .append("<header class='ranking-board-head'><div><span class='ranking-kicker'>").append(esc(mode))
            .append("</span><h2 id='").append(id(item)).append("-title'>").append(esc(String.valueOf(item.content().get("title"))))
            .append("</h2><p>").append(esc(String.valueOf(item.content().get("summary")))).append("</p></div>")
            .append("<a class='ranking-method-link' href='").append(esc(articleUrl)).append("'>评分方法与完整依据 ↗</a></header>")
            .append("<dl class='ranking-facts'><div><dt>数据来源</dt><dd>").append(esc(sourceName(sources)))
            .append("</dd></div><div><dt>快照日期</dt><dd><time datetime='").append(esc(data.path("as_of").asText())).append("'>")
            .append(esc(PricingRenderer.date(data.path("as_of").asText()))).append("</time></dd></div><div><dt>评分口径</dt><dd>")
            .append(esc(data.path("benchmark").asText())).append("</dd></div><div><dt>方法版本</dt><dd>")
            .append(esc(data.path("method_version").asText())).append("</dd></div></dl>")
            .append("<div class='ranking-table-toolbar'><p data-ranking-count role='status' aria-live='polite'>").append(entries.size())
            .append(" 个模型配置</p><span>").append(editorial ? "保留编辑名次" : higher ? "成绩越高越好" : "成绩越低越好")
            .append(" · ").append(esc(unit)).append("</span></div>")
            .append("<p class='ranking-scroll-hint'>左右滑动查看完整榜单 →</p>")
            .append("<div class='ranking-table-scroll' tabindex='0' role='region' aria-label='").append(esc(String.valueOf(item.content().get("title"))))
            .append(" 完整成绩'><table class='ranking-data-table'><thead><tr><th scope='col'>排名</th><th scope='col'>模型 / 版本</th><th scope='col'>")
            .append(editorial ? "参考成绩" : "来源成绩").append("</th><th scope='col'>排名依据</th><th scope='col'>来源</th></tr></thead><tbody>");
        int order = 0;
        for (var entry : entries) {
            var name = entry.path("tool_name").asText();
            var version = entry.path("model_version").asText();
            var score = entry.path("score");
            html.append("<tr data-ranking-row data-order='").append(order++).append("' data-name='").append(esc(name + " " + version)).append("'>")
                .append("<td><span class='ranking-position").append(entry.path("rank").asInt() <= 3 ? " ranking-position-top" : "")
                .append("'>").append(entry.path("rank").asInt()).append("</span></td><th scope='row'><strong>").append(esc(name))
                .append("</strong><small>").append(esc(version)).append("</small>");
            if (SiteSettings.safeUrl(entry.path("product_url").asText())) {
                html.append("<a class='ranking-product' data-link-kind='reference' href='").append(esc(entry.path("product_url").asText()))
                    .append("' target='_blank' rel='noopener noreferrer'>使用入口 ↗</a>");
            }
            html.append("</th><td class='ranking-score-cell'><strong>").append(score.isNull() ? "未提供" : esc(score.asText()))
                .append("</strong><small>").append(esc(unit)).append("</small>");
            if (!editorial && higher && !score.isNull() && max.signum() > 0) {
                var width = decimal(score).multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
                html.append("<span class='ranking-score-bar' aria-hidden='true'><i style='width:").append(width.toPlainString()).append("%'></i></span>");
            }
            html.append("</td><td class='ranking-reason'><details><summary>查看依据</summary><p>").append(esc(entry.path("reason").asText()))
                .append("</p></details></td><td class='ranking-source-cell'>").append(PricingRenderer.source(sources, entry.path("source_id").asText()))
                .append("</td></tr>");
        }
        html.append("</tbody></table></div><p class='ranking-no-results' data-ranking-empty hidden>没有找到匹配的模型，请更换关键词或重置筛选。</p>")
            .append("<footer class='ranking-board-foot'><span>").append(editorial ? "编辑名次保持不变；参考成绩不用于自动合成总分。" : "分数只在本榜单口径内比较；筛选不改变原名次。")
            .append(!editorial && higher ? "条形长度以本榜最高分为参照。" : "")
            .append("</span><a href='").append(esc(articleUrl)).append("'>查看来源与使用说明 ↗</a></footer></section>");
    }

    private static BigDecimal decimal(JsonNode value) {
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException error) { return BigDecimal.ZERO; }
    }

    private static String sourceName(JsonNode sources) { return String.join(" / ", sourceNames(sources)); }

    private static List<String> sourceNames(JsonNode sources) {
        var names = new java.util.LinkedHashSet<String>();
        for (var source : sources) {
            var author = source.path("author").asText("");
            if (!author.isBlank()) { names.add(author); continue; }
            try {
                var host = URI.create(source.path("url").asText()).getHost();
                names.add(host == null ? "来源材料" : host);
            } catch (IllegalArgumentException error) { names.add("来源材料"); }
        }
        return names.isEmpty() ? List.of("来源待补充") : List.copyOf(names);
    }

    private static String id(PackageViews.PublishedContent item) { return "ranking-" + item.content_id(); }
    private static String unit(String value) {
        return Map.of("percent", "%", "elo", "Elo", "index", "指数", "seconds", "秒", "custom", "来源计分").getOrDefault(value, value);
    }
    private static String esc(String value) { return PackageRenderer.escape(value); }
}
