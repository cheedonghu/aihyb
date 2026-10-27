package run.aihyb.pluginaihyb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class SiteSettings {
    public static final int MAX_BYTES = 240000;
    public static final Map<String,String> SCENES = Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
        "overall","综合", "programming","编程", "research","科研", "office","专业办公", "qa","知识问答", "image","生图", "video","视频")));
    public static final List<String> SCENE_ORDER = List.of("overall","programming","research","office","qa","image","video");
    public record Tag(String id, String label, boolean enabled, List<String> scenes) {}
    public record Qr(String id, String label, String description, String expiresOn, String image) {}
    public record LinkRule(String url, String relation) {}
    public record Settings(String version, List<Tag> tags, List<Qr> qrs, List<LinkRule> links) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAME = "aihyb-site-settings";
    private final ReactiveExtensionClient client;
    public SiteSettings(ReactiveExtensionClient client) { this.client = client; }
    public Mono<Settings> get() {
        return client.fetch(SiteConfiguration.class, NAME).map(resource -> {
            var value = parse(resource.getConfiguration());
            return new Settings("r"+resource.getMetadata().getVersion(), value.tags(), value.qrs(), value.links());
        }).defaultIfEmpty(defaults());
    }
    public Mono<Settings> save(String raw) {
        return Mono.fromCallable(() -> { if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) fail("配置过大，请缩小二维码或减少规则"); return validate(parse(raw)); })
            .subscribeOn(Schedulers.boundedElastic()).flatMap(next -> client.fetch(SiteConfiguration.class, NAME)
                .flatMap(current -> {
                    if (!("r"+current.getMetadata().getVersion()).equals(next.version())) return Mono.error(new PackageProblem(409,"配置已被修改，请重新加载后再保存"));
                    var old = parse(current.getConfiguration());
                    var ids = new HashSet<>(next.tags().stream().map(Tag::id).toList());
                    if (old.tags().stream().anyMatch(tag -> !ids.contains(tag.id()))) return Mono.error(new PackageProblem(422,"已有标签不能直接删除或更换标识，请停用以保留旧稿引用"));
                    current.setConfiguration(serialize(next));
                    return client.update(current);
                }).switchIfEmpty(Mono.defer(() -> {
                    if (!"0".equals(next.version())) return Mono.error(new PackageProblem(409,"配置版本不匹配，请重新加载"));
                    var ids = next.tags().stream().map(Tag::id).toList();
                    if (defaults().tags().stream().anyMatch(tag -> !ids.contains(tag.id()))) return Mono.error(new PackageProblem(422,"默认任务标签不能删除，请停用"));
                    var item = new SiteConfiguration(); var metadata = new Metadata(); metadata.setName(NAME); item.setMetadata(metadata);
                    item.setConfiguration(serialize(next)); return client.create(item);
                })).then(get()));
    }
    static Settings parse(String raw) {
        try { return JSON.readValue(raw, Settings.class); }
        catch (Exception error) { throw new PackageProblem(422,"站点配置格式无效"); }
    }
    static String serialize(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    static Settings validate(Settings value) {
        if (value == null || value.version() == null || value.tags() == null || value.qrs() == null || value.links() == null) fail("配置字段不完整");
        if (value.tags().size() > 100 || value.qrs().size() != 3 || value.links().size() > 100) fail("标签或链接规则数量超限，二维码须保留三类");
        var ids = new HashSet<String>(); var labels = new HashSet<String>();
        for (var tag : value.tags()) {
            if (tag == null || tag.id() == null || !tag.id().matches("[a-z0-9][a-z0-9-]{0,79}") || !ids.add(tag.id())) fail("标签标识无效或重复");
            text(tag.label(),40,"标签名称");
            if (!labels.add(tag.label().strip().toLowerCase(Locale.ROOT))) fail("标签名称重复");
            if (tag.scenes() == null || tag.scenes().size() > 6 || new HashSet<>(tag.scenes()).size()!=tag.scenes().size()
                || tag.scenes().stream().anyMatch(scene -> !SCENES.containsKey(scene) || scene.equals("overall"))) fail("标签场景无效；综合榜需单独人工维护");
        }
        ids.clear();
        for (var qr : value.qrs()) {
            if (qr == null || !List.of("group","account","support").contains(qr.id()) || !ids.add(qr.id())) fail("二维码类型无效或重复");
            text(qr.label(),40,"二维码名称"); if(qr.description()==null || qr.description().length()>160) fail("二维码说明过长");
            if (qr.expiresOn()==null || qr.image()==null) fail("二维码字段不完整");
            if (!qr.expiresOn().isBlank()) try { LocalDate.parse(qr.expiresOn()); } catch (Exception error) { fail("二维码到期日期无效"); }
            if (!qr.image().isBlank()) imageBytes(qr);
        }
        ids.clear();
        for (var rule : value.links()) {
            if (rule == null || !safeUrl(rule.url()) || !ids.add(rule.url())) fail("外链地址无效或重复，请填写完整 HTTP/HTTPS 地址");
            if (!List.of("reference","sponsored","ugc","nofollow").contains(rule.relation())) fail("外链关系无效");
        }
        return value;
    }
    private static void text(String value,int max,String field) { if(value==null || value.isBlank() || value.length()>max) fail(field+"为空或过长"); }
    private static void fail(String message) { throw new PackageProblem(422,message); }
    public static boolean safeUrl(String value) {
        try { var url=URI.create(value); return value.length()<=2048 && ("https".equals(url.getScheme())||"http".equals(url.getScheme())) && url.getHost()!=null && url.getUserInfo()==null; }
        catch(Exception error) { return false; }
    }
    public static byte[] imageBytes(Qr qr) {
        try {
            if (!qr.image().startsWith("data:image/png;base64,") || qr.image().length()>66000) fail("二维码须为不超过 48 KB 的 PNG 图片");
            var bytes=Base64.getDecoder().decode(qr.image().substring(22));
            if(bytes.length>49152) fail("二维码图片超过 48 KB");
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) fail("二维码图片无法读取");
                var reader=readers.next(); try { reader.setInput(input); int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(!"png".equalsIgnoreCase(reader.getFormatName()) || w<64 || h<64 || w>1024 || h>1024) fail("二维码尺寸须在 64–1024 像素之间");
                    if(reader.read(0)==null) fail("二维码图片无效");
                } finally { reader.dispose(); }
            }
            return bytes;
        } catch(PackageProblem error) { throw error; } catch(Exception error) { throw new PackageProblem(422,"二维码图片无效"); }
    }
    public static boolean active(Qr qr) {
        return !qr.image().isBlank() && (qr.expiresOn().isBlank() || !LocalDate.parse(qr.expiresOn()).isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
    }
    public static String imageUrl(Qr qr) { return "/aihyb/site-media/"+qr.id()+"/"+PackageCodec.hash(qr.image())+".png"; }
    public static Settings defaults() {
        return new Settings("0",List.of(
            new Tag("office","办公提效",true,List.of("office")),new Tag("presentation","PPT 与演示",true,List.of("office")),
            new Tag("spreadsheet","表格处理",true,List.of("office")),new Tag("writing","写作表达",true,List.of("office")),
            new Tag("programming","编程开发",true,List.of("programming")),new Tag("research","学习研究",true,List.of("research")),
            new Tag("qa","知识问答",true,List.of("qa")),new Tag("image","画图设计",true,List.of("image")),
            new Tag("video","视频创作",true,List.of("video")),new Tag("audio","语音音频",true,List.of())),
            List.of(new Qr("group","微信群","","",""),new Qr("account","公众号","","",""),new Qr("support","客服","","","")),List.of());
    }
    public static Set<String> taskIds(PackageViews.PublishedContent item) {
        var result=new LinkedHashSet<String>(); var tags=item.content().get("task_tags");
        if(tags instanceof List<?> values) values.forEach(tag->result.add(String.valueOf(tag)));
        else result.add(String.valueOf(item.data().get("task")));
        return result;
    }
    public static boolean matches(Settings settings,PackageViews.PublishedContent item,String task,String scene) {
        var ids=taskIds(item);
        if(task!=null&&!task.isBlank()&&!ids.contains(task)) return false;
        if(scene==null||scene.isBlank()) return true;
        if(scene.equals("overall")) return item.kind().equals("leaderboard")&&"overall".equals(item.data().get("task"));
        return settings.tags().stream().anyMatch(tag->tag.enabled()&&ids.contains(tag.id())&&tag.scenes().contains(scene));
    }
}
