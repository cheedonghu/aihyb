import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';

// Slow, deterministic drifting gradient blobs; no external wallpaper needed.
export const Background: React.FC<{variant: 'waves' | 'dark'}> = ({variant}) => {
	const frame = useCurrentFrame();
	const t = frame / 30;
	if (variant === 'dark') {
		return <AbsoluteFill style={{background: 'radial-gradient(circle at 50% 40%, #1d2433 0%, #0b0d12 70%)'}} />;
	}
	const blob = (x: number, y: number, r: number, color: string, speed: number, phase: number) => {
		const cx = x + Math.sin(t * speed + phase) * 6;
		const cy = y + Math.cos(t * speed * 0.8 + phase) * 5;
		return `radial-gradient(circle at ${cx}% ${cy}%, ${color} 0%, transparent ${r}%)`;
	};
	return (
		<AbsoluteFill
			style={{
				background: [
					blob(15, 25, 45, 'rgba(56,132,255,0.85)', 0.25, 0),
					blob(85, 20, 40, 'rgba(120,214,255,0.7)', 0.2, 1.3),
					blob(70, 85, 50, 'rgba(30,64,175,0.9)', 0.18, 2.1),
					blob(25, 90, 40, 'rgba(250,230,190,0.55)', 0.3, 3.4),
					'linear-gradient(135deg, #0f3b8c 0%, #2a76d6 45%, #9fd2ff 100%)',
				].join(', '),
			}}
		/>
	);
};
