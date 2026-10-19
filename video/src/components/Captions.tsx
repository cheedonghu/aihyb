import React from 'react';
import {useCurrentFrame} from 'remotion';
import type {TimelineCaption} from '../types';

export const Captions: React.FC<{captions: TimelineCaption[]}> = ({captions}) => {
	const frame = useCurrentFrame();
	const current = captions.find((c) => frame >= c.startFrame && frame < c.endFrame);
	if (!current) {
		return null;
	}
	return (
		<div style={{position: 'absolute', left: 0, right: 0, bottom: 70, display: 'flex', justifyContent: 'center'}}>
			<div
				style={{
					color: '#fff',
					fontSize: 52,
					fontWeight: 700,
					letterSpacing: 1,
					WebkitTextStroke: '8px #000',
					paintOrder: 'stroke fill',
					textShadow: '0 3px 8px rgba(0,0,0,0.5)',
					maxWidth: 1600,
					textAlign: 'center',
				}}
			>
				{current.text}
			</div>
		</div>
	);
};
