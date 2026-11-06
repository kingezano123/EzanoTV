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

---

# Eskista dance clip (`ezano-dance-3d.mp4`)

A 5-second clip with music. An Ethiopian man and woman dance eskista (shoulder dance) in the park, then run off together.

- `avatar.js` is the reusable avatar system. Each body is one smooth skin: rounded body parts are merged and turned into a single mesh, so the joints are seamless. The body is rigged to a skeleton with smooth skin weights, and the arms use IK so the hands land where they're placed.
  - **Outfits** (`OUTFITS`): `habesha_kemis`, `habesha_libs`, `orange_jacket_jeans`, `chef_whites`, `red_overalls` and `teal_tee_red_shorts`. To add a new look, add an entry with its colours, tilet border colours, and an optional dress or tunic shell.
  - **Body types** (`BODIES`): `man` and `woman`.
- `dance.html` holds the scene and choreography: eskista shoulder moves, clapping on the beat, then a run.
- `music.py` synthesizes the original 6/8 track: kebero drum, claps, a krar-like melody in the ambassel scale, and a masinko-like drone.

```bash
python3 music.py music.wav 5
ln -s ../dance.html ../avatar.js build/
cd build && AUDIO=../music.wav PAGE=dance.html NODE_PATH=$(npm root -g) node ../render.mjs . ../ezano-dance-3d.mp4 30
```
