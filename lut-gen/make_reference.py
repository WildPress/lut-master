"""Builds the bundled reference clip: ref/street.jpg (AI-generated, no rights issues) re-encoded as
flat Sony S-Log3 / S-Gamut3.Cine footage, so the app has something to try looks on before a video is
opened, and so the emulator has realistic log footage to test with.

The photo is treated as a finished Rec.709 picture and run backwards through this project's own
S-Log3 input transform (S-curve, shoulder, Rec.709 OETF, gamut matrix, S-Log3 curve), so applying
the S-Log3 camera profile in the app gives the photo back.

Usage: python make_reference.py   (needs ffmpeg on PATH)
Writes out/reference-slog3.png and ../app/src/main/assets/reference/reference-slog3.mp4, plus
out/reference-slog3-pan.mp4 (a slow push-in, for testing playback on the emulator).
"""
import os
import subprocess

import numpy as np
from PIL import Image

import generate as G

HERE = os.path.dirname(os.path.abspath(__file__))
PROF = G.PROFILES["sony-slog3-to-rec709"]


def invert(fn, y, lo=0.0, hi=1.0, n=4096):
    """Per-channel inverse of a monotonic curve on [lo, hi] by table lookup."""
    xs = np.linspace(lo, hi, n)
    ys = fn(xs)
    return np.interp(y, ys, xs)


def display_to_slog3(disp):
    d = invert(lambda v: G.scurve(v, G.PIVOT, G.NEUTRAL_G), disp)
    d = invert(G.shoulder, np.minimum(d, 0.9999), 0.0, 1.6)
    lin = invert(G.oetf709, d, 0.0, 2.0)
    return np.clip(G.scene_codes(PROF, lin), 0.0, 1.0)


def main():
    src = np.asarray(Image.open(os.path.join(HERE, "ref", "street.jpg")).convert("RGB")).astype(float) / 255.0
    log = display_to_slog3(src)
    back = G.input_transform(PROF, log)
    err = np.abs(back - src)
    print(f"round trip through the S-Log3 input transform: mean error {err.mean() * 255:.2f}/255, "
          f"99th pct {np.percentile(err, 99) * 255:.2f}/255")

    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    png = os.path.join(HERE, "out", "reference-slog3.png")
    # 8-bit PNG for looking at; ffmpeg gets the 16-bit data as rawvideo so the encode isn't
    # quantised twice.
    Image.fromarray(np.round(log * 255).astype(np.uint8)).save(png)
    raw = np.round(log * 65535).astype("<u2").tobytes()
    h, w = log.shape[:2]

    assets = os.path.join(HERE, "..", "app", "src", "main", "assets", "reference")
    os.makedirs(assets, exist_ok=True)
    tags = ["-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"]
    base = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb48le",
            "-s", f"{w}x{h}", "-framerate", "30", "-i", "-"]
    enc = ["-c:v", "libx264", "-preset", "slow", "-crf", "14", "-pix_fmt", "yuv420p",
           "-x264-params", "keyint=30", "-movflags", "+faststart", "-an"] + tags

    # Still: one second, looped by the app.
    still = os.path.join(assets, "reference-slog3.mp4")
    subprocess.run(base + ["-vf", "loop=29:1:0,scale=out_color_matrix=bt709:out_range=tv"] + enc + [still],
                   input=raw, check=True)
    # Pan: five-second slow push-in, so frames change (for emulator playback tests).
    pan = os.path.join(HERE, "out", "reference-slog3-pan.mp4")
    zoom = (f"loop=149:1:0,scale={w * 2}:{h * 2},"
            f"zoompan=z='1+0.15*on/149':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s={w}x{h}:fps=30,"
            "scale=out_color_matrix=bt709:out_range=tv")
    subprocess.run(base + ["-vf", zoom] + enc + [pan], input=raw, check=True)
    for p in (png, still, pan):
        print(p, os.path.getsize(p) // 1024, "KB")


if __name__ == "__main__":
    main()
