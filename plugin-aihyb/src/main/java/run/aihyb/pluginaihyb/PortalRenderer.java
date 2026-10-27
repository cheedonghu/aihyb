package run.aihyb.pluginaihyb;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Server-rendered presentation of the canonical, published four-module read model. */
@Component
public class PortalRenderer {
    static final Map<String, String> LABELS = Map.of("review", "能力测评", "leaderboard", "评分与榜单", "pricing", "价格对比", "tutorial", "使用技巧与教程", "news", "最新资讯");
    private static final List<String> KINDS = List.of("review", "leaderboard", "pricing", "tutorial");
    private static final Map<String, String> TASKS = Map.ofEntries(Map.entry("office", "办公提效"), Map.entry("presentation", "PPT 与演示"),
        Map.entry("spreadsheet", "表格处理"), Map.entry("writing", "写作表达"), Map.entry("programming", "编程开发"),
        Map.entry("image", "画图设计"), Map.entry("video", "视频创作"), Map.entry("audio", "语音音频"), Map.entry("research", "学习研究"));
    private final PackageCodec codec;
    private final SiteSettings.Settings configuration;
    private final String selectedScene;
    @org.springframework.beans.factory.annotation.Autowired
    public PortalRenderer(PackageCodec codec) { this(codec,SiteSettings.defaults(),null); }
    private PortalRenderer(PackageCodec codec,SiteSettings.Settings configuration,String scene) { this.codec=codec; this.configuration=configuration; this.selectedScene=scene; }
    public String home(List<PackageViews.PublishedContent> items,SiteSettings.Settings configuration) { return new PortalRenderer(codec,configuration,null).home(items); }
    public String listing(String kind,PackageViews.Page<PackageViews.PublishedContent> page,String task,String scene,SiteSettings.Settings configuration) {
        return new PortalRenderer(codec,configuration,scene).listing(kind,page,task);
    }

    public String home(List<PackageViews.PublishedContent> items) {
        var review = items.stream().filter(item -> item.kind().equals("review")).findFirst();
        var html = new StringBuilder("<div class='hero-surface'><div class='container'><section class='hero'><div><div class='eyebrow blue'>给每一位认真用 AI 的人</div><h1>选对 AI，<br><em>把时间还给自己。</em></h1><p class='hero-subtitle'>哪款好用、值不值得买、怎么用出效果。<br>从真实任务出发，帮你找到有依据的答案。</p><form class='searchbox' action='/search' method='get'><span aria-hidden='true'>⌕</span><input name='keyword' aria-label='搜索任务、工具或教程' placeholder='想用 AI 做什么？试试「做 PPT」'><button class='primary' type='submit'>找答案 →</button></form><div class='popular'><span>大家在看</span><a href='/aihyb/review?task=presentation'>做 PPT</a><a href='/aihyb/tutorial'>工作流与技巧</a><a href='/aihyb/pricing'>会员怎么选 ↗</a></div></div>");
        if (review.isPresent()) {
            var item = review.get();
            var image = item.assets().stream().filter(asset -> String.valueOf(asset.get("content_type")).startsWith("image/")).findFirst();
            html.append("<a class='featured-story").append(image.isPresent() ? " portal-featured-story" : "")
                .append("' href='").append(url(item)).append("'><div class='featured-art")
                .append(image.isPresent() ? " portal-featured-media" : "").append("'><div class='issue-label'>测评精选 <span>能力测评</span></div>");
            if (image.isPresent()) {
                html.append("<img src='").append(esc(String.valueOf(image.get().get("url")))).append("' alt='")
                    .append(esc(String.valueOf(image.get().get("alt")))).append("'>");
            } else {
                html.append("<div class='portal-hero-paper' aria-hidden='true'><span>从一个具体任务开始</span><strong>看结果，<br>也看过程。</strong><i></i><i></i><i></i></div><div class='portal-art-index' aria-hidden='true'><b>↗</b><span>任务</span><span>样例</span><span>依据</span></div><span class='art-note'>专题排版示意</span>");
            }
            html.append("</div><div class='story-caption'><div><h3>")
                .append(esc(title(item))).append("</h3><p>").append(esc(task(item))).append(" · 结论与证据一起看</p></div><span class='round-arrow'>↗</span></div></a>");
        } else {
            html.append("<a class='featured-story' href='/aihyb/review'><div class='featured-art portal-empty-art'><strong>好不好用，<br>任务里见。</strong></div><div class='story-caption'><h3>从能力测评开始了解 AI</h3><span class='round-arrow'>↗</span></div></a>");
        }
        html.append("</section><div class='module-strip'>");
        var descriptions = List.of("看样例，判断好不好用", "按任务，找到合适的 AI", "比权益，也比实际花费", "从能用，到真正用好");
        for (var index = 0; index < KINDS.size(); index++) {
            var kind = KINDS.get(index);
            html.append("<a class='module-item' href='/aihyb/").append(kind).append("'><span class='module-icon'>")
                .append(List.of("◎", "▥", "▤", "▧").get(index)).append("</span><div><strong>").append(LABELS.get(kind))
                .append("</strong><p>").append(descriptions.get(index)).append("</p></div><span class='small-arrow'>↗</span></a>");
        }
        html.append("</div></div></div><div class='container home-content'><div class='scene-row'><span class='scene-label'>你想做什么</span><nav class='scene-options' aria-label='按场景找 AI'>")
            .append(sceneLinks("leaderboard", null, false)).append("</nav></div>");
        html.append("<section class='news-section' id='latest-news' aria-labelledby='latest-news-title'><div class='news-heading'><div><span class='news-eyebrow'>AI 动态 · 观察与解读</span><h2 id='latest-news-title'>最新资讯<span aria-hidden='true'>↗</span></h2><p>关注新变化，也保留理解它的上下文。</p></div><a class='text-link' href='/aihyb/news'>查看全部资讯 →</a></div>")
            .append(news(items.stream().filter(item -> "news".equals(item.kind()))
                .sorted(java.util.Comparator.comparing((PackageViews.PublishedContent item) -> String.valueOf(item.content().get("news_date"))).reversed())
                .limit(5).toList())).append("</section>");
        html.append(sectionHeading("01", "好不好用，任务里见。", "每份榜单保留任务、评分口径、日期与推荐理由。", "leaderboard", "看全部榜单"));
        var boards = items.stream().filter(item -> item.kind().equals("leaderboard")).limit(1).toList();
        html.append("<div class='home-ranking'><div>");
        if (boards.isEmpty()) { html.append(empty("暂无已发布榜单", "核验后的任务榜单会在这里展示。")); }
        for (var board : boards) { html.append(board(board)); }
        html.append("</div><aside class='editor-picks'><div class='editor-kicker'>编辑的选择</div><h3>选完工具，<br>继续把事情做好。</h3><div class='editor-list'>");
        for (var item : items.stream().filter(value -> KINDS.contains(value.kind()) && !value.kind().equals("leaderboard")).limit(3).toList()) {
            html.append("<a class='editor-item' href='").append(url(item)).append("'><span>").append(LABELS.get(item.kind())).append("</span><strong>").append(esc(title(item))).append(" ↗</strong></a>");
        }
        html.append("</div></aside></div></section>")
            .append(sectionHeading("02", "把结果摆出来，再下结论。", "任务、关键差异与原始来源，一起看。", "review", "看全部测评"))
            .append(cards(items.stream().filter(item -> item.kind().equals("review")).limit(3).toList(), false)).append("</section>")
            .append(sectionHeading("03", "买得明白，才能用得痛快。", "所有方案直接展开，额度、重置、地区与限制放在报价旁边。", "pricing", "看全部价格与任务消耗"))
            .append(PricingRenderer.render(PricingRenderer.records(items), true))
            .append("<a class='text-link portal-usage-link' href='/aihyb/pricing#task-usage'>同一任务消耗多少百分比额度？看使用记录 →</a></section>")
            .append(sectionHeading("04", "选完工具，下一步就上手。", "少一点反复摸索，多一点可以复用的方法。", "tutorial", "看全部教程"))
            .append(cards(items.stream().filter(item -> item.kind().equals("tutorial")).limit(3).toList(), true)).append("</section>")
            .append("<div class='closing-note'><span>结论有来源，价格有口径，内容有核验日期。</span><span>让每一次选择，都有依据。</span></div></div>");
        return html.toString();
    }

    public String listing(String kind, PackageViews.Page<PackageViews.PublishedContent> page, String selectedTask) {
        if (kind == null) { return home(page.items()); }
        var titles = Map.of("review", "先看结果，再看结论。", "leaderboard", "找到适合这件事的 AI。", "pricing", "看清权益，也看真实花费。", "tutorial", "把 AI，变成你的工作习惯。", "news", "最新资讯");
        var descriptions = Map.of("review", "从任务条件与样例出发，查看结论、证据和原始来源。", "leaderboard", "每份榜单独立保留评分口径，不混合不同任务与基准的分数。", "pricing", "各产品全部方案默认展开，直接看额度、重置方式、地区、限制与同任务消耗。", "tutorial", "从一个小任务开始，学习可以复用的提示词、工作流与使用方法。", "news", "AI 动态、行业观察与有出处的解读，按收录日期更新。");
        var html = new StringBuilder("<div class='container'><div class='page-head'><div><div class='breadcrumb'><a href='/'>首页</a><span>/</span><span>")
            .append(LABELS.get(kind)).append("</span></div><h1>").append(titles.get(kind)).append("</h1><p>").append(descriptions.get(kind)).append("</p></div></div>");
        if (!kind.equals("news")) {
            html.append("<div class='filter-bar'><div class='filter-line'><span>场景</span><nav class='filter-buttons' aria-label='任务分类'>")
                .append(sceneLinks(kind,selectedScene,true)).append("</nav></div><div class='filter-line'><span>任务</span><nav class='filter-buttons' aria-label='任务标签'>")
                .append(taskLinks(kind, selectedTask, true)).append("</nav></div></div>");
        }
        if (kind.equals("pricing")) {
            html.append("<nav class='catalog-jumps' aria-label='价格对比章节'><a href='#all-plans'>全部套餐与额度 ↓</a><a href='#task-usage'>同任务体感额度消耗 ↓</a></nav><div id='all-plans'>")
                .append(PricingRenderer.render(PricingRenderer.records(page.items()), false)).append("</div>");
        } else if (kind.equals("news")) {
            html.append(news(page.items()));
        } else if (kind.equals("leaderboard")) {
            if (page.items().isEmpty()) { html.append(empty("暂时没有符合条件的榜单", "试试其他任务分类。")); }
            for (var item : page.items()) { html.append(board(item)); }
        } else {
            html.append("<div class='page-subheading'><h2>").append(LABELS.get(kind)).append("<small>共 ").append(page.total()).append(" 条已发布内容</small></h2></div>")
                .append(cards(page.items(), kind.equals("tutorial")));
        }
        if (!kind.equals("pricing")) {
            html.append("<nav class='portal-pagination' aria-label='内容分页'><span>第 ").append(page.page()).append(" 页 · 共 ").append(page.total()).append(" 条</span>");
            var query = "?task=" + URLEncoder.encode(selectedTask == null ? "" : selectedTask, StandardCharsets.UTF_8) + "&amp;scene=" + (selectedScene==null?"":selectedScene) + "&amp;size=" + page.size() + "&amp;page=";
            if (page.page() > 1) { html.append("<a class='secondary' href='").append(query).append(page.page() - 1).append("'>上一页</a>"); }
            if ((long) page.page() * page.size() < page.total()) { html.append("<a class='secondary' href='").append(query).append(page.page() + 1).append("'>下一页</a>"); }
            html.append("</nav>");
        }
        return html.append("</div>").toString();
    }

    private String news(List<PackageViews.PublishedContent> items) {
        if (items.isEmpty()) { return empty("新的资讯正在路上", "有出处的 AI 动态与观察，会陆续收录在这里。"); }
        var html = new StringBuilder("<ol class='news-timeline'>");
        for (var item : items) {
            var date = String.valueOf(item.content().get("news_date"));
            var source = codec.tree(item.sources()).path(0);
            html.append("<li class='news-entry'><div class='news-date'><time datetime='").append(esc(date)).append("'>")
                .append(esc(date.length() == 10 ? date.substring(5).replace('-', '/') : date)).append("</time><span>")
                .append(esc(date.length() == 10 ? date.substring(0, 4) + " · 收录" : "收录日期")).append("</span></div><article class='news-card'><div class='news-copy'><div class='news-meta'><span class='news-label'>资讯解读</span>");
            if (!item.sources().isEmpty()) {
                html.append("<span>").append(esc(source.path("author").asText("来源材料"))).append("</span>");
            }
            html.append("</div><h3><a href='")
                .append(url(item)).append("'>").append(esc(title(item))).append("</a></h3><p class='news-summary'>")
                .append(esc(summary(item))).append("</p><div class='news-footer'>");
            var sourceUrl = source.path("url").asText();
            if (sourceUrl.matches("(?i)^https?://.*")) {
                html.append("<a class='news-source' href='").append(esc(sourceUrl)).append("' target='_blank' rel='noopener noreferrer' data-link-kind='reference'>原始来源 ↗</a>");
            }
            html.append("<a class='news-read' href='").append(url(item)).append("'>阅读全文 <span aria-hidden='true'>→</span></a></div></div>");
            var image = item.assets().stream().filter(asset -> String.valueOf(asset.get("content_type")).startsWith("image/")).findFirst();
            if (image.isPresent()) {
                html.append("<a class='news-image' href='").append(url(item)).append("' tabindex='-1' aria-hidden='true'><img loading='lazy' src='")
                    .append(esc(String.valueOf(image.get().get("url")))).append("' alt=''></a>");
            }
            html.append("</article></li>");
        }
        return html.append("</ol>").toString();
    }

    private String cards(List<PackageViews.PublishedContent> items, boolean tutorials) {
        if (items.isEmpty()) { return empty("暂时没有符合条件的已发布内容", "核验与发布后的内容会显示在这里。"); }
        var html = new StringBuilder(tutorials ? "<div class='tutorial-page-list'>" : "<div class='review-grid review-page-grid'>");
        for (var item : items) {
            var data = codec.tree(item.data());
            if (tutorials) {
                html.append("<a class='tutorial-detail-card' href='").append(url(item)).append("'><span>↗</span><div><span class='pill'>")
                    .append(esc(task(item))).append("</span><h3>").append(esc(title(item))).append("</h3><p>").append(esc(summary(item)))
                    .append("</p><div class='meta-line'><span>").append(esc(data.path("tool_name").asText("实用教程"))).append("</span><span>")
                    .append(verification(data.path("verification_status").asText())).append("</span><span>阅读步骤与提示词 →</span></div></div></a>");
            } else {
                html.append("<a class='review-card' href='").append(url(item)).append("'>");
                var image = item.assets().stream().filter(asset -> String.valueOf(asset.get("content_type")).startsWith("image/")).findFirst();
                if (image.isPresent()) {
                    html.append("<div class='review-cover portal-media-cover'><img loading='lazy' src='").append(esc(String.valueOf(image.get().get("url"))))
                        .append("' alt='").append(esc(String.valueOf(image.get().get("alt")))).append("'></div>");
                } else {
                    html.append("<div class='review-cover portal-text-cover'><span class='cover-badge'>").append(esc(task(item)))
                        .append("</span><strong>看结果，<br>也看依据。</strong><span class='cover-note'>专题排版 · 非模型输出</span></div>");
                }
                html.append("<div class='review-body'><div class='meta-line'><span class='tag'>能力测评</span><span>")
                    .append(esc(task(item))).append("</span></div><h3>").append(esc(title(item))).append("</h3><p>").append(esc(summary(item)))
                    .append("</p><div class='review-footer'><span>").append(esc(data.path("tool_name").asText("任务测评")))
                    .append("</span><b>结论与来源 ↗</b></div></div></a>");
            }
        }
        return html.append("</div>").toString();
    }
    private String board(PackageViews.PublishedContent item) {
        var data = codec.tree(item.data()); var sources = codec.tree(item.sources());
        boolean overall="overall".equals(data.path("task").asText());
        var html = new StringBuilder("<section class='panel portal-board'><div class='panel-head'><div class='panel-title'><a href='")
            .append(url(item)).append("'>").append(esc(title(item))).append("</a><small>").append(esc(task(item))).append(" · ")
            .append(esc(data.path("benchmark").asText())).append(" · ").append(esc(data.path("score_unit").asText()))
            .append("</small></div><span class='pill'>方法 ").append(esc(data.path("method_version").asText())).append("</span></div>")
            .append("<div class='ranking-table-wrapper'><table class='ranking-table portal-ranking'><thead><tr><th scope='col'>名次</th><th scope='col'>工具 / 型号</th><th scope='col'>原始成绩</th><th scope='col'>推荐理由</th><th scope='col'>来源</th></tr></thead><tbody>");
        for (var entry : data.path("entries")) {
            html.append("<tr><td class='rank-number'>").append(esc(entry.path("rank").asText())).append("</td><th scope='row'><strong>")
                .append(esc(entry.path("tool_name").asText())).append("</strong><small>").append(esc(entry.path("model_version").asText()))
                .append(entry.path("product_url").asText().isBlank()?"":" <a data-link-kind='reference' href='"+esc(entry.path("product_url").asText())+"' target='_blank' rel='noopener'>使用入口 ↗</a>")
                .append("</small></th><td><strong class='score-value'>").append(entry.path("score").isNull() ? (overall?"人工排序，不计算总分":"未知") : esc(entry.path("score").asText()))
                .append("</strong></td><td class='rank-reason'>").append(esc(entry.path("reason").asText())).append("</td><td>")
                .append(PricingRenderer.source(sources, entry.path("source_id").asText())).append("</td></tr>");
        }
        html.append("</tbody></table></div><div class='panel-foot'><span>榜单快照 ").append(esc(PricingRenderer.date(data.path("as_of").asText())))
            .append(" · 分数仅在同一口径内比较</span><a href='").append(url(item)).append("'>完整方法与依据 ↗</a></div></section>");
        return html.toString();
    }
    private String taskLinks(String kind,String selected,boolean includeAll) {
        var html=new StringBuilder();
        var scene=selectedScene==null?"":"scene="+selectedScene+"&amp;";
        if(includeAll) html.append("<a class='").append(selected==null||selected.isBlank()?"active":"").append("' href='/aihyb/").append(kind).append(selectedScene==null?"":"?scene="+selectedScene).append("'>全部任务</a>");
        for(var tag:configuration.tags()) if(tag.enabled() && (selectedScene==null||selectedScene.isBlank()||tag.scenes().contains(selectedScene))) {
            html.append("<a class='").append(tag.id().equals(selected)?"active":"").append("' href='/aihyb/").append(kind).append("?").append(scene).append("task=").append(tag.id()).append("'>").append(esc(tag.label())).append("</a>");
        }
        return html.toString();
    }
    private static String sceneLinks(String kind,String selected,boolean includeAll) {
        var html=new StringBuilder();
        if(includeAll) html.append("<a class='").append(selected==null||selected.isBlank()?"active":"").append("' href='/aihyb/").append(kind).append("'>全部场景</a>");
        for(var id:SiteSettings.SCENE_ORDER) {
            if(id.equals("overall")&&!kind.equals("leaderboard")) continue;
            html.append("<a class='").append(id.equals(selected)?"active":"").append("' href='/aihyb/").append(kind).append("?scene=").append(id).append("'>").append(SiteSettings.SCENES.get(id)).append("</a>");
        }
        return html.toString();
    }
    private static String sectionHeading(String number, String title, String description, String kind, String action) {
        return "<section class='section'><div class='section-heading'><div><div class='label-line'><span class='section-index'>" + number
            + "</span><h2>" + title + "</h2></div><p>" + description + "</p></div><a class='text-link' href='/aihyb/" + kind + "'>" + action + " →</a></div>";
    }
    private static String empty(String title, String description) { return "<div class='empty-state'><h3>" + title + "</h3><p>" + description + "</p></div>"; }
    private String task(PackageViews.PublishedContent item) {
        if("overall".equals(item.data().get("task"))) return "综合 · 人工排序";
        var ids=SiteSettings.taskIds(item);
        var labels=configuration.tags().stream().filter(tag->ids.contains(tag.id())).map(SiteSettings.Tag::label).toList();
        return labels.isEmpty()?"其他任务":String.join(" · ",labels);
    }
    private static String title(PackageViews.PublishedContent item) { return String.valueOf(item.content().get("title")); }
    private static String summary(PackageViews.PublishedContent item) { return String.valueOf(item.content().get("summary")); }
    private static String url(PackageViews.PublishedContent item) {
        var url = item.url();
        return esc(url == null || url.isBlank() ? "/aihyb/content/" + item.content_id() : url);
    }
    private static String verification(String value) { return value.equals("reproduced") ? "已复现" : "待复现核验"; }
    private static String esc(String value) { return PackageRenderer.escape(value); }
}
