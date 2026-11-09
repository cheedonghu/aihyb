package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Published rankings keep provenance in the footer, separate from the comparison. */
final class LeaderboardRenderer {
    private LeaderboardRenderer() {}

    static String render(List<PackageViews.PublishedContent> items, PackageCodec codec) {
        var boards = items.stream().filter(item -> !item.articleOnly())
            .sorted(Comparator.comparingInt(item -> "overall".equals(item.data().get("task")) ? 0 : 1)).toList();
        if (boards.isEmpty()) {
            return "<div class='empty-state'><h2>榜单正在准备中</h2><p>核验并发布后的榜单会在这里展示。</p></div>";
        }
        var html = new StringBuilder("<section class='ranking-catalog' data-ranking-catalog aria-label='评分与榜单'>")
            .append("<div class='ranking-controls' data-ranking-controls hidden><label class='ranking-search'>搜索模型<input type='search' data-ranking-search placeholder='输入模型名称' autocomplete='off'></label>")
            .append("<label>排列方式<select data-ranking-sort><option value='rank'>默认排名</option><option value='name'>模型名称</option></select></label>")
            .append("<button type='button' class='ranking-reset' data-ranking-reset>重置</button></div>")
            .append("<nav class='ranking-tabs' aria-label='选择榜单'>");
        for (var item : boards) {
            html.append("<a href='#").append(id(item)).append("' data-ranking-tab>")
                .append(esc(String.valueOf(item.content().get("title")))).append("</a>");
        }
        html.append("</nav>");
        for (var item : boards) {
            appendBoard(html, codec.tree(item.data()), codec.tree(item.sources()), id(item),
                String.valueOf(item.content().get("title")), articleUrl(item) + "#ranking-reference-notes", "");
        }
        return html.append("</section>").toString();
    }

    static String preview(PackageViews.PublishedContent item, PackageCodec codec) {
        var html = new StringBuilder("<div class='ranking-catalog'>");
        appendBoard(html, codec.tree(item.data()), codec.tree(item.sources()), id(item),
            String.valueOf(item.content().get("title")), articleUrl(item) + "#ranking-reference-notes", "");
        return html.append("</div>").toString();
    }

    static String article(JsonNode payload, String body, String attachments) {
        var html = new StringBuilder("<div class='aihyb-review-report aihyb-content-report report-leaderboard' data-report-kind='leaderboard'><div class='ranking-catalog'>");
        if (ReportBodyLayout.demonstration(payload)) {
            html.append("<p class='ranking-demo-note'>虚构演示数据，不代表真实排名。</p>");
        }
        var data = payload.path("data");
        var notes = new StringBuilder("<details class='ranking-reference-notes' id='ranking-reference-notes'><summary>引用说明</summary><div class='report-prose'>")
            .append(ReportBodyLayout.facts(new ReportBodyLayout.Fact("评分口径", data.path("benchmark").asText()),
                new ReportBodyLayout.Fact("更新时间", PricingRenderer.date(data.path("as_of").asText()))))
            .append(ReportBodyLayout.prose(body + attachments));
        notes.append("<h3>排名说明</h3><ul>");
        for (var entry : data.path("entries")) {
            notes.append("<li><strong>").append(entry.path("rank").asInt()).append(". ").append(esc(entry.path("tool_name").asText())).append("：</strong>")
                .append(esc(entry.path("reason").asText())).append(" <span class='ranking-entry-reference'>")
                .append(PricingRenderer.source(payload.path("sources"), entry.path("source_id").asText())).append("</span></li>");
        }
        notes.append("</ul></div></details>");
        appendBoard(html, data, payload.path("sources"), "report-ranking", "完整排名", null, notes.toString());
        return html.append("</div></div>").toString();
    }

    private static void appendBoard(StringBuilder html, JsonNode data, JsonNode sources, String id,
                                    String title, String notesUrl, String notes) {
        var entries = java.util.stream.StreamSupport.stream(data.path("entries").spliterator(), false)
            .sorted(Comparator.comparingInt(entry -> entry.path("rank").asInt())).toList();
        boolean editorial = "editorial_order".equals(data.path("ranking_mode").asText());
        boolean higher = data.path("higher_is_better").asBoolean();
        var max = entries.stream().filter(entry -> !entry.path("score").isNull())
            .map(entry -> decimal(entry.path("score"))).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        var unit = unit(data.path("score_unit").asText());
        html.append("<section class='ranking-board' data-ranking-board id='").append(esc(id))
            .append("' aria-labelledby='").append(esc(id)).append("-title'><header class='ranking-board-head'><h2 id='")
            .append(esc(id)).append("-title'>").append(esc(title)).append("</h2></header>")
            .append("<div class='ranking-table-toolbar'><p data-ranking-count role='status' aria-live='polite'>").append(entries.size())
            .append(" 个模型</p><span>").append(editorial ? "编辑排序" : higher ? "评分越高越好" : "评分越低越好")
            .append("</span></div><div class='ranking-table-scroll' tabindex='0' role='region' aria-label='").append(esc(title))
            .append("'><table class='ranking-data-table'><thead><tr><th scope='col'>排名</th><th scope='col'>模型</th><th scope='col'>")
            .append(editorial ? "参考评分" : "评分").append("</th></tr></thead><tbody>");
        int order = 0;
        for (var entry : entries) {
            var name = entry.path("tool_name").asText();
            var score = entry.path("score");
            html.append("<tr data-ranking-row data-order='").append(order++).append("' data-name='").append(esc(name)).append("'>")
                .append("<td><span class='ranking-position report-rank-number").append(entry.path("rank").asInt() <= 3 ? " ranking-position-top" : "")
                .append("'>").append(entry.path("rank").asInt()).append("</span></td><th scope='row'><strong>").append(esc(name)).append("</strong>");
            if (SiteSettings.safeUrl(entry.path("product_url").asText())) {
                html.append("<a class='ranking-product' data-link-kind='reference' href='").append(esc(entry.path("product_url").asText()))
                    .append("' target='_blank' rel='noopener noreferrer'>使用入口 ↗</a>");
            }
            html.append("</th><td class='ranking-score-cell'><strong class='report-raw-score'>").append(score.isNull() ? "未提供" : esc(score.asText()))
                .append("</strong>");
            if (!unit.isBlank()) { html.append("<small>").append(esc(unit)).append("</small>"); }
            if (!editorial && higher && !score.isNull() && max.signum() > 0) {
                var width = decimal(score).multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
                html.append("<span class='ranking-score-bar' aria-hidden='true'><i style='width:").append(width.toPlainString()).append("%'></i></span>");
            }
            html.append("</td></tr>");
        }
        html.append("</tbody></table></div><p class='ranking-no-results' data-ranking-empty hidden>没有找到匹配的模型，请更换关键词或重置。</p>")
            .append("<footer class='ranking-board-foot'><div class='ranking-references'><span>引用与说明</span>");
        for (var source : sources) {
            html.append("<span>").append(PricingRenderer.source(sources, source.path("source_id").asText())).append("</span>");
        }
        if (notesUrl != null) { html.append("<a href='").append(esc(notesUrl)).append("'>引用说明 ↗</a>"); }
        html.append("</div>");
        if (notesUrl == null) { html.append(notes); }
        html.append("</footer></section>");
    }

    private static String articleUrl(PackageViews.PublishedContent item) {
        var value = item.url();
        return SiteSettings.safeUrl(value) || value != null && value.startsWith("/") && !value.startsWith("//")
            ? value : "/aihyb/content/" + item.content_id();
    }
    private static BigDecimal decimal(JsonNode value) {
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException error) { return BigDecimal.ZERO; }
    }
    private static String id(PackageViews.PublishedContent item) { return "ranking-" + item.content_id(); }
    private static String unit(String value) {
        return Map.of("percent", "%", "elo", "Elo", "index", "指数", "seconds", "秒", "custom", "").getOrDefault(value, value);
    }
    private static String esc(String value) { return PackageRenderer.escape(value); }
}
