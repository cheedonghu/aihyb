import React from 'react';
import {Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {Video} from '@remotion/media';
import type {Visual} from '../types';

const CARD_AREA = {left: 160, top: 90, width: 1600, height: 800};
const ACCENT = '#f5c542';

const Frame: React.FC<{children: React.ReactNode; transparent?: boolean}> = ({children, transparent}) => {
	const frame = useCurrentFrame();
	// Hard cut with a subtle settle; never start transparent, or the cut flashes empty background.
	const enter = interpolate(frame, [0, 8], [0, 1], {extrapolateRight: 'clamp'});
	return (
		<div
			style={{
				position: 'absolute',
				...CARD_AREA,
				display: 'flex',
				alignItems: 'center',
				justifyContent: 'center',
				transform: `scale(${0.98 + 0.02 * enter})`,
			}}
		>
			<div
				style={{
					maxWidth: '100%',
					maxHeight: '100%',
					borderRadius: 18,
					overflow: 'hidden',
					boxShadow: transparent ? undefined : '0 24px 60px rgba(0,0,0,0.45)',
					display: 'flex',
				}}
			>
				{children}
			</div>
		</div>
	);
};

const Panel: React.FC<{children: React.ReactNode}> = ({children}) => (
	<Frame>
		<div
			style={{
				width: CARD_AREA.width,
				height: CARD_AREA.height,
				background: 'rgba(12,16,24,0.92)',
				color: '#f2f4f8',
				padding: '70px 90px',
				boxSizing: 'border-box',
				display: 'flex',
				flexDirection: 'column',
				justifyContent: 'center',
			}}
		>
			{children}
		</div>
	</Frame>
);

const Heading: React.FC<{text?: string}> = ({text}) =>
	text ? <div style={{fontSize: 56, fontWeight: 800, marginBottom: 40, color: ACCENT}}>{text}</div> : null;

const ImageVisual: React.FC<{visual: Extract<Visual, {type: 'image'}>; durationInFrames: number}> = ({visual, durationInFrames}) => {
	const frame = useCurrentFrame();
	const scale = visual.zoom === false ? 1 : interpolate(frame, [0, durationInFrames], [1, 1.06]);
	return (
		<Frame>
			<Img
				src={staticFile(visual.src)}
				style={{
					maxWidth: CARD_AREA.width,
					maxHeight: CARD_AREA.height,
					objectFit: visual.fit ?? 'contain',
					transform: `scale(${scale})`,
				}}
			/>
		</Frame>
	);
};

const VideoVisual: React.FC<{visual: Extract<Visual, {type: 'video'}>}> = ({visual}) => {
	const {fps} = useVideoConfig();
	return (
		<Frame>
			<Video
				src={staticFile(visual.src)}
				trimBefore={Math.round((visual.start ?? 0) * fps)}
				loop={visual.loop}
				volume={visual.volume ?? 0}
				objectFit={visual.fit ?? 'contain'}
				style={{maxWidth: CARD_AREA.width, maxHeight: CARD_AREA.height}}
			/>
		</Frame>
	);
};

const PointsVisual: React.FC<{visual: Extract<Visual, {type: 'points'}>}> = ({visual}) => {
	const frame = useCurrentFrame();
	return (
		<Panel>
			<Heading text={visual.heading} />
			{visual.items.map((item, i) => {
				const appear = interpolate(frame, [6 + i * 10, 14 + i * 10], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
				return (
					<div key={i} style={{display: 'flex', alignItems: 'baseline', gap: 24, fontSize: 44, lineHeight: 1.5, marginBottom: 18, opacity: appear}}>
						<span style={{color: ACCENT, fontWeight: 800, minWidth: 48}}>{i + 1}.</span>
						<span>{item}</span>
					</div>
				);
			})}
		</Panel>
	);
};

const CodeVisual: React.FC<{visual: Extract<Visual, {type: 'code'}>}> = ({visual}) => (
	<Panel>
		<Heading text={visual.heading} />
		<pre
			style={{
				margin: 0,
				padding: '36px 44px',
				background: '#05070b',
				borderRadius: 12,
				fontFamily: 'Consolas, "Cascadia Mono", monospace',
				fontSize: 34,
				lineHeight: 1.5,
				color: '#cfe3ff',
				whiteSpace: 'pre-wrap',
				overflow: 'hidden',
			}}
		>
			{visual.code}
		</pre>
	</Panel>
);

const TitleVisual: React.FC<{visual: Extract<Visual, {type: 'title'}>}> = ({visual}) => (
	<Frame transparent>
		<div style={{textAlign: 'center', color: '#fff', textShadow: '0 6px 30px rgba(0,0,0,0.5)'}}>
			{visual.tag ? (
				<div style={{display: 'inline-block', background: ACCENT, color: '#111', fontSize: 34, fontWeight: 800, padding: '8px 24px', borderRadius: 8, marginBottom: 36}}>
					{visual.tag}
				</div>
			) : null}
			<div style={{fontSize: 96, fontWeight: 900, lineHeight: 1.2}}>{visual.heading}</div>
			{visual.sub ? <div style={{fontSize: 44, marginTop: 30, opacity: 0.9}}>{visual.sub}</div> : null}
		</div>
	</Frame>
);

const QuoteVisual: React.FC<{visual: Extract<Visual, {type: 'quote'}>}> = ({visual}) => (
	<Panel>
		<div style={{fontSize: 120, color: ACCENT, lineHeight: 0.6, fontFamily: 'Georgia, serif'}}>“</div>
		<div style={{fontSize: 52, lineHeight: 1.5, fontWeight: 600}}>{visual.text}</div>
		{visual.author ? <div style={{fontSize: 34, marginTop: 36, opacity: 0.7}}>—— {visual.author}</div> : null}
	</Panel>
);

export const VisualCard: React.FC<{visual: Visual; durationInFrames: number}> = ({visual, durationInFrames}) => {
	switch (visual.type) {
		case 'image':
			return <ImageVisual visual={visual} durationInFrames={durationInFrames} />;
		case 'video':
			return <VideoVisual visual={visual} />;
		case 'points':
			return <PointsVisual visual={visual} />;
		case 'code':
			return <CodeVisual visual={visual} />;
		case 'title':
			return <TitleVisual visual={visual} />;
		case 'quote':
			return <QuoteVisual visual={visual} />;
		default:
			return null;
	}
};
