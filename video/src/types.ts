export type Visual =
	| {type: 'image'; src: string; fit?: 'contain' | 'cover'; zoom?: boolean}
	| {type: 'video'; src: string; start?: number; loop?: boolean; volume?: number; fit?: 'contain' | 'cover'}
	| {type: 'title'; heading: string; sub?: string; tag?: string}
	| {type: 'points'; heading?: string; items: string[]}
	| {type: 'code'; heading?: string; code: string; language?: string}
	| {type: 'quote'; text: string; author?: string};

export type TimelineSegment = {
	from: number;
	durationInFrames: number;
	audio: string;
	audioDelayFrames: number;
	visual: Visual;
	label?: string | null;
};

export type TimelineCaption = {
	text: string;
	startFrame: number;
	endFrame: number;
};

export type Timeline = {
	id: string;
	title: string;
	fps: number;
	durationInFrames: number;
	background: 'waves' | 'dark';
	credit: string | null;
	bgm: {src: string; volume: number} | null;
	segments: TimelineSegment[];
	captions: TimelineCaption[];
};

export type EpisodeProps = {
	episodeId: string;
	timeline?: Timeline;
};
