package run.aihyb.pluginaihyb;

import static run.aihyb.pluginaihyb.ReportBodyLayout.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Review-specific evidence in the shared four-module report layout. */
final class ReviewBodyRenderer {
    private ReviewBodyRenderer() {}
    private static final Map<String, String> ORIGINS = Map.of(
        "external_summary", "来源材料整理", "original_test", "原创测试", "official_explanation", "官方说明整理");
    static String render(JsonNode payload, String body, String attachments, String revision) {
        var data = payload.path("data"); var conclusions = data.path("conclusions"); var sources = payload.path("sources");
        var origin = ORIGINS.getOrDefault(data.path("content_origin").asText(), "测评记录");
        var html = new StringBuilder(open(payload, "结论速览", conclusions.path(0).path("text").asText("结论待补充"), origin,
            "先读结论，再核对过程与依据", "report-evidence", "查看 " + conclusions.size() + " 条结论与依据"));
        html.append(facts(new Fact("任务场景", task(data)), new Fact("工具 / 产品", data.path("tool_name").asText()),
            new Fact("记录版本", data.path("tool_version").asText()), new Fact("内容性质", demonstration(payload) ? "虚构版式演示" : origin)))
            .append(jumps(new Jump("report-process", "01 过程与样例"), new Jump("report-evidence", "02 结论与依据"), new Jump("report-sources", "03 原始来源")))
            .append(heading("01", "report-process", "过程与结果样例", "看清任务怎么做，再判断结果是否适合自己。"))
            .append("<div class='report-prose'>").append(prose(body + attachments)).append("</div></section>")
            .append(heading("02", "report-evidence", "结论与依据", "每条结论保留对应材料，方便逐项核对。"))
            .append("<div class='report-evidence-grid'>");
        var number = 0;
        for (var conclusion : conclusions) {
            number++;
            html.append("<div class='report-evidence-card'><div class='report-evidence-title'><span aria-hidden='true'>")
                .append(String.format("%02d", number)).append("</span><h3>").append(esc(conclusion.path("text").asText()))
                .append("</h3></div><div class='report-evidence-material'><span>对应依据</span><p>")
                .append(esc(conclusion.path("evidence").asText())).append("</p></div>")
                .append(sourceRef(sources, conclusion.path("source_id").asText())).append("</div>");
        }
        return html.append("</div></section>").append(sources(sources, "03")).append(finish(revision)).toString();
    }
}
