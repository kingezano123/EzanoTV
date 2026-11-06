# Ezano TV 3D promo

`ezano-tv-3d.mp4` is a 10-second, 1280×720, 30 fps clip in a soft clay 3D style. A family watches Ezano TV in a sunny park, a kid rides past on a bike, and then the family turns to the camera and cheers as the chunky "EZANO TV" title bounces in.

- `scene.html` holds the three.js scene: sky, sun with soft shadows, ambient occlusion, rigged characters and the Ezano Home screen on the TV.
- `render.mjs` renders each frame in headless Chromium, then encodes the MP4 with ffmpeg.

## Re-render

```bash
mkdir build && cd build && npm init -y && npm i three@0.170.0
ln -s ../scene.html scene.html && mkdir fonts && cp /usr/share/fonts/opentype/inter/Inter-Black.otf fonts/
NODE_PATH=$(npm root -g) node ../render.mjs . ../ezano-tv-3d.mp4 30
```
Requires Playwright with Chromium, ffmpeg, and the Inter font.
