package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.jsoup.Jsoup;

/** Shared presentation for the four published article kinds. */
final class ReportBodyLayout {
    private ReportBodyLayout() {}
    record Fact(String label, String value) {}
    record Jump(String target, String label) {}
    private static final Map<String, String> TASKS = Map.ofEntries(
        Map.entry("overall", "综合"), Map.entry("qa", "知识问答"), Map.entry("office", "办公提效"), Map.entry("presentation", "PPT 与演示"),
        Map.entry("spreadsheet", "表格处理"), Map.entry("writing", "写作表达"),
        Map.entry("programming", "编程开发"), Map.entry("image", "画图设计"),
        Map.entry("video", "视频创作"), Map.entry("audio", "语音音频"), Map.entry("research", "学习研究"));

    static String open(JsonNode payload, String kicker, String statement, String status,
                       String guide, String target, String action) {
        var kind = ContentSection.of(payload);
        if (demonstration(payload)) { status = "虚构演示 · " + (kind.equals("review") ? "非真实测评" : status); }
        return "<div class='aihyb-review-report aihyb-content-report report-" + esc(kind) + "' data-report-kind='" + esc(kind)
            + "'><section class='report-verdict' aria-labelledby='report-verdict-title'><div class='report-verdict-top'><h2 id='report-verdict-title' class='report-kicker'>"
            + esc(kicker) + "</h2><span class='report-status'>" + esc(status)
            + "</span></div><p class='report-verdict-statement'>" + esc(statement) + "</p><p>"
            + esc(payload.path("content").path("summary").asText())
            + "</p><div class='report-verdict-bottom'><span>" + esc(guide) + "</span><a href='#" + esc(target) + "'>"
            + esc(action) + " <span aria-hidden='true'>↓</span></a></div></section>";
    }
    static String facts(Fact... facts) {
        var html = new StringBuilder("<dl class='report-facts'>");
        for (var fact : facts) {
            html.append("<div><dt>").append(esc(fact.label())).append("</dt><dd>").append(esc(fact.value())).append("</dd></div>");
        }
        return html.append("</dl>").toString();
    }
    static String jumps(Jump... jumps) {
        var html = new StringBuilder("<nav class='report-jumps' aria-label='正文章节导航'>");
        for (var jump : jumps) { html.append("<a href='#").append(esc(jump.target())).append("'>").append(esc(jump.label())).append("</a>"); }
        return html.append("</nav>").toString();
    }
    static String heading(String number, String id, String title, String description) {
        return "<section class='report-section' aria-labelledby='" + esc(id)
            + "'><div class='report-section-heading'><span aria-hidden='true'>" + esc(number) + "</span><div><h2 id='"
            + esc(id) + "'>" + esc(title) + "</h2><p>" + esc(description) + "</p></div></div>";
    }
    static String prose(String body) {
        var document = Jsoup.parseBodyFragment(body);
        for (var heading : document.select("h1,h2")) { heading.tagName("h3"); }
        return document.body().html();
    }
    static String sources(JsonNode sources, String number) {
        var html = new StringBuilder(heading(number, "report-sources", "原始来源", "保留出处与材料取得时间，阅读时可回到原始语境。"));
        for (var source : sources) {
            html.append("<div class='report-source-card' id='report-source-").append(esc(source.path("source_id").asText()))
                .append("'><span class='report-source-icon' aria-hidden='true'>↗</span><div><div class='report-source-title'><strong>")
                .append(esc(author(source))).append("</strong><span>")
                .append(esc(Map.of("webpage", "网页", "file", "文件", "dataset", "数据集", "self_test", "测试记录")
                    .getOrDefault(source.path("material_type").asText(), "来源材料")))
                .append("</span></div><p>材料取得于 ").append(esc(PricingRenderer.date(source.path("retrieved_at").asText()))).append("</p>");
            var url = source.path("url").asText("");
            if (publicUrl(url)) {
                html.append("<a href='").append(esc(url)).append("' target='_blank' rel='noopener noreferrer' data-link-kind='reference'>查看完整原文 <span aria-hidden='true'>↗</span></a>");
            } else { html.append("<p class='report-file-note'>作者提供的文件或本站材料</p>"); }
            html.append("</div></div>");
        }
        return html.append("</section>").toString();
    }
    static String sourceRef(JsonNode sources, String id) {
        for (var source : sources) {
            if (id.equals(source.path("source_id").asText())) {
                return "<a class='report-source-ref' href='#report-source-" + esc(id) + "'>" + esc(author(source)) + " <span aria-hidden='true'>↗</span></a>";
            }
        }
        return "<span class='report-source-ref'>来源待补充</span>";
    }
    static String finish(String revision) {
        return "<details class='report-version'><summary>查看内容版本</summary><p>" + esc(revision) + "</p></details></div>";
    }
    static String task(JsonNode data) { return TASKS.getOrDefault(data.path("task").asText(), data.path("task").asText()); }
    static boolean demonstration(JsonNode payload) {
        for (var tag : payload.path("content").path("tags")) {
            if ("虚构数据".equals(tag.asText()) || "虚构演示".equals(tag.asText())) { return true; }
        }
        return false;
    }
    private static String author(JsonNode source) { return source.path("author").isTextual() ? source.path("author").asText() : "作者提供的来源材料"; }
    private static boolean publicUrl(String url) {
        try {
            var uri = java.net.URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException error) { return false; }
    }
    static String esc(String value) { return PackageRenderer.escape(value); }
}
