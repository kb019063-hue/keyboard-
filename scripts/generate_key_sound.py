#!/usr/bin/env python3
"""Synthesise the key-press click into res/raw/key_click.wav.

A recorded sample would be a blob nobody can reproduce or tweak; this script is the source of
truth, the same arrangement as the launcher icon, the dictionaries and the settings search index.

The click is a ~45 ms burst: broadband noise for the "tick" of plastic against plastic, blended
with a short 1.9 kHz body tone for the "thock", both under a fast-attack exponential decay so it
reads as a mechanical key and not as a beep. Peak-normalised to 0.7 so the in-app volume slider
has headroom, 22 050 Hz mono 16-bit -- plenty for a transient and about 2 KB on disk.

Usage: python3 scripts/generate_key_sound.py
"""
import math
import os
import random
import struct
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "res", "raw", "key_click.wav")

RATE = 22050
DURATION = 0.045
DECAY_MS = 8.0        # exponential decay of the noise tick
BODY_HZ = 1900.0      # the resonant "thock" of the keycap
BODY_DECAY_MS = 4.0   # the body tone dies faster than the tick
PEAK = 0.7


def main():
    random.seed(20261022)  # fixed seed: the same click every time, so the asset is reproducible
    n = int(RATE * DURATION)
    tau = DECAY_MS / 1000.0
    tau_body = BODY_DECAY_MS / 1000.0
    samples = []
    for i in range(n):
        t = i / RATE
        attack = min(1.0, t / 0.001)              # 1 ms linear attack avoids a startup pop
        env = attack * math.exp(-t / tau)
        body = math.sin(2 * math.pi * BODY_HZ * t) * math.exp(-t / tau_body)
        s = env * (0.62 * (random.random() * 2 - 1) + 0.38 * body)
        samples.append(s)
    peak = max(abs(s) for s in samples) or 1.0
    pcm = b"".join(struct.pack("<h", int(round(s / peak * PEAK * 32767))) for s in samples)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with wave.open(OUT, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)
    print(f"  wrote {os.path.normpath(OUT)}  {n} samples, {os.path.getsize(OUT)} bytes")


if __name__ == "__main__":
    main()
