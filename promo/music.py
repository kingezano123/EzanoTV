"""Original 5-second Ethiopian-style groove (6/8 eskista feel), synthesized from scratch.

kebero-style hand drum, hand claps, shaker, a krar-like plucked melody in the
ambassel scale (D Eb G A Bb), and a soft masinko-like bowed drone.
Usage: python3 music.py out.wav [seconds]
"""
import sys, wave
import numpy as np

SR = 44100
DUR = float(sys.argv[2]) if len(sys.argv) > 2 else 5.0
N = int(SR * (DUR + 0.6))
BAR = 1.0               # one 6/8 bar per second (dotted quarter = 120 bpm)
E = BAR / 6             # eighth note
rng = np.random.default_rng(7)
mix = np.zeros(N)

def add(sig, t, gain=1.0, pan=0.0):
    i = int(t * SR)
    if i >= N: return
    n = min(len(sig), N - i)
    mix[i:i + n] += sig[:n] * gain

def env(n, attack, decay):
    t = np.arange(n) / SR
    return np.minimum(1, t / max(attack, 1e-4)) * np.exp(-t / decay)

def kebero(low=True):
    n = int(SR * (0.45 if low else 0.18))
    t = np.arange(n) / SR
    f0 = 92 if low else 210
    pitch = f0 * (1 + (1.6 if low else .8) * np.exp(-t * 38))
    body = np.sin(2 * np.pi * np.cumsum(pitch) / SR) * env(n, .001, .16 if low else .055)
    slap = rng.normal(0, 1, n) * env(n, .0005, .012 if low else .02)
    slap = np.convolve(slap, np.ones(6) / 6, 'same')
    return body * (1.0 if low else .7) + slap * (.35 if low else .55)

def clap():
    n = int(SR * .2); out = np.zeros(n)
    for k, d in enumerate([0, .009, .018, .026]):
        i = int(d * SR); m = n - i
        burst = rng.normal(0, 1, m) * env(m, .0003, .006 if k < 3 else .05)
        out[i:] += burst
    out = out - np.convolve(out, np.ones(30) / 30, 'same')   # crude high-pass
    return out * .5

def shaker():
    n = int(SR * .07)
    s = rng.normal(0, 1, n) * env(n, .008, .02)
    return s - np.convolve(s, np.ones(8) / 8, 'same')

def krar(freq, dur=.5, bright=.5):
    # Karplus-Strong plucked string
    n = int(SR * dur); p = int(SR / freq)
    buf = rng.uniform(-1, 1, p); out = np.zeros(n)
    for i in range(n):
        out[i] = buf[i % p]
        buf[i % p] = (bright * buf[i % p] + (1 - bright) * buf[(i + 1) % p]) * .996 if i >= p else buf[i % p]
        if i >= p - 1:
            j = i % p; buf[j] = .5 * (buf[j] + buf[(j + 1) % p]) * .997
    return out * env(n, .002, dur * .6)

D4 = 293.66
NOTES = {'D4': D4, 'Eb4': D4 * 2 ** (1 / 12), 'G4': D4 * 2 ** (5 / 12), 'A4': D4 * 2 ** (7 / 12),
         'Bb4': D4 * 2 ** (8 / 12), 'D5': D4 * 2, 'A3': D4 * 2 ** (-5 / 12), 'D3': D4 / 2, 'G3': D4 * 2 ** (-7 / 12)}
melody = [
    'A4 A4 Bb4 A4 G4 -',
    'G4 A4 G4 Eb4 D4 -',
    'A4 A4 Bb4 D5 Bb4 A4',
    'G4 A4 G4 Eb4 D4 D4',
    'D4 Eb4 G4 A4 D4 -',
]
bass = ['D3', 'D3', 'G3', 'D3', 'D3']

bars = int(np.ceil(DUR / BAR))
for b in range(bars):
    t0 = b * BAR
    # kebero: DUM . tek DUM tek tek
    for k, kind in enumerate(['D', None, 'T', 'D', 'T', 't']):
        if kind: add(kebero(kind == 'D'), t0 + k * E, 1.0 if kind != 't' else .55)
    for k in (0, 3): add(clap(), t0 + k * E + .004, .55)
    for k in range(6): add(shaker(), t0 + k * E + E * .5, .22 if k % 3 else .3)
    for k, nm in enumerate(melody[b % len(melody)].split()):
        if nm != '-': add(krar(NOTES[nm], .42, .55), t0 + k * E, .55)
    add(krar(NOTES[bass[b % len(bass)]], .9, .7), t0, .5)
    add(krar(NOTES['A3'], .5, .7), t0 + 3 * E, .3)

# masinko-like bowed drone with vibrato
t = np.arange(N) / SR
vib = 1 + .006 * np.sin(2 * np.pi * 5.5 * t)
ph = 2 * np.pi * np.cumsum(NOTES['D4'] * vib) / SR
drone = sum(np.sin(ph * h) / h ** 1.3 for h in range(1, 8))
drone *= np.minimum(1, t / .6) * .07
mix += drone

# gentle room reverb (a few feedback delays)
wet = np.zeros(N)
for d, g in [(.031, .35), (.047, .3), (.071, .25), (.113, .2)]:
    k = int(d * SR); wet[k:] += mix[:-k] * g
mix = mix + wet * .35

mix = mix[:int(SR * DUR)]
fade = int(SR * .35); mix[-fade:] *= np.linspace(1, 0, fade)
mix[:200] *= np.linspace(0, 1, 200)
mix = np.tanh(mix / np.max(np.abs(mix)) * 1.6) * .89

stereo = np.stack([mix, np.roll(mix, 9)], 1)
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((stereo * 32767).astype('<i2').tobytes())
print('wrote', sys.argv[1], DUR, 's')
