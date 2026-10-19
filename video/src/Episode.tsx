import React from 'react';
import {AbsoluteFill, Sequence, staticFile, useCurrentFrame} from 'remotion';
import {Audio} from '@remotion/media';
import {Background} from './components/Background';
import {Captions} from './components/Captions';
import {VisualCard} from './components/VisualCard';
import type {EpisodeProps} from './types';

export const FONT_STACK = '"Microsoft YaHei", "Noto Sans SC", "PingFang SC", sans-serif';

const Credit: React.FC<{text: string}> = ({text}) => (
	<div
		style={{
			position: 'absolute',
			left: 36,
			top: 30,
			padding: '6px 16px',
			borderRadius: 8,
			background: 'rgba(0,0,0,0.55)',
			borderLeft: '4px solid #f5c542',
			color: '#fff',
			fontFamily: FONT_STACK,
			fontSize: 26,
		}}
	>
		{text}
	</div>
);

const Progress: React.FC<{total: number}> = ({total}) => {
	const frame = useCurrentFrame();
	return (
		<div style={{position: 'absolute', left: 0, bottom: 0, height: 6, width: `${(frame / total) * 100}%`, background: '#f5c542', opacity: 0.85}} />
	);
};

export const Episode: React.FC<EpisodeProps> = ({timeline}) => {
	if (!timeline) {
		return <AbsoluteFill style={{background: '#111'}} />;
	}
	return (
		<AbsoluteFill style={{fontFamily: FONT_STACK}}>
			<Background variant={timeline.background} />
			{timeline.segments.map((segment, index) => (
				<Sequence key={index} from={segment.from} durationInFrames={segment.durationInFrames} premountFor={30} name={segment.label ?? `段落 ${index + 1}`}>
					<VisualCard visual={segment.visual} durationInFrames={segment.durationInFrames} />
					<Sequence from={segment.audioDelayFrames} layout="none">
						<Audio src={staticFile(segment.audio)} />
					</Sequence>
				</Sequence>
			))}
			{timeline.bgm ? <Audio src={staticFile(timeline.bgm.src)} volume={timeline.bgm.volume} loop /> : null}
			<Captions captions={timeline.captions} />
			{timeline.credit ? <Credit text={timeline.credit} /> : null}
			<Progress total={timeline.durationInFrames} />
		</AbsoluteFill>
	);
};
