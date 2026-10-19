import React from 'react';
import {CalculateMetadataFunction, Composition, staticFile} from 'remotion';
import {Episode} from './Episode';
import type {EpisodeProps, Timeline} from './types';

const calculateMetadata: CalculateMetadataFunction<EpisodeProps> = async ({props}) => {
	const response = await fetch(staticFile(`episodes/${props.episodeId}/build/timeline.json`));
	if (!response.ok) {
		throw new Error(`Missing timeline for "${props.episodeId}". Run: python scripts/build.py ${props.episodeId}`);
	}
	const timeline = (await response.json()) as Timeline;
	return {
		durationInFrames: Math.max(1, timeline.durationInFrames),
		fps: timeline.fps,
		props: {...props, timeline},
	};
};

export const RemotionRoot: React.FC = () => (
	<Composition
		id="Episode"
		component={Episode}
		width={1920}
		height={1080}
		fps={30}
		durationInFrames={300}
		defaultProps={{episodeId: 'demo-skill-tips'}}
		calculateMetadata={calculateMetadata}
	/>
);
