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

---

# Hedonic Adaptation, an Amharic 3D explainer (`hedonic-adaptation-am.mp4`)

A 3-minute, 24 fps animation. All on-screen text is in Amharic, there is no voice, and an original background score plays throughout.

| Time | Scene | What you see |
|---|---|---|
| 0:00–0:45 | ክፍል ፩ · የህልም መኪና | Saving coins in a ቁጠባ jar, driving the new car home, showing it to friends, a calendar flipping to መጋቢት ("ከ6 ወር በኋላ…"), then a cloudy, ordinary day and a thought bubble with the next car |
| 0:45–1:30 | ክፍል ፪ · የአእምሮ ምስጢር | Pedestals (መኪና, ቤት, ደመወዝ, ስኬት), a brain with gears, the "ሄዶኒክ አዳፕቴሽን" definition board, a live happiness graph with spikes that fade back to "አዲሱ የተለመደ", and a stair where ቅንጦት turns into የተለመደ |
| 1:30–2:15 | ክፍል ፫ · ያልተጠበቀው እውነት | A finish line (የመጨረሻ መስመር) that keeps moving away, the +$10,000 coin, income vs expectations bars, a treadmill with the እርካታ heart just out of reach, and stepping off |
| 2:15–3:00 | ክፍል ፬ · አምስት ትምህርቶች | Lesson tiles ፩–፭, appreciating what you have, a gratitude journal, a coffee ceremony (ቡና) with friends, two staircases (yours vs others'), planting a tree, and a sunset on the porch |

- `hedonic.html` holds the scenes, props, Amharic captions (`CAPS`), lesson titles (`TIPS`) and camera work.
- `hedonic_music.py` makes the 3-minute score in the tizita mode: major for the warm scenes, minor for the treadmill tension. It has piano, a krar-like melody, a pad and a soft kebero.
- `fonts/` holds Noto Sans and Noto Serif Ethiopic (SIL Open Font License).
- The characters come from `avatar.js`: lighter skin tones, and hands with five fingers that can curl.

```bash
python3 hedonic_music.py hedonic.wav 180
cd build && ln -s ../hedonic.html ../avatar.js . && cp ../fonts/*.woff2 fonts/
WORKERS=4 AUDIO=../hedonic.wav PAGE=hedonic.html NODE_PATH=$(npm root -g) node ../render.mjs . ../hedonic-adaptation-am.mp4 24
```
