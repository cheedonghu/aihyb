"""Build an episode: validate episode.json, synthesize narration, derive captions, emit timeline.json.

Usage:
    python scripts/build.py <episode-id> [--render]

Inputs live in public/episodes/<episode-id>/episode.json plus referenced assets.
Outputs go to public/episodes/<episode-id>/build/ (regenerable) and, with --render, out/<episode-id>.mp4.
"""
import argparse
import asyncio
import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

import edge_tts

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / 'public'
COMPOSITOR = ROOT / 'node_modules/@remotion/compositor-win32-x64-msvc'
FFPROBE = COMPOSITOR / ('ffprobe.exe' if sys.platform == 'win32' else 'ffprobe')

FPS = 30
DEFAULT_VOICE = 'zh-CN-YunxiNeural'
DEFAULT_RATE = '+10%'
LEAD_IN = 0.15  # silence before speech in each segment, seconds
TAIL = 0.25  # silence after speech in each segment, seconds
MAX_CAPTION_UNITS = 20  # one CJK char = 1 unit, ASCII = 0.55
CAPTION_PUNCT = '，。？！：、；,.?!:; '
VISUAL_TYPES = {'image', 'video', 'title', 'points', 'code', 'quote'}


class EpisodeError(Exception):
    pass


def ffprobe_duration(path):
    result = subprocess.run(
        [str(FFPROBE), '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(path)],
        capture_output=True,
    )
    if result.returncode:
        raise EpisodeError(f'ffprobe failed for {path}: {result.stderr.decode("utf-8", "replace")}')
    return float(json.loads(result.stdout)['format']['duration'])


def validate(episode, folder):
    errors = []
    for key in ('id', 'title', 'segments'):
        if key not in episode:
            errors.append(f'missing field: {key}')
    if episode.get('id') != folder.name:
        errors.append(f'id {episode.get("id")!r} must equal folder name {folder.name!r}')
    segments = episode.get('segments') or []
    if not segments:
        errors.append('segments must not be empty')
    for i, seg in enumerate(segments):
        where = f'segments[{i}]'
        if not str(seg.get('text', '')).strip():
            errors.append(f'{where}.text is empty')
        visual = seg.get('visual')
        if not isinstance(visual, dict) or visual.get('type') not in VISUAL_TYPES:
            errors.append(f'{where}.visual.type must be one of {sorted(VISUAL_TYPES)}')
            continue
        if visual['type'] in ('image', 'video'):
            src = visual.get('src')
            if not src:
                errors.append(f'{where}.visual.src is required for {visual["type"]}')
            elif not (folder / src).is_file():
                errors.append(f'{where}.visual.src not found: {folder / src}')
        if visual['type'] == 'points' and not visual.get('items'):
            errors.append(f'{where}.visual.items is required for points')
        if visual['type'] == 'code' and not visual.get('code'):
            errors.append(f'{where}.visual.code is required for code')
    for i, source in enumerate(episode.get('sources', [])):
        if not source.get('url') and not source.get('note'):
            errors.append(f'sources[{i}] needs url or note')
    bgm = episode.get('bgm')
    if bgm and not (folder / bgm.get('src', '')).is_file():
        errors.append(f'bgm.src not found: {bgm.get("src")}')
    if errors:
        raise EpisodeError('episode.json invalid:\n  ' + '\n  '.join(errors))


async def synthesize(text, voice, rate, out_dir, index, semaphore):
    mp3 = out_dir / f'seg-{index:02d}.mp3'
    words_path = out_dir / f'seg-{index:02d}.words.jsonl'
    key_path = out_dir / f'seg-{index:02d}.key'
    key = hashlib.sha256(json.dumps([text, voice, rate], ensure_ascii=False).encode()).hexdigest()
    if key_path.exists() and key_path.read_text() == key and mp3.exists() and words_path.exists():
        return mp3, words_path
    async with semaphore:
        for attempt in range(3):
            try:
                pending_mp3 = mp3.with_suffix('.pending.mp3')
                pending_words = words_path.with_suffix('.pending.jsonl')
                speech = edge_tts.Communicate(text, voice, rate=rate, boundary='WordBoundary')
                await speech.save(str(pending_mp3), str(pending_words))
                if not pending_words.read_text(encoding='utf-8').strip():
                    raise EpisodeError('edge-tts returned no word boundaries')
                pending_mp3.replace(mp3)
                pending_words.replace(words_path)
                key_path.write_text(key)
                print(f'  tts segment {index + 1}', flush=True)
                return mp3, words_path
            except Exception:
                if attempt == 2:
                    raise
                await asyncio.sleep(2 * (attempt + 1))
    raise RuntimeError('unreachable')


def visual_units(value):
    return sum(0.55 if ord(ch) < 128 else 1 for ch in value)


def make_captions(text, words, offset_s):
    """Align TTS word boundaries back to the written text, then page them into one-line captions."""
    tokens, cursor = [], 0
    for word in words:
        found = text.find(word['text'], cursor)
        if found < 0:
            # TTS may normalize numbers or symbols; fall back to proportional timing for the rest.
            break
        end = found + len(word['text'])
        while end < len(text) and text[end] in CAPTION_PUNCT:
            end += 1
        start_s = offset_s + word['offset'] / 1e7
        tokens.append({'text': text[cursor:end], 'start': start_s, 'end': start_s + word['duration'] / 1e7})
        cursor = end
    if cursor < len(text):
        last_end = tokens[-1]['end'] if tokens else offset_s
        tokens.append({'text': text[cursor:], 'start': last_end, 'end': last_end + 0.3 * visual_units(text[cursor:])})

    pages, current = [], []
    for token in tokens:
        combined = visual_units(''.join(t['text'] for t in current) + token['text'])
        # Let a short clause-ending token stay on the current page so pages never start with "的，".
        closes_clause = token['text'].rstrip().endswith(tuple('，。？！；,.?!;')) and visual_units(token['text']) <= 3
        # Keep multi-word English terms such as "Computer Use" on one page.
        joins_latin = bool(current) and current[-1]['text'][-1:].isascii() and current[-1]['text'].strip()[-1:].isalnum() and token['text'][:1].isascii()
        allowance = 4 if closes_clause else 6 if joins_latin else 0
        if current and combined > MAX_CAPTION_UNITS + allowance:
            pages.append(current)
            current = []
        current.append(token)
        if token['text'].rstrip().endswith(tuple('，。？！；,.?!;')) and visual_units(''.join(t['text'] for t in current)) >= 6:
            pages.append(current)
            current = []
    if current:
        pages.append(current)
    captions = []
    for page in pages:
        shown = ''.join(t['text'] for t in page).strip().rstrip('，。、；,;')
        captions.append({'text': shown, 'start': page[0]['start'], 'end': page[-1]['end']})
    # Close gaps so captions do not flicker between pages.
    for current_page, next_page in zip(captions, captions[1:]):
        current_page['end'] = next_page['start']
    return captions


async def build(episode_id):
    folder = PUBLIC / 'episodes' / episode_id
    episode_path = folder / 'episode.json'
    if not episode_path.is_file():
        raise EpisodeError(f'not found: {episode_path}')
    episode = json.loads(episode_path.read_text(encoding='utf-8'))
    validate(episode, folder)

    out_dir = folder / 'build'
    out_dir.mkdir(exist_ok=True)
    voice = episode.get('voice', DEFAULT_VOICE)
    rate = episode.get('rate', DEFAULT_RATE)
    semaphore = asyncio.Semaphore(3)
    print(f'Synthesizing {len(episode["segments"])} segments with {voice} {rate}', flush=True)
    audio = await asyncio.gather(*(
        synthesize(seg['text'], voice, rate, out_dir, i, semaphore) for i, seg in enumerate(episode['segments'])
    ))

    timeline_segments, captions, cursor_frames = [], [], 0
    for i, (seg, (mp3, words_path)) in enumerate(zip(episode['segments'], audio)):
        speech = ffprobe_duration(mp3)
        hold = float(seg.get('hold', 0))
        frames = math.ceil((LEAD_IN + speech + TAIL + hold) * FPS)
        visual = dict(seg['visual'])
        if visual['type'] == 'video':
            available = ffprobe_duration(folder / visual['src']) - float(visual.get('start', 0))
            if available * FPS < frames and not visual.get('loop'):
                print(f'  warning: segment {i + 1} video is {available:.1f}s, segment needs {frames / FPS:.1f}s; last frame will freeze. Set "loop": true or trim narration.', flush=True)
        if visual.get('src'):
            visual['src'] = f'episodes/{episode_id}/{visual["src"]}'
        words = [json.loads(line) for line in words_path.read_text(encoding='utf-8').splitlines() if line.strip()]
        segment_start_s = cursor_frames / FPS
        for cap in make_captions(seg['text'], words, segment_start_s + LEAD_IN):
            captions.append({
                'text': cap['text'],
                'startFrame': round(cap['start'] * FPS),
                'endFrame': min(round(cap['end'] * FPS), cursor_frames + frames),
            })
        timeline_segments.append({
            'from': cursor_frames,
            'durationInFrames': frames,
            'audio': f'episodes/{episode_id}/build/{mp3.name}',
            'audioDelayFrames': round(LEAD_IN * FPS),
            'visual': visual,
            'label': seg.get('label'),
        })
        cursor_frames += frames

    bgm = episode.get('bgm')
    timeline = {
        'id': episode_id,
        'title': episode['title'],
        'fps': FPS,
        'durationInFrames': cursor_frames,
        'background': episode.get('background', 'waves'),
        'credit': episode.get('credit', '素材来自网络'),
        'bgm': {'src': f'episodes/{episode_id}/{bgm["src"]}', 'volume': bgm.get('volume', 0.08)} if bgm else None,
        'segments': timeline_segments,
        'captions': captions,
    }
    (out_dir / 'timeline.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')
    if episode.get('sources'):
        lines = [f'# {episode["title"]}', '', '## 来源', '']
        for source in episode['sources']:
            lines.append(f'- {source.get("title", source.get("url", ""))} {source.get("url", "")} {source.get("note", "")}'.rstrip())
        (out_dir / 'sources.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'Timeline: {cursor_frames / FPS:.1f}s, {len(timeline_segments)} segments, {len(captions)} captions', flush=True)
    return timeline


def render(episode_id):
    out = ROOT / 'out' / f'{episode_id}.mp4'
    out.parent.mkdir(exist_ok=True)
    props = json.dumps({'episodeId': episode_id})
    npx = 'npx.cmd' if sys.platform == 'win32' else 'npx'
    cmd = [npx, 'remotion', 'render', 'src/index.ts', 'Episode', str(out), f'--props={props}', '--log=warn']
    print('Rendering:', ' '.join(cmd), flush=True)
    result = subprocess.run(cmd, cwd=ROOT)
    if result.returncode:
        raise EpisodeError(f'remotion render failed with exit code {result.returncode}')
    print(f'Output: {out}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('episode_id')
    parser.add_argument('--render', action='store_true', help='render out/<episode-id>.mp4 after building')
    args = parser.parse_args()
    try:
        asyncio.run(build(args.episode_id))
        if args.render:
            render(args.episode_id)
    except EpisodeError as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
