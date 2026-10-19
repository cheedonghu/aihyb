# 视频流水线

把一份 `episode.json`（文案 + 素材引用）变成带配音、字幕的横屏视频。风格参照“素材卡片 + 口播 + 底部字幕”的资讯解说视频：画面以素材为主，硬切为主，不做花哨转场。

```
人工收集素材（截图/录屏/官方图）
        ↓
AI 按 prompts/write-episode.md 写 episode.json（文案 + 每段配哪张素材）
        ↓
人工审稿：事实、来源、措辞
        ↓
python scripts/build.py <id> --render   → out/<id>.mp4
```

## 首次准备

```powershell
cd video
npm install
python -m venv .venv            # 需要 Python 3.10+，带 pip
.venv\Scripts\python.exe -m pip install -r requirements.txt
```

配音用 [edge-tts](https://github.com/rany2/edge-tts)，免费、需联网。若出现 `403 Invalid response status`，先 `pip install -U edge-tts`，多数是微软握手参数变了。

## 做一期视频

1. 新建 `public/episodes/<id>/`，`<id>` 用小写短横线，例如 `2026-10-19-minimax-m31`。
2. 把素材放进去：截图 `png/jpg`、录屏 `mp4`。文件名用英文。
3. 让 AI 按 [写稿规范](prompts/write-episode.md) 生成 `episode.json`，或参照下面两个样例手写。
4. 预览：`npm run studio`，在右侧把 `episodeId` 改成你的 id（需先跑一次 build 生成时间线）。
5. 出片：`.venv\Scripts\python.exe scripts\build.py <id> --render`。

改文案后重跑 build 即可；配音按“文案 + 音色 + 语速”缓存，没改的段落不会重新合成。

样例：

- [demo-skill-tips](public/episodes/demo-skill-tips/episode.json)：skill 技巧分享，演示全部画面类型。
- [demo-news-minimax](public/episodes/demo-news-minimax/episode.json)：资讯快讯，只用原文事实并标注来源。

## episode.json 格式

| 字段 | 说明 |
|---|---|
| `id` | 必须等于目录名 |
| `title` | 视频标题，只用于记录 |
| `voice` / `rate` | 默认 `zh-CN-YunxiNeural`、`+10%`。其他中文音色：`zh-CN-XiaoxiaoNeural`（女）、`zh-CN-YunjianNeural`（男，偏解说） |
| `background` | `waves`（蓝色流动背景）或 `dark` |
| `credit` | 左上角角标，如“素材来自网络”“资讯来源：xx”；`null` 不显示 |
| `bgm` | 可选 `{"src": "bgm.mp3", "volume": 0.08}`，需自备有授权的音乐 |
| `sources` | 来源列表 `{title, url, note}`，build 时导出 `build/sources.md`，发布时贴进简介 |
| `segments` | 段落数组，一段 = 一句口播 + 一个画面 |

每段：

| 字段 | 说明 |
|---|---|
| `text` | 口播文案。字幕从这段文字切出，**数字、单位写成你希望被读出来的样子** |
| `visual` | 画面，见下表 |
| `hold` | 可选，口播结束后画面多停几秒 |
| `label` | 可选，在 Studio 时间轴上显示的名字 |

段落时长由配音长度自动决定，不用手填时间。

| `visual.type` | 字段 | 用途 |
|---|---|---|
| `image` | `src`，`fit`，`zoom`（默认缓慢放大） | 截图、官方配图、跑分表 |
| `video` | `src`，`start`（秒），`loop`，`volume`（默认静音） | 录屏、演示片段 |
| `title` | `heading`，`sub`，`tag` | 开场、章节、结尾 |
| `points` | `heading`，`items[]` | 要点列表，逐条出现 |
| `code` | `heading`，`code` | 配置、命令、skill 文件 |
| `quote` | `text`，`author` | 引用原文 |

视频素材比段落短时会冻结在最后一帧，build 会给出警告，设置 `"loop": true` 或缩短文案。

## 发布前检查

- 资讯类：每个事实都能在 `sources` 里找到出处；官方宣称的数据要说明“官方称”，不要说成实测结论。
- 素材版权：他人截图、视频片段只作引用和评论，标注来源；不要整段搬运他人视频。
- 平台标识：配音是 AI 合成，发布到抖音、B 站、视频号等平台时，按平台要求勾选“AI 生成内容”声明。
- 看一遍成片：字幕断句、数字和缩写的读法。“200T”“70%”这类写法 TTS 可能读得不自然，建议在 `text` 里写成“二百万亿”“百分之七十”，画面卡片保留原写法。

## 目录

```
video/
  scripts/build.py        校验 → 配音 → 字幕切分 → timeline.json → 渲染
  src/                    Remotion 组件（Episode、背景、素材卡片、字幕）
  prompts/write-episode.md  给 AI 的写稿规范
  public/episodes/<id>/   每期的 episode.json 和素材；build/ 为生成物，不提交
  out/                    成片，不提交
```
