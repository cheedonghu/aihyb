package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Published offers and task observations; never combines currencies or quota pools. */
public final class PricingRenderer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public record Record(JsonNode data, JsonNode sources, String href, String title) {}
    private PricingRenderer() {}

    public static List<Record> records(List<PackageViews.PublishedContent> contents) {
        return contents.stream().filter(item -> "pricing".equals(item.kind()) && !item.articleOnly()).map(item ->
            new Record(MAPPER.valueToTree(item.data()), MAPPER.valueToTree(item.sources()),
                "/aihyb/content/" + item.content_id(), String.valueOf(item.content().get("title")))).toList();
    }

    public static String render(List<Record> records, boolean compact) {
        var groups = new LinkedHashMap<String, List<Map.Entry<Record, JsonNode>>>();
        for (var record : records) {
            for (var plan : record.data().path("plans")) {
                var key = text(plan, "offer_kind") + "/" + text(plan, "currency");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(Map.entry(record, plan));
            }
        }
        var html = new StringBuilder("<section class='pricing-catalog' aria-label='全部价格方案'>");
        if (groups.isEmpty()) {
            html.append("<div class='empty-state'><h3>暂时没有已发布的价格方案</h3><p>核验后的方案将在这里完整列出。</p></div>");
        }
        for (var group : groups.entrySet()) {
            var api = group.getKey().startsWith("api/");
            var currency = group.getKey().substring(group.getKey().indexOf('/') + 1);
            html.append("<div class='catalog-heading'><h3>").append(api ? "API 调用价格" : "会员订阅与免费方案")
                .append(" <small>").append(esc(currency)).append("</small></h3><span>")
                .append(group.getValue().size()).append(" 个方案 · 默认全部展开</span></div>")
                .append("<div class='pricing-scroll' role='region' aria-label='价格、额度与使用限制对照表' tabindex='0'><table class='pricing-table'><thead><tr>")
                .append("<th scope='col'>产品 / 方案</th><th scope='col'>价格 / 计费</th><th scope='col'>额度 / 重置方式</th>")
                .append("<th scope='col'>地区 / 渠道</th><th scope='col'>权益 / 使用限制</th><th scope='col'>来源 / 核验</th></tr></thead><tbody>");
            for (var entry : group.getValue()) {
                var record = entry.getKey(); var plan = entry.getValue();
                html.append("<tr><th scope='row'><a href='").append(esc(record.href())).append("'><strong>")
                    .append(esc(text(record.data(), "tool_name"))).append("</strong></a><span class='plan-name'>")
                    .append(esc(text(plan, "name"))).append("</span></th><td><strong class='catalog-price'>")
                    .append(price(plan)).append("</strong><small>").append(esc(label(text(plan, "billing_cycle"))))
                    .append("</small><small>").append(esc(plan.path("tax_note").asText("税费说明待补充"))).append("</small></td><td>");
                if (plan.has("usage_quota")) {
                    html.append("<span class='table-label'>方案额度</span>").append(quota(plan.get("usage_quota")));
                } else {
                    html.append("<span class='table-label'>方案额度</span><span>待补充</span>");
                }
                html.append("<span class='table-label'>免费额度</span>").append(quota(plan.path("free_quota")))
                    .append("<span class='table-label'>重置方式</span><span>")
                    .append(esc(plan.path("reset_rule").asText("方案额度重置待补充"))).append("</span></td><td>")
                    .append(esc(label(text(plan, "region")))).append("<small>").append(esc(label(text(plan, "channel"))))
                    .append("</small><small>").append(esc(text(plan, "eligibility"))).append("</small></td><td>");
                if (plan.path("benefits").isArray() && !plan.path("benefits").isEmpty()) {
                    html.append("<span class='table-label'>包含权益</span>");
                    for (var benefit : plan.path("benefits")) { html.append("<span class='fact-line'>").append(esc(benefit.asText())).append("</span>"); }
                }
                html.append("<span class='table-label'>限制</span>");
                if (plan.path("limits").isEmpty()) { html.append("<span>限制待补充</span>"); }
                for (var limit : plan.path("limits")) { html.append("<span class='fact-line'>").append(esc(limit.asText())).append("</span>"); }
                html.append("</td><td>").append(source(record.sources(), text(plan, "source_id")))
                    .append("<small>核验 ").append(esc(date(text(plan, "verified_at")))).append("</small><small>")
                    .append(esc(status(plan.path("status").asText("unknown")))).append("</small></td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        html.append("<p class='catalog-footnote'>按币种与计费方式分组；年付显示全年实际扣款，API 显示原始计费单位。未知额度不等于无限额度。</p></section>");
        if (!compact) { html.append(taskUsage(records)); }
        return html.toString();
    }

    public static String taskUsage(List<Record> records) {
        var html = new StringBuilder("<section id='task-usage' class='task-usage-section'><div class='catalog-heading'><div><h2>同一任务，实际消耗多少额度？</h2><p>一起看消耗百分比、额度池、重置规则、耗时与返工次数。</p></div></div>");
        var groups = new LinkedHashMap<String, List<Map.Entry<Record, JsonNode>>>();
        for (var record : records) {
            for (var observation : record.data().path("task_usage")) {
                // An identifier alone is insufficient: preserve the exact task conditions.
                var key = text(observation, "task_id") + "\n" + text(observation, "conditions");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(Map.entry(record, observation));
            }
        }
        if (groups.isEmpty()) {
            html.append("<div class='panel usage-pending'><h3>同任务使用记录待补充</h3><p>当前已发布内容尚未提供同任务额度消耗数据。</p><div class='pending-fields'><span>额度消耗：待补充</span><span>额度池与重置：待补充</span><span>耗时与返工次数：待补充</span><span>样本与测试条件：待补充</span></div></div>");
        }
        for (var group : groups.values()) {
            var first = group.getFirst().getValue();
            html.append("<div class='task-condition'><h3>").append(esc(text(first, "task_name"))).append("</h3><p>共同条件：")
                .append(esc(text(first, "conditions"))).append("</p></div>")
                .append("<div class='pricing-scroll' role='region' aria-label='同任务额度消耗对照表' tabindex='0'><table class='usage-table'><thead><tr>")
                .append("<th scope='col'>产品 / 方案 / 版本</th><th scope='col'>额度消耗</th><th scope='col'>额度池 / 重置</th><th scope='col'>耗时 / 返工</th><th scope='col'>完成结果 / 样本</th><th scope='col'>来源 / 测试时间</th></tr></thead><tbody>");
            for (var entry : group) {
                var record = entry.getKey(); var observation = entry.getValue();
                var planName = "对应方案待补充";
                for (var plan : record.data().path("plans")) {
                    if (text(plan, "plan_id").equals(text(observation, "plan_id"))) { planName = text(plan, "name"); break; }
                }
                var value = observation.path("consumed_percent");
                var percent = value.isNumber() ? value.decimalValue() : null;
                var valid = percent != null && percent.signum() >= 0 && percent.compareTo(BigDecimal.valueOf(100)) <= 0;
                html.append("<tr><th scope='row'>").append(esc(text(record.data(), "tool_name")))
                    .append("<span class='plan-name'>").append(esc(planName)).append("</span><small>")
                    .append(esc(text(observation, "model_version"))).append("</small></th><td>");
                if (valid) {
                    var display = percent.stripTrailingZeros().toPlainString();
                    html.append("<strong class='usage-percent'>").append(display).append("%</strong>")
                        .append("<div class='usage-track' aria-hidden='true'><span style='width:").append(display).append("%'></span></div>");
                } else { html.append("待补充"); }
                html.append("</td><td>").append(esc(text(observation, "quota_basis"))).append("<small>")
                    .append(esc(text(observation, "quota_reset"))).append("</small></td><td>")
                    .append(duration(observation.path("duration_seconds").asLong())).append("<small>返工 ")
                    .append(observation.path("revision_count").asInt()).append(" 次</small></td><td>")
                    .append(esc(text(observation, "outcome"))).append("<small>样本 ").append(observation.path("sample_count").asInt())
                    .append(" 次</small></td><td>").append(source(record.sources(), text(observation, "source_id")))
                    .append("<small>").append(esc(date(text(observation, "measured_at")))).append("</small></td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        return html.append("<p class='catalog-footnote'>百分比以各方案记录的额度池为分母，反映本次任务的体感消耗；额度总量与重置周期不同，不能只凭百分比推算谁更划算。</p></section>").toString();
    }

    private static String quota(JsonNode quota) {
        if (quota.isMissingNode() || quota.isNull()) { return "<span>待补充</span>"; }
        return "<span>" + (quota.path("amount").isNull() || quota.path("amount").isMissingNode() ? "未知" : esc(text(quota, "amount")))
            + " " + esc(label(text(quota, "unit"))) + "</span><small>重置：" + esc(label(text(quota, "reset_period")))
            + "</small><small>" + esc(text(quota, "condition")) + "</small>";
    }
    static String source(JsonNode sources, String id) {
        for (var source : sources) {
            if (!id.equals(source.path("source_id").asText())) { continue; }
            var author = source.path("author").isTextual() ? source.path("author").asText() : "来源记录";
            var url = source.path("url").asText("");
            java.net.URI uri;
            try { uri = java.net.URI.create(url); }
            catch (IllegalArgumentException error) { return esc(author); }
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null) {
                return "<a target='_blank' rel='noopener noreferrer' data-link-kind='reference' href='" + esc(url) + "'>" + esc(author) + " ↗</a>";
            }
            return esc(author);
        }
        return "来源待补充";
    }
    static String price(JsonNode plan) {
        var amount = plan.path("amount");
        if (amount.isNull() || amount.isMissingNode()) { return "价格待核验"; }
        return esc(amount.asText()) + " " + esc(text(plan, "currency")) + " / " + esc(label(text(plan, "price_unit")));
    }
    static String duration(long seconds) { return seconds / 60 + " 分 " + seconds % 60 + " 秒"; }
    static String text(JsonNode node, String key) { return node.path(key).isNull() ? "待补充" : node.path(key).asText("待补充"); }
    static String date(String value) { return value.length() >= 10 ? value.substring(0, 10) : value; }
    private static String status(String status) { return switch (status) { case "available" -> "在售"; case "unavailable" -> "已停售"; default -> "状态待核验"; }; }
    static String label(String value) {
        return switch (value) {
            case "month", "monthly" -> "月"; case "year", "annual" -> "年"; case "day", "daily" -> "每天";
            case "percent" -> "% 额度"; case "unknown" -> "未知"; case "usage" -> "按量计费"; case "tasks" -> "次任务"; case "request" -> "次请求";
            case "million_input_tokens" -> "百万输入 tokens"; case "million_output_tokens" -> "百万输出 tokens";
            case "image" -> "张图片"; case "CN" -> "中国大陆"; case "US" -> "美国";
            case "official-web" -> "官方网页"; default -> value;
        };
    }
    private static String esc(String value) { return PackageRenderer.escape(value); }
}
