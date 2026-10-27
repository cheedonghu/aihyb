package run.aihyb.pluginaihyb;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class OutboundLinksTest {
    @Test void classificationsPreserveInternalNavigationAndProtectNewWindows() {
        var document=Jsoup.parse("<a id='internal' href='/aihyb/tutorial' rel='nofollow'>内链</a><a id='anchor' href='#section'>目录</a>"
            +"<a id='reference' href='https://docs.example/a' data-link-kind='reference' target='_blank'>来源</a>"
            +"<a id='paid' href='https://tools.example/offer' data-link-kind='reference' download>推广下载</a>"
            +"<a id='user' href='//user.example/' rel='ugc'>用户</a><a id='unknown' href='https://other.example'>未知</a>"
            +"<a id='same' href='http://5.231.253.4:80/archives' rel='nofollow sponsored'>同站绝对地址</a>");
        OutboundLinks.apply(document,URI.create("http://5.231.253.4/page"),List.of(new SiteSettings.LinkRule("https://tools.example/offer","sponsored")));
        assertFalse(document.getElementById("internal").hasAttr("rel"));assertFalse(document.getElementById("anchor").hasAttr("rel"));
        assertEquals("noopener",document.getElementById("reference").attr("rel"));assertEquals("sponsored nofollow",document.getElementById("paid").attr("rel"));
        assertEquals("ugc nofollow",document.getElementById("user").attr("rel"));assertEquals("nofollow",document.getElementById("unknown").attr("rel"));
        assertFalse(document.getElementById("same").hasAttr("rel"));
    }
}
