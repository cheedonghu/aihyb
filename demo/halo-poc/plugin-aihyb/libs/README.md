# Feed 1.5.0 public API

`feed-api-1.5.0.jar` contains only the three unchanged public API class files extracted from the official installed `PluginFeed-1.5.0.jar`: `run.halo.feed.RssCacheClearRequested`, `CacheClearRule`, and `CacheClearRule$Type`.

Source: [halo-dev/plugin-feed v1.5.0](https://github.com/halo-dev/plugin-feed/tree/v1.5.0/api). Copyright and license remain those of the upstream Halo Feed project; the verbatim GPL-3.0 license is [PluginFeed-LICENSE](./PluginFeed-LICENSE). Extraction hashes are recorded in [feed-api-extraction.json](../../evidence/feed-api-extraction.json).

The JAR is a local `compileOnly` dependency (plus test runtime); it is not bundled into the AIHYB plugin. Runtime classes come from the declared `PluginFeed >=1.5.0` dependency. This PoC has verified only Feed 1.5.0; retest the shared event and alias/tag cache invalidation before upgrading Feed. A failed cache notification is logged without failing or repeating the already committed publication; durable retry is not implemented.
