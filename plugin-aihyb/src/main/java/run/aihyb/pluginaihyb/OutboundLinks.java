package run.aihyb.pluginaihyb;

import java.net.URI;
import java.util.*;
import org.jsoup.nodes.Document;

/** Link relationships are independent of browser opener protection. */
public final class OutboundLinks {
    private OutboundLinks() {}
    public static void apply(Document document,URI origin,List<SiteSettings.LinkRule> rules) {
        var configured=new HashMap<String,String>(); rules.forEach(rule->configured.put(rule.url(),rule.relation()));
        for(var link:document.select("a[href]")) {
            URI target;
            try { target=origin.resolve(link.attr("href")); } catch(IllegalArgumentException error) { continue; }
            if(!List.of("http","https").contains(target.getScheme())||target.getHost()==null) continue;
            boolean internal=Objects.equals(origin.getHost(),target.getHost()) && port(origin)==port(target) && Objects.equals(origin.getScheme(),target.getScheme());
            var rel=new LinkedHashSet<>(Arrays.asList(link.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"))); rel.remove("");
            var explicit=rel.contains("sponsored")?"sponsored":rel.contains("ugc")?"ugc":rel.contains("nofollow")?"nofollow":"";
            rel.removeAll(List.of("nofollow","sponsored","ugc"));
            if("_blank".equals(link.attr("target"))) rel.add("noopener");
            if(!internal) {
                var relation=configured.getOrDefault(target.toString(),link.attr("data-link-kind").isBlank()?explicit:link.attr("data-link-kind"));
                switch(relation) {
                    case "reference" -> { }
                    case "sponsored" -> rel.addAll(List.of("sponsored","nofollow"));
                    case "ugc" -> rel.addAll(List.of("ugc","nofollow"));
                    default -> rel.add("nofollow");
                }
            }
            if(rel.isEmpty()) link.removeAttr("rel"); else link.attr("rel",String.join(" ",rel));
        }
    }
    private static int port(URI uri) { return uri.getPort()>=0?uri.getPort():"https".equals(uri.getScheme())?443:80; }
}
