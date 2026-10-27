package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PackageCodec {
    public static final int MAX_BYTES = 128 * 1024;
    public static final Set<String> KINDS = Set.of("review", "tutorial", "leaderboard", "pricing");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;

    public PackageCodec() {
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(40).maxStringLength(MAX_BYTES).build());
        try (var input = getClass().getResourceAsStream("/contracts/content-package-v1.schema.json")) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(input, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load bundled package schema", error);
        }
    }

    public JsonNode parse(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new PackageProblem(413, "稿件包超过 128 KiB，请按内容条目拆分");
        }
        try {
            var node = mapper.readTree(raw);
            if (node == null) { throw new IllegalArgumentException("Empty input"); }
            return node;
        }
        catch (Exception error) { throw new PackageProblem(422, "$: JSON 解析失败、重复键或嵌套过深"); }
    }

    /** Internal projections are not incoming packages and can contain larger escaped HTML. */
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }

    public JsonNode validate(String raw) {
        var root = parse(raw);
        var errors = new ArrayList<String>();
        schema.validate(root).stream().limit(30).forEach(error -> errors.add(error.getMessage()));
        if (!errors.isEmpty()) { throw new PackageProblem(422, errors); }
        var markdown=root.get("content").get("body_markdown").asText();
        if(markdown.contains("/apis/api.aihyb.run/v1alpha1/media/")||markdown.contains("/apis/console.api.aihyb.run/")){
            errors.add("content.body_markdown: 媒体请使用 asset:// 标识，不要粘贴已发布或预览地址");
        }
        var urls=java.util.regex.Pattern.compile("https?://[^\\s)<>\"']+").matcher(markdown);
        while(urls.find()){
            var value=urls.group().toLowerCase(java.util.Locale.ROOT);
            if(value.contains(".myqcloud.com")||value.contains("x-amz-signature=")||value.contains("q-signature=")||value.contains("x-cos-security-token=")){
                errors.add("content.body_markdown: 请上传媒体后引用 asset://，不可写入对象存储或签名直链");
            }
        }
        var images=java.util.regex.Pattern.compile("!\\[[^]\\r\\n]*]\\(([^\\s)]+)").matcher(markdown);
        while(images.find()){
            if(!images.group(1).startsWith("asset://")&&!images.group(1).equals("/aihyb/poc-sample.svg")){
                errors.add("content.body_markdown: 内嵌图片请先上传并使用 asset:// 引用");
            }
        }
        // Parse reference-style links too; regex-only validation misses ![image][reference].
        org.commonmark.parser.Parser.builder().build().parse(markdown).accept(new org.commonmark.node.AbstractVisitor(){
            @Override public void visit(org.commonmark.node.Image image){
                var target=image.getDestination();
                if(!target.matches("asset://[a-z0-9][a-z0-9-]{0,79}")&&!target.equals("/aihyb/poc-sample.svg")){
                    errors.add("content.body_markdown: 图片必须引用本稿件附件");
                }
            }
            @Override public void visit(org.commonmark.node.Link link){
                String target;
                try{target=java.net.URLDecoder.decode(link.getDestination(),StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);}
                catch(IllegalArgumentException error){errors.add("content.body_markdown: 链接编码无效");return;}
                if(target.contains(".myqcloud.com")||target.contains("x-amz-signature=")||target.contains("q-signature=")
                    ||target.contains("/apis/api.aihyb.run/v1alpha1/media/")||target.contains("/apis/console.api.aihyb.run/")){
                    errors.add("content.body_markdown: 对象存储或媒体直链请改用 asset:// 引用");
                }
                visitChildren(link);
            }
        });
        var sources = unique(root.get("sources"), "source_id", "sources", errors);
        var assetIds=unique(root.get("assets"), "asset_id", "assets", errors);
        var references=java.util.regex.Pattern.compile("(?:asset://|/aihyb-asset/)([a-z0-9][a-z0-9-]{0,79})")
            .matcher(root.get("content").get("body_markdown").asText());
        var referenced=new HashSet<String>();
        while(references.find()){
            var id=references.group(1);referenced.add(id);
            if(!assetIds.contains(id)){errors.add("content.body_markdown: 附件引用不存在 "+id);}
        }
        for (var source : root.get("sources")) {
            if (source.path("url").isTextual()) {
                try {
                    var uri = URI.create(source.get("url").asText());
                    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                        errors.add("sources.url: 非法 HTTP 来源");
                    }
                    if(forbiddenMediaUrl(source.get("url").asText())){
                        errors.add("sources.url: 原文来源不能填写存储签名链接或后台/媒体网关地址");
                    }
                } catch (Exception error) { errors.add("sources.url: URL 无效"); }
            } else if (source.path("material_type").asText().equals("webpage")) {
                errors.add("sources.url: 网页来源必须有原文链接");
            }
        }
        for (var asset : root.get("assets")) {
            if(!MediaService.TYPES.contains(asset.get("content_type").asText())){errors.add("assets.content_type: 不支持此媒体类型");}
            if(asset.get("visibility").asText().equals("private")&&referenced.contains(asset.get("asset_id").asText())){
                errors.add("assets: 私有附件仅供审核，不得嵌入公开正文");
            }
            var key = asset.get("object_key").asText();
            if (key.startsWith("/") || key.contains("..") || key.contains("\\") || key.contains(":")) {
                errors.add("assets.object_key: 只接受稳定的相对对象标识");
            }
        }
        var data = root.get("data");
        switch (root.get("kind").asText()) {
            case "review" -> checkSources(data.get("conclusions"), sources, "data.conclusions", errors);
            case "tutorial" -> {
                if (data.get("verification_status").asText().equals("reproduced")
                    && data.get("last_verified_at").isNull()) {
                    errors.add("data.last_verified_at: 已复现教程需要核验时间");
                }
            }
            case "leaderboard" -> {
                var entries = data.get("entries");
                if ("overall".equals(data.path("task").asText()) && !"editorial_order".equals(data.path("ranking_mode").asText())) errors.add("综合榜须使用人工排序");
                var modelKeys=new HashSet<String>();
                for(var entry:entries) {
                    if(entry.has("product_url")&&!SiteSettings.safeUrl(entry.path("product_url").asText())) errors.add("产品入口须为有效 HTTP/HTTPS 地址");
                    if("overall".equals(data.path("task").asText())) {
                        if(entry.path("tool_name").asText().isBlank()||entry.path("model_version").asText().isBlank()) errors.add("综合榜须明确模型名称与版本");
                        if(!modelKeys.add(entry.path("tool_name").asText().strip()+"\u0000"+entry.path("model_version").asText().strip())) errors.add("综合榜中的模型版本重复");
                    }
                }
                unique(entries, "entry_id", "data.entries", errors);
                checkSources(entries, sources, "data.entries", errors);
                var ranks = new HashSet<Integer>();
                BigDecimal previous = null;
                int previousRank = 0;
                for (var entry : entries) {
                    int rank = entry.get("rank").asInt();
                    if ((!ranks.add(rank) && !data.get("allow_ties").asBoolean()) || rank < previousRank) {
                        errors.add("data.entries.rank: 名次须有序，重复名次需明确允许并列");
                    }
                    previousRank = rank;
                    var score = entry.get("score");
                    if (!score.isNull() && data.get("score_unit").asText().equals("percent")
                        && new BigDecimal(score.asText()).compareTo(new BigDecimal("100")) > 0) {
                        errors.add("data.entries.score: 百分比不得超过 100");
                    }
                    if (data.get("ranking_mode").asText().equals("score")) {
                        if (score.isNull()) { errors.add("data.entries.score: 按分数排序不能使用未知分数"); }
                        else {
                            var current = new BigDecimal(score.asText());
                            if (previous != null && (data.get("higher_is_better").asBoolean()
                                ? previous.compareTo(current) < 0 : previous.compareTo(current) > 0)) {
                                errors.add("data.entries.score: 顺序与分数方向不符");
                            }
                            previous = current;
                        }
                    }
                }
            }
            case "pricing" -> {
                var planIds = unique(data.get("plans"), "plan_id", "data.plans", errors);
                checkSources(data.get("plans"), sources, "data.plans", errors);
                if (data.has("task_usage")) {
                    checkSources(data.get("task_usage"), sources, "data.task_usage", errors);
                    for (var usage : data.get("task_usage")) {
                        if (!planIds.contains(usage.get("plan_id").asText())) {
                            errors.add("data.task_usage.plan_id: 对应方案不存在");
                        }
                    }
                }
                for (var plan : data.get("plans")) {
                    var unit = plan.get("price_unit").asText();
                    var cycle = plan.get("billing_cycle").asText();
                    if (plan.get("offer_kind").asText().equals("subscription")) {
                        if (!(unit.equals("month") && cycle.equals("monthly"))
                            && !(unit.equals("year") && cycle.equals("annual"))) {
                            errors.add("data.plans: 会员计费周期与价格单位不符");
                        }
                    } else if (!cycle.equals("usage") || unit.equals("month") || unit.equals("year")) {
                        errors.add("data.plans: API 报价必须按用量单位记录");
                    }
                }
            }
            default -> throw new PackageProblem(422, "kind: 不支持的模块");
        }
        if (!errors.isEmpty()) { throw new PackageProblem(422, errors); }
        return root;
    }

    private Set<String> unique(JsonNode array, String field, String path, List<String> errors) {
        var values = new HashSet<String>();
        for (var value : array) {
            if (!values.add(value.get(field).asText())) { errors.add(path + "." + field + ": 标识重复"); }
        }
        return values;
    }
    private static boolean forbiddenMediaUrl(String value){
        try{
            var target=java.net.URLDecoder.decode(value,StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
            return target.contains(".myqcloud.com")||target.contains("x-amz-signature=")||target.contains("q-signature=")
                ||target.contains("x-cos-security-token=")||target.contains("/apis/api.aihyb.run/v1alpha1/media/")
                ||target.contains("/apis/console.api.aihyb.run/");
        }catch(IllegalArgumentException failure){return true;}
    }

    private void checkSources(JsonNode items, Set<String> sources, String path, List<String> errors) {
        for (var item : items) {
            if (!sources.contains(item.get("source_id").asText())) {
                errors.add(path + ".source_id: 来源引用不存在");
            }
        }
    }

    public String canonical(JsonNode node) {
        try { return mapper.writeValueAsString(sorted(node)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    // Return ordinary Java values at the Jackson 3 HTTP boundary in Halo 2.26.
    public Map<String, Object> toMap(JsonNode node) {
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    public List<Map<String, Object>> toList(JsonNode node) {
        return mapper.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    public String serialize(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Cannot serialize package response", error); }
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode();
            var fields = new ArrayList<String>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            fields.forEach(key -> result.set(key, sorted(node.get(key))));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
