Synthetic test fixtures generated locally with FFmpeg (not bundled with the app):
ffmpeg -f lavfi -i sine=frequency=440:duration=2:sample_rate=48000 -ac 2 -c:a aac -b:a 128k export-aac.m4a
ffmpeg -f lavfi -i sine=frequency=440:duration=2:sample_rate=48000 -ac 2 -c:a libopus -b:a 128k export-opus.webm
These are generated sine tones, not recordings obtained from YouTube.
