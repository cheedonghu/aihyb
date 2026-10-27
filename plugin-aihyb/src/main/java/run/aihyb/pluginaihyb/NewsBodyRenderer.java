package run.aihyb.pluginaihyb;

import static run.aihyb.pluginaihyb.ReportBodyLayout.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Sourced news keeps the shared reading layout without presenting a capability test. */
final class NewsBodyRenderer {
    private NewsBodyRenderer() {}

    static String render(JsonNode payload, String body, String attachments, String revision) {
        var sources = payload.path("sources");
        return open(payload, "资讯速览", "发生了什么，为什么值得关注。", "资讯解读",
                "来源观点与本站解读，请结合原文阅读", "report-sources", "查看原始来源")
            + facts(new Fact("收录日期", payload.path("content").path("news_date").asText()),
                new Fact("内容栏目", "最新资讯"), new Fact("主要来源", sources.path(0).path("author").asText("来源材料")))
            + jumps(new Jump("report-process", "01 资讯与解读"), new Jump("report-sources", "02 原始来源"))
            + heading("01", "report-process", "资讯与解读", "保留背景、原始材料与不同观点。")
            + "<div class='report-prose'>" + prose(body + attachments) + "</div></section>"
            + sources(sources, "02") + finish(revision);
    }
}
