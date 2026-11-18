package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Reference measurements accompany the source ranking without changing its scale or order. */
final class OpenModelHardware {
    static final String TASK = "open-models";
    private static final int INPUT = 6144;
    private static final int OUTPUT = 2048;
    private static final Map<String, String> EVIDENCE = Map.of(
        "vendor", "厂商实测", "community", "社区实测", "estimate", "估算");

    private OpenModelHardware() {}

    static void controls(StringBuilder html, String id) {
        html.append("<div class='ranking-hardware-filter'><label data-hardware-control hidden>单卡显存预算 ")
            .append("<select data-hardware-budget aria-controls='").append(id).append("-chart' aria-describedby='")
            .append(id).append("-criteria'><option value=''>全部模型</option>");
        for (var gb : List.of(8, 12, 16, 24)) {
            html.append("<option value='").append(gb * 1000).append("'>").append(gb).append(" GB 以内</option>");
        }
        html.append("</select></label><p id='").append(id).append("-criteria'>筛选依据为单卡实测显存占用，输入 ")
            .append(INPUT).append("、输出 ").append(OUTPUT).append(" tokens，batch 1。按 1 GB = 1000 MB 比较，上限含等值。")
            .append("硬件记录仅供参考，具体消费卡的兼容性和可用显存需另行核实。</p>")
            .append("<noscript>当前显示全部模型，启用 JavaScript 可按显存筛选。</noscript></div>");
    }

    static void append(StringBuilder html, JsonNode entry, Map<String, Integer> references) {
        html.append("<div class='ranking-hardware'>");
        if (entry.has("license")) {
            html.append("<span>权重许可 · ").append(text(entry, "license")).append("</span> ");
        }
        if (entry.has("weights_bytes")) {
            var bytes = entry.path("weights_bytes").decimalValue();
            var gb = bytes.movePointLeft(9).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
            html.append("<span>GGUF Q4_K_M 权重文件 · ");
            if (gb.signum() > 0) html.append("约 ").append(gb.toPlainString()).append(" GB · ");
            html.append(bytes.toBigIntegerExact()).append(" 字节</span>");
        }
        var records = entry.path("hardware");
        if (records.isEmpty()) {
            html.append("<p>硬件参考配置：未收录</p></div>");
            return;
        }
        var rows = new StringBuilder();
        BigDecimal minimum = null;
        for (var record : records) {
            var mb = record.path("memory_mb").decimalValue();
            rows.append("<li data-hardware-record");
            if (comparable(record)) {
                rows.append(" data-measured-mb='").append(mb.toPlainString()).append("'");
                minimum = minimum == null ? mb : minimum.min(mb);
            }
            rows.append("><strong>").append(EVIDENCE.get(record.path("evidence").asText())).append(" · ")
                .append(mb.stripTrailingZeros().toPlainString()).append(" MB</strong> · ")
                .append(text(record, "device")).append(" × ").append(text(record, "gpu_count"))
                .append(" · ").append(text(record, "quantization")).append(" · ").append(text(record, "runtime"))
                .append(" · 输入 ").append(text(record, "input_tokens")).append(" / 输出 ").append(text(record, "output_tokens"))
                .append(" tokens · batch ").append(text(record, "batch_size"))
                .append(" · 出处 [").append(references.get(record.path("source_id").asText())).append("]</li>");
        }
        html.append("<details><summary>参考配置 · 同口径单卡实测")
            .append(minimum == null ? "未收录" : "最低 " + minimum.stripTrailingZeros().toPlainString() + " MB")
            .append("</summary><ul>").append(rows).append("</ul></details></div>");
    }

    private static boolean comparable(JsonNode record) {
        var evidence = record.path("evidence").asText();
        return ("vendor".equals(evidence) || "community".equals(evidence))
            && record.path("gpu_count").asInt() == 1 && record.path("batch_size").asInt() == 1
            && record.path("input_tokens").asInt() == INPUT && record.path("output_tokens").asInt() == OUTPUT;
    }

    private static String text(JsonNode node, String key) {
        return PackageRenderer.escape(node.path(key).asText());
    }
}
