package run.aihyb.pluginaihyb;

import java.util.List;

/** Shared server-rendered footer; empty QR slots never create broken images. */
public final class SiteFooter {
    private SiteFooter() {}
    public static String render(SiteSettings.Settings settings) {
        var html=new StringBuilder("<div class='aihyb-footer-inner'><div class='aihyb-footer-main'><div class='aihyb-footer-brand'><a href='/'><img src='/aihyb/assets/logo-primary.png' alt='AI好用榜' width='164'></a><p>让每一次选择，都有依据。</p></div><nav class='aihyb-footer-nav' aria-label='页脚导航'><ul>");
        for(var kind:List.of("review","leaderboard","pricing","tutorial","news")) {
            html.append("<li><a href='/aihyb/").append(kind).append("'>").append(PortalRenderer.LABELS.get(kind)).append("</a></li>");
        }
        html.append("<li><a href='/archives'>内容归档</a></li></ul></nav></div>");
        var active=settings.qrs().stream().filter(SiteSettings::active).toList();
        if(!active.isEmpty()) {
            html.append("<div class='aihyb-footer-qrs' aria-label='关注与联系'>");
            for(var qr:active) html.append("<figure><a href='").append(SiteSettings.imageUrl(qr)).append("' target='_blank' rel='noopener' aria-label='")
                .append(esc(qr.label())).append("，打开图片保存'><img src='").append(SiteSettings.imageUrl(qr)).append("' alt='").append(esc(qr.label())).append("二维码' width='144' height='144' loading='lazy'></a><figcaption><strong>")
                .append(esc(qr.label())).append("</strong><span>").append(esc(qr.description())).append("</span><small>点击图片放大或保存</small></figcaption></figure>");
            html.append("</div>");
        }
        if(settings.qrs().stream().anyMatch(qr->qr.id().equals("group")&&!qr.image().isBlank()&&!SiteSettings.active(qr))) {
            html.append("<p class='aihyb-qr-expired'>微信群二维码已到期，");
            html.append(active.stream().anyMatch(qr->qr.id().equals("support"))?"请通过客服入口联系。":"请等待管理员更新。").append("</p>");
        }
        return html.append("<div class='aihyb-footer-bottom'>© 2026 AI好用榜 AIHYB.COM</div></div>").toString();
    }
    private static String esc(String text) { return PackageRenderer.escape(text); }
}
