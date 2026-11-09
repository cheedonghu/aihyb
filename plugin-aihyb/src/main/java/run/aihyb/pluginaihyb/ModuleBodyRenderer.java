package run.aihyb.pluginaihyb;

import static run.aihyb.pluginaihyb.ReportBodyLayout.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import org.jsoup.Jsoup;

/** Kind-specific detail, using the same report layout and original published values. */
final class ModuleBodyRenderer {
    private ModuleBodyRenderer() {}
    static String render(JsonNode payload, String body, String attachments, String revision) {
        return switch (payload.path("kind").asText()) {
            case "leaderboard" -> LeaderboardRenderer.article(payload, body, attachments);
            case "pricing" -> pricing(payload, body, attachments, revision);
            case "tutorial" -> tutorial(payload, body, attachments, revision);
            default -> throw new IllegalArgumentException("Unsupported article kind");
        };
    }
    private static String pricing(JsonNode payload, String body, String attachments, String revision) {
        var data = payload.path("data"); var sources = payload.path("sources"); var plans = data.path("plans");
        var currencies = new LinkedHashSet<String>();
        for (var plan : plans) { currencies.add(plan.path("currency").asText()); }
        var records = List.of(new PricingRenderer.Record(data, sources, "#report-plans", "方案与额度"));
        var html = new StringBuilder(open(payload, "方案速览", plans.size() + " 个方案，价格与权益一起看。", "价格方案记录",
            "先核对权益和限制，再比较实际花费", "report-plans", "查看全部方案"));
        html.append(facts(new Fact("工具 / 产品", data.path("tool_name").asText()), new Fact("任务场景", task(data)),
            new Fact("已发布方案", plans.size() + " 个"), new Fact("原始币种", String.join(" / ", currencies))))
            .append(jumps(new Jump("report-plans", "01 套餐与权益"), new Jump("report-usage", "02 任务消耗"),
                new Jump("report-conditions", "03 补充说明"), new Jump("report-sources", "04 原始来源")))
            .append(heading("01", "report-plans", "套餐与权益", "全部方案直接展开，会员订阅与 API 保留各自计价口径。"))
            .append("<div class='report-plan-grid'>");
        for (var plan : plans) {
            html.append("<section class='report-plan-card' data-plan-id='").append(esc(plan.path("plan_id").asText()))
                .append("'><div class='report-plan-top'><span>").append(plan.path("offer_kind").asText().equals("api") ? "API 调用" : "会员订阅 / 免费方案")
                .append("</span><span class='report-plan-state'>").append(switch (plan.path("status").asText()) {
                    case "available" -> "在售"; case "unavailable" -> "已停售"; default -> "状态待核验";
                }).append("</span><h3>").append(esc(plan.path("name").asText())).append("</h3></div><div class='report-plan-price'>")
                .append(PricingRenderer.price(plan)).append("</div><p class='report-plan-cycle'>扣款周期：")
                .append(esc(PricingRenderer.label(plan.path("billing_cycle").asText()))).append(" · ")
                .append(esc(PricingRenderer.label(plan.path("region").asText()))).append(" / ")
                .append(esc(PricingRenderer.label(plan.path("channel").asText()))).append("</p>")
                .append("<div class='report-plan-quota'><span>方案额度</span>").append(quota(plan.path("usage_quota")))
                .append("<span>免费额度</span>").append(quota(plan.path("free_quota"))).append("</div>")
                .append("<p class='report-plan-reset'>重置方式：").append(esc(plan.path("reset_rule").asText("方案额度重置待补充")))
                .append("</p><div class='report-plan-details'><strong>包含权益</strong>").append(lines(plan.path("benefits"), "权益待补充"))
                .append("<strong>使用限制</strong>").append(lines(plan.path("limits"), "限制待补充"))
                .append("<p class='report-plan-eligibility'>适用条件：").append(esc(plan.path("eligibility").asText())).append("</p><p>")
                .append(esc(plan.path("tax_note").asText("税费说明待补充"))).append("</p></div><div class='report-plan-footer'>")
                .append(sourceRef(sources, plan.path("source_id").asText())).append("<small>核验 ")
                .append(esc(PricingRenderer.date(plan.path("verified_at").asText()))).append("</small></div></section>");
        }
        html.append("</div><div class='report-comparison-label'>完整方案对照</div>").append(PricingRenderer.render(records, true)).append("</section>")
            .append(heading("02", "report-usage", "同一任务的额度消耗", "保留额度池、重置、耗时和样本条件；没有实测时明确标注待补充。"));
        var usage = Jsoup.parseBodyFragment(PricingRenderer.taskUsage(records));
        var usageTitle = usage.selectFirst(".task-usage-section > .catalog-heading");
        if (usageTitle != null) { usageTitle.remove(); }
        return html.append(usage.body().html()).append("</section>").append(heading("03", "report-conditions", "购买条件与补充说明", "地区、渠道、税费和限制以当前方案记录及来源为准。"))
            .append("<div class='report-prose'>").append(prose(body + attachments)).append("</div></section>")
            .append(sources(sources, "04")).append(finish(revision)).toString();
    }
    private static String tutorial(JsonNode payload, String body, String attachments, String revision) {
        var data = payload.path("data"); var status = verification(data.path("verification_status").asText());
        var html = new StringBuilder(open(payload, "上手指南", "先准备，再跟着步骤做。", status,
            "核对工具与版本，保留每一步的操作条件", "report-steps", "开始阅读操作步骤"));
        html.append(facts(new Fact("任务场景", task(data)), new Fact("工具 / 产品", data.path("tool_name").asText()),
            new Fact("适用版本", data.path("tool_version").asText()), new Fact("复现状态", status)))
            .append(jumps(new Jump("report-prepare", "01 开始之前"), new Jump("report-steps", "02 步骤与提示词"), new Jump("report-sources", "03 原始来源")))
            .append(heading("01", "report-prepare", "开始之前", "先准备好原文列出的材料，并核对适用版本。"))
            .append("<ul class='report-preparation'>");
        for (var prerequisite : data.path("prerequisites")) { html.append("<li>").append(esc(prerequisite.asText())).append("</li>"); }
        if (data.path("prerequisites").isEmpty()) { html.append("<li>原文未列出前置条件，请先核对工具与版本。</li>"); }
        html.append("</ul><aside class='report-verification-note'><strong>").append(esc(status)).append("</strong><p>");
        var state = data.path("verification_status").asText();
        html.append(switch (state) {
            case "reproduced" -> "稿件已记录复现状态；其他版本与环境仍需重新确认。";
            case "source_only" -> "当前仅整理来源材料，尚未记录本站复现结果。";
            default -> "当前稿件尚未记录复现结果，操作前请先核对版本与界面。";
        });
        if (!data.path("last_verified_at").isNull() && !data.path("last_verified_at").isMissingNode()) {
            html.append(" 最近核验：").append(esc(PricingRenderer.date(data.path("last_verified_at").asText())));
        }
        html.append("</p></aside></section>").append(heading("02", "report-steps", "操作步骤与提示词", "按原文顺序阅读，代码与提示词支持选中复制。"));
        var prose = Jsoup.parseBodyFragment(prose(body + attachments));
        prose.select("ol").addClass("report-step-list");
        for (var heading : prose.select("h3,h4")) {
            if (heading.text().contains("常见问题") || heading.text().contains("注意事项")) {
                heading.addClass("report-tip-title");
                var next = heading.nextElementSibling();
                if (next != null && next.is("p,ul")) { next.addClass("report-tutorial-tip"); }
            }
        }
        return html.append("<div class='report-prose report-tutorial-prose'>").append(prose.body().html()).append("</div></section>")
            .append(sources(payload.path("sources"), "03")).append(finish(revision)).toString();
    }
    private static String quota(JsonNode quota) {
        if (quota.isNull() || quota.isMissingNode()) { return "<div><strong>待补充</strong></div>"; }
        return "<div><strong>" + ((quota.path("amount").isNull() || quota.path("amount").isMissingNode()) ? "未知" : esc(quota.path("amount").asText())) + " "
            + esc(PricingRenderer.label(quota.path("unit").asText())) + "</strong><small>重置："
            + esc(PricingRenderer.label(quota.path("reset_period").asText())) + "</small><small>" + esc(quota.path("condition").asText()) + "</small></div>";
    }
    private static String lines(JsonNode values, String empty) {
        if (!values.isArray() || values.isEmpty()) { return "<p>" + empty + "</p>"; }
        var html = new StringBuilder("<ul>");
        for (var value : values) { html.append("<li>").append(esc(value.asText())).append("</li>"); }
        return html.append("</ul>").toString();
    }
    private static String verification(String value) {
        return switch (value) { case "reproduced" -> "已复现"; case "source_only" -> "仅来源材料"; default -> "待复现"; };
    }
}
