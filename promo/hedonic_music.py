"""Original 3-minute background score for "Hedonic Adaptation" (no voice).

Built on the Ethiopian tizita mode: tizita major (C D E G A) for the warm
scenes, tizita minor (C D Eb G Ab) for the "treadmill" tension in scene 3.
Soft piano arpeggios, a krar-like pluck melody, a slow pad, gentle kebero
pulse and shaker. Everything is synthesized here with numpy.
Usage: python3 hedonic_music.py out.wav [seconds]
"""
import sys, wave
import numpy as np

SR = 44100
DUR = float(sys.argv[2]) if len(sys.argv) > 2 else 180.0
N = int(SR * (DUR + 3))
L = np.zeros(N); R = np.zeros(N)
rng = np.random.default_rng(3)
BPM = 76; BEAT = 60 / BPM; BAR = 4 * BEAT

def midi(m): return 440 * 2 ** ((m - 69) / 12)

def put(sig, t, gain=1., pan=0.):
    i = int(t * SR)
    if i >= N or gain <= 0: return
    n = min(len(sig), N - i)
    L[i:i + n] += sig[:n] * gain * np.sqrt(.5 * (1 - pan))
    R[i:i + n] += sig[:n] * gain * np.sqrt(.5 * (1 + pan))

def piano(f, dur=2.2):
    t = np.arange(int(SR * dur)) / SR
    s = np.zeros_like(t)
    for k in range(1, 7):
        s += np.sin(2 * np.pi * f * k * (1 + .0004 * k * k) * t) / k ** 1.6 * np.exp(-t * (1.1 + k * .9))
    return s * np.minimum(1, t / .004)

def pluck(f, dur=1.4):   # krar-like
    t = np.arange(int(SR * dur)) / SR
    s = np.zeros_like(t)
    for k in range(1, 9):
        s += np.sin(2 * np.pi * f * k * t + k) / k ** 1.2 * np.exp(-t * (2.2 + k * 1.6))
    return s * np.minimum(1, t / .002)

def pad(f, dur):
    t = np.arange(int(SR * (dur + 1.6))) / SR
    env = np.minimum(1, t / 1.1) * np.where(t > dur, np.exp(-(t - dur) / .7), 1)
    s = np.zeros_like(t)
    for k, a in [(1, 1), (2, .45), (3, .2), (4, .1)]:
        for det in (-.0025, .0025):
            s += a * np.sin(2 * np.pi * f * k * (1 + det) * t + det * 900)
    return s * env * (1 + .15 * np.sin(2 * np.pi * .23 * t))

def bass(f, dur=1.8):
    t = np.arange(int(SR * dur)) / SR
    return (np.sin(2 * np.pi * f * t) + .3 * np.sin(4 * np.pi * f * t)) * np.exp(-t * 1.6) * np.minimum(1, t / .01)

def kebero(low=True):
    n = int(SR * (.4 if low else .15)); t = np.arange(n) / SR
    f0 = 80 if low else 190
    ph = 2 * np.pi * np.cumsum(f0 * (1 + 1.2 * np.exp(-t * 35))) / SR
    s = np.sin(ph) * np.exp(-t / (.14 if low else .05))
    nz = np.convolve(rng.normal(0, 1, n), np.ones(5) / 5, 'same') * np.exp(-t / .012)
    return s + nz * .3

def shaker():
    n = int(SR * .06); t = np.arange(n) / SR
    s = rng.normal(0, 1, n) * np.minimum(1, t / .006) * np.exp(-t / .018)
    return s - np.convolve(s, np.ones(8) / 8, 'same')

MAJ = {'C6': [48, 52, 55, 57], 'Am7': [45, 48, 52, 55], 'Dsus': [50, 55, 57, 60], 'G6': [43, 50, 55, 57]}
MIN = {'Cm': [48, 51, 55, 60], 'Abma7': [44, 48, 51, 55], 'Gsus': [43, 48, 50, 55], 'Cm9': [48, 51, 55, 62]}
PROG_MAJ = ['C6', 'Am7', 'Dsus', 'G6']
PROG_MIN = ['Cm', 'Abma7', 'Gsus', 'Cm9']
SCALE_MAJ = [60, 62, 64, 67, 69, 72, 74, 76, 79]
SCALE_MIN = [60, 62, 63, 67, 68, 72, 74, 75, 79]

def section(t):
    if t < 45: return 'wonder'
    if t < 90: return 'curious'
    if t < 135: return 'tension'
    return 'warm'

nbars = int(np.ceil(DUR / BAR))
mel_idx = 4
for b in range(nbars):
    t0 = b * BAR
    sec = section(t0 + .01)
    minor = sec == 'tension'
    chord = (MIN if minor else MAJ)[(PROG_MIN if minor else PROG_MAJ)[b % 4]]
    scale = SCALE_MIN if minor else SCALE_MAJ
    intro = min(1, (t0 + .01) / 6)                       # gentle start
    ending = 1 if t0 < DUR - 8 else max(0, (DUR - t0) / 8)
    lvl = intro * ending
    # pad + bass
    for m in chord: put(pad(midi(m + 12), BAR), t0, .05 * lvl, (m % 5 - 2) * .2)
    put(bass(midi(chord[0] - 12)), t0, .32 * lvl)
    if sec in ('curious', 'tension', 'warm'): put(bass(midi(chord[0] - 12), 1.2), t0 + 2 * BEAT, .2 * lvl)
    # piano arpeggio in eighths
    arp = [chord[0] + 12, chord[1] + 12, chord[2] + 12, chord[3] + 12, chord[2] + 24, chord[3] + 12, chord[1] + 12, chord[2] + 12]
    density = {'wonder': [0, 2, 4, 6], 'curious': range(8), 'tension': range(8), 'warm': range(8)}[sec]
    for k in density:
        put(piano(midi(arp[k]), 1.8), t0 + k * BEAT / 2, (.11 if k % 2 == 0 else .075) * lvl, -.3 + .6 * (k / 7))
    # krar-like melody: short pentatonic phrases, every other bar
    if b % 2 == 1 or sec == 'warm':
        rhythm = [0, 1, 1.5, 2, 3] if b % 4 == 1 else [0, .5, 1, 2.5]
        for r in rhythm:
            mel_idx = int(np.clip(mel_idx + rng.choice([-2, -1, -1, 1, 1, 2]), 0, len(scale) - 1))
            put(pluck(midi(scale[mel_idx]), 1.3), t0 + r * BEAT, .16 * lvl, .25)
    # percussion
    if sec in ('curious', 'tension', 'warm'):
        pg = {'curious': .5, 'tension': .7, 'warm': .45}[sec]
        for k in range(4):
            put(kebero(k % 2 == 0), t0 + k * BEAT, (.5 if k % 2 == 0 else .3) * pg * lvl)
        for k in range(8):
            put(shaker(), t0 + k * BEAT / 2, (.12 if k % 2 else .08) * pg * lvl, .4)
        if sec == 'tension':                                # ticking, like steps on a treadmill
            for k in range(16): put(pluck(midi(84), .12), t0 + k * BEAT / 4, .025 * lvl, -.5)

# final ringing chord
for m in [48, 55, 60, 64, 67, 69]:
    put(piano(midi(m + 12), 4.5), DUR - 4.2, .12)
    put(pad(midi(m), 3.0), DUR - 4.2, .04)

# reverb: FFT convolution with a decaying noise impulse
def reverb(x, secs=2.2):
    n = int(SR * secs); t = np.arange(n) / SR
    ir = rng.normal(0, 1, n) * np.exp(-t / .55); ir[0] = 0
    size = 1 << int(np.ceil(np.log2(len(x) + n)))
    y = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)[:len(x)]
    return y / np.max(np.abs(y) + 1e-9)
wl, wr = reverb(L), reverb(R)
L = L + wl * np.max(np.abs(L)) * .32
R = R + wr * np.max(np.abs(R)) * .32

out = np.stack([L, R], 1)[:int(SR * DUR)]
fade = int(SR * 3); out[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 1.5
out = np.tanh(out / np.max(np.abs(out)) * 1.3) / np.tanh(1.3) * .9
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((out * 32767).astype('<i2').tobytes())
print('wrote', sys.argv[1], DUR, 's')
