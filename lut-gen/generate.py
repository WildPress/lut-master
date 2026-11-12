import glob
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

N = 33
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
OUT_IN = os.path.join(OUT, "input")
OUT_LOOK = os.path.join(OUT, "looks")

LUMA = np.array([0.2126, 0.7152, 0.0722])
PIVOT = 0.409  # Rec.709 OETF of 18% grey
NEUTRAL_G = 1.15  # gentle S-curve baked into every input transform
KNEE = 0.85  # highlight shoulder knee (display-referred)

# ----------------------------------------------------------------------------
# Colour primaries (CIE xy) -> matrices
# ----------------------------------------------------------------------------
D65 = (0.3127, 0.3290)
P709 = [(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)]
P2020 = [(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)]  # F-Gamut, N-Log gamut (manufacturer PDFs)
PVGAMUT = [(0.730, 0.280), (0.165, 0.840), (0.100, -0.030)]  # Panasonic V-Log/V-Gamut manual 4.1
PDGAMUT = [(0.71, 0.31), (0.21, 0.88), (0.09, -0.08)]  # DJI D-Log/D-Gamut white paper
PSGAMUT3CINE = [(0.766, 0.275), (0.225, 0.800), (0.089, -0.087)]  # Sony S-Gamut3.Cine


def xy_to_xyz(x, y):
    return np.array([x / y, 1.0, (1 - x - y) / y])


def rgb_to_xyz(prim, white=D65):
    P = np.array([xy_to_xyz(*p) for p in prim]).T
    S = np.linalg.solve(P, xy_to_xyz(*white))
    return P * S


def derive_to_709(prim):
    return np.linalg.inv(rgb_to_xyz(P709)) @ rgb_to_xyz(prim)


# Matrices published by the manufacturer (used when available)
M_SGAMUT3CINE_PUB = np.array([
    [1.6269474099, -0.5401385150, -0.0868088949],
    [-0.1785155272, 1.4179409274, -0.2394254002],
    [-0.0444361150, -0.1959199662, 1.2403560812]])
M_VGAMUT_PUB = np.array([
    [1.806576, -0.695697, -0.110879],
    [-0.170090, 1.305955, -0.135865],
    [-0.025206, -0.154468, 1.179674]])
M_DGAMUT_PUB = np.array([
    [1.6746, -0.5797, -0.0949],
    [-0.0981, 1.3340, -0.2359],
    [-0.0410, -0.2430, 1.2840]])


# ----------------------------------------------------------------------------
# Log curves. decode: code 0..1 (10-bit/1023) -> scene reflection (0.18 = mid grey)
#             encode: scene reflection -> code 0..1
# ----------------------------------------------------------------------------
def _pos(y, floor=1e-12):
    return np.maximum(y, floor)


BP_SLOG3 = 171.2102946929


def slog3_decode(x):
    c = np.asarray(x, dtype=np.float64) * 1023.0
    hi = np.power(10.0, (c - 420.0) / 261.5) * 0.19 - 0.01
    lo = (c - 95.0) * 0.01125 / (BP_SLOG3 - 95.0)
    return np.where(c >= BP_SLOG3, hi, lo)


def slog3_encode(y):
    y = np.asarray(y, dtype=np.float64)
    hi = (420.0 + np.log10(_pos(y + 0.01) / 0.19) * 261.5) / 1023.0
    lo = (y * (BP_SLOG3 - 95.0) / 0.01125 + 95.0) / 1023.0
    return np.clip(np.where(y >= 0.01125, hi, lo), 0, 1)


def vlog_decode(x):
    x = np.asarray(x, dtype=np.float64)
    c, d, b = 0.241514, 0.598206, 0.00873
    return np.where(x < 0.181, (x - 0.125) / 5.6, np.power(10.0, (x - d) / c) - b)


def vlog_encode(y):
    y = np.asarray(y, dtype=np.float64)
    c, d, b = 0.241514, 0.598206, 0.00873
    hi = c * np.log10(_pos(y + b)) + d
    return np.clip(np.where(y < 0.01, 5.6 * y + 0.125, hi), 0, 1)


def _fuji(a, b, c, d, e, f, cut1, cut2):
    def dec(x):
        x = np.asarray(x, dtype=np.float64)
        return np.where(x >= cut2, np.power(10.0, (x - d) / c) / a - b / a, (x - f) / e)

    def enc(y):
        y = np.asarray(y, dtype=np.float64)
        hi = c * np.log10(_pos(a * y + b)) + d
        return np.clip(np.where(y >= cut1, hi, e * y + f), 0, 1)

    return dec, enc


flog_decode, flog_encode = _fuji(0.555556, 0.009468, 0.344676, 0.790453, 8.735631, 0.092864,
                                 0.00089, 0.100537775223865)
flog2_decode, flog2_encode = _fuji(5.555556, 0.064829, 0.245281, 0.384316, 8.799461, 0.092864,
                                   0.000889, 0.100686685370811)


def nlog_decode(x):
    c = np.asarray(x, dtype=np.float64) * 1023.0
    return np.where(c < 452.0, np.power(c / 650.0, 3) - 0.0075, np.exp((c - 619.0) / 150.0))


def nlog_encode(y):
    y = np.asarray(y, dtype=np.float64)
    lo = 650.0 * np.cbrt(np.maximum(y + 0.0075, 0.0))
    hi = 150.0 * np.log(_pos(y)) + 619.0
    return np.clip(np.where(y < 0.328, lo, hi) / 1023.0, 0, 1)


def dlog_decode(x):
    x = np.asarray(x, dtype=np.float64)
    return np.where(x <= 0.14, (x - 0.0929) / 6.025,
                    (np.power(10.0, 3.89616 * x - 2.27752) - 0.0108) / 0.9892)


def dlog_encode(y):
    y = np.asarray(y, dtype=np.float64)
    hi = np.log10(_pos(y * 0.9892 + 0.0108)) * 0.256663 + 0.584555
    return np.clip(np.where(y <= 0.0078, 6.025 * y + 0.0929, hi), 0, 1)


# name -> (decode, encode, matrix-to-709, source note, published anchors {reflection: 10-bit code})
PROFILES = {
    "sony-slog3-to-rec709": dict(
        title="Sony S-Log3 / S-Gamut3.Cine", dec=slog3_decode, enc=slog3_encode, M=M_SGAMUT3CINE_PUB,
        prim=PSGAMUT3CINE, anchors={0.0: 95, 0.18: 420}, label="S-Log3",
        source="Sony S-Log3 formula and S-Gamut3.Cine matrix (as commonly published by Sony, not re-checked against Sony's own document in this project)"),
    "panasonic-vlog-to-rec709": dict(
        title="Panasonic V-Log / V-Gamut", dec=vlog_decode, enc=vlog_encode, M=M_VGAMUT_PUB,
        prim=PVGAMUT, anchors={0.0: 128, 0.18: 433, 0.90: 602}, label="V-Log",
        source="Panasonic V-Log/V-Gamut Reference Manual Rev.1.0 (2014-11-28), sections 3, 4.1, 4.2"),
    "fujifilm-flog-to-rec709": dict(
        title="Fujifilm F-Log / F-Gamut (BT.2020)", dec=flog_decode, enc=flog_encode,
        M=derive_to_709(P2020), prim=P2020, anchors={0.0: 95, 0.18: 470, 0.90: 705}, label="F-Log",
        source="Fujifilm F-Log Data Sheet Ver.1.1, sections 2-3 (gamut is BT.2020, matrix derived from its primaries)"),
    "fujifilm-flog2-to-rec709": dict(
        title="Fujifilm F-Log2 / F-Gamut (BT.2020)", dec=flog2_decode, enc=flog2_encode,
        M=derive_to_709(P2020), prim=P2020, anchors={0.0: 95, 0.18: 400, 0.90: 570}, label="F-Log2",
        source="Fujifilm F-Log2 Data Sheet Ver.1.0, sections 2-3 (gamut is BT.2020, matrix derived from its primaries)"),
    "nikon-nlog-to-rec709": dict(
        title="Nikon N-Log (BT.2020)", dec=nlog_decode, enc=nlog_encode, M=derive_to_709(P2020),
        prim=P2020, anchors={}, label="N-Log",
        source="Nikon N-Log Specification 1.0.0 (Sc/Jp editions, 2018-09-01), sections 2-3; log() read as natural log"),
    "dji-dlog-to-rec709": dict(
        title="DJI D-Log / D-Gamut (Zenmuse X7/X9)", dec=dlog_decode, enc=dlog_encode, M=M_DGAMUT_PUB,
        prim=PDGAMUT, anchors={0.0: 95, 0.18: 408, 0.90: 586}, label="D-Log",
        source="DJI D-Log/D-Gamut White Paper, Zenmuse X9 (2022.02), pages 3-4 and 7; consumer D-Log M may differ"),
}


# ----------------------------------------------------------------------------
# Display transforms and colour helpers
# ----------------------------------------------------------------------------
def oetf709(L):
    L = np.clip(L, 0.0, None)
    return np.where(L < 0.018, 4.5 * L, 1.099 * np.power(L, 0.45) - 0.099)


def shoulder(x, k=KNEE):
    """Soft highlight roll-off above the knee; identity below, asymptote 1.0."""
    return np.where(x <= k, x, k + (1 - k) * (1 - np.exp(-(x - k) / (1 - k))))


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def luma(x):
    return x @ LUMA


def scurve(x, p=PIVOT, g=1.15):
    x = np.clip(x, 0.0, 1.0)
    lo = p * np.power(np.clip(x / p, 0, 1), g)
    hi = 1 - (1 - p) * np.power(np.clip((1 - x) / (1 - p), 0, 1), g)
    return np.where(x < p, lo, hi)


def rgb2hsv(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = rgb.max(-1)
    mn = rgb.min(-1)
    d = mx - mn
    dd = np.where(d > 1e-9, d, 1.0)
    h = np.select([d <= 1e-9, mx == r, mx == g],
                  [0.0, ((g - b) / dd) % 6.0, (b - r) / dd + 2.0], (r - g) / dd + 4.0) * 60.0
    s = np.where(mx > 1e-9, d / np.where(mx > 1e-9, mx, 1.0), 0.0)
    return np.stack([h % 360.0, s, mx], -1)


def hsv2rgb(hsv):
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    c = v * s
    hp = (h / 60.0) % 6.0
    x = c * (1 - np.abs(hp % 2.0 - 1))
    m = v - c
    z = np.zeros_like(c)
    sec = hp.astype(int)
    conds = [sec == i for i in range(6)]
    r = np.select(conds, [c, x, z, z, x, c], z)
    g = np.select(conds, [x, c, c, x, z, z], z)
    b = np.select(conds, [z, z, x, c, c, x], z)
    return np.stack([r + m, g + m, b + m], -1)


def hue_adjust(rgb, adjustments):
    hsv = rgb2hsv(rgb)
    h, s, v = hsv[..., 0].copy(), hsv[..., 1].copy(), hsv[..., 2].copy()
    gate = smoothstep(0.04, 0.3, hsv[..., 1])
    for center, width, smult, hshift, vmult in adjustments:
        dh = (hsv[..., 0] - center + 180.0) % 360.0 - 180.0
        w = np.exp(-0.5 * (dh / width) ** 2) * gate
        h += w * hshift
        s *= 1 + (smult - 1) * w
        v *= 1 + (vmult - 1) * w
    return hsv2rgb(np.stack([h % 360.0, np.clip(s, 0, 1), np.clip(v, 0, 1)], -1))


def grade(x, g=1.15, p=PIVOT, sat=1.0, lift=(0, 0, 0), crush=0.0, gain=(1, 1, 1),
          gamma=(1, 1, 1), sh=(0, 0, 0), mid=(0, 0, 0), hi=(0, 0, 0), hue=None, mono=None):
    """Look on display-referred Rec.709. g is the contrast of the finished look relative to the
    plain neutral input transform (which already carries NEUTRAL_G), so it is divided down."""
    g = g / NEUTRAL_G
    x = np.clip(x, 0.0, 1.0)
    if mono is not None:
        y = x @ np.array(mono)
        x = np.stack([y, y, y], -1)
    if hue:
        x = hue_adjust(x, hue)
    x = scurve(x, p, g)
    if sat != 1.0:
        y = luma(x)[..., None]
        x = y + (x - y) * sat
    x = np.clip(x, 0, 1)
    x = np.power(x, np.array(gamma))
    x = x * np.array(gain)
    y = luma(np.clip(x, 0, 1))
    ws = 1 - smoothstep(0.0, 0.5, y)
    wh = smoothstep(0.45, 1.0, y)
    wm = np.clip(1 - ws - wh, 0, 1)
    x = (x + ws[..., None] * np.array(sh) + wm[..., None] * np.array(mid)
         + wh[..., None] * np.array(hi))
    x = np.clip(x, 0, 1)
    x = x + np.array(lift) * (1 - x)
    if crush:
        x = (x - crush) / (1 - crush)
    return np.clip(x, 0.0, 1.0)


def tint_black(x, floor):
    y = luma(x)
    w = (1 - smoothstep(0.0, 0.5, y))[..., None]
    return np.clip(x + np.array(floor) * (1 - x) * w, 0, 1)


LOOKS = {
    "kodak-portra-400-style": dict(
        title="Kodak Portra 400 style (approx)",
        fn=lambda x: grade(x, g=1.03, sat=0.92, lift=(0.036, 0.033, 0.03), gain=(1.0, 0.99, 0.95),
                           hi=(0.012, 0.006, -0.004),
                           hue=[(25, 25, 1.06, 0, 1.0), (120, 35, 0.68, 12, 0.97),
                                (200, 40, 0.85, 0, 1.0), (225, 30, 0.88, 0, 1.0)])),
    "kodak-ektar-100-style": dict(
        title="Kodak Ektar 100 style (approx)",
        fn=lambda x: grade(x, g=1.28, sat=1.25, crush=0.012, gain=(1.0, 0.995, 0.985),
                           hue=[(0, 25, 1.2, 0, 1.0), (225, 35, 1.15, 0, 0.98), (120, 40, 1.1, 0, 1.0)])),
    "kodak-gold-200-style": dict(
        title="Kodak Gold 200 style (approx)",
        fn=lambda x: grade(x, g=1.12, sat=1.08, gain=(1.0, 0.95, 0.80), lift=(0.04, 0.03, 0.004),
                           sh=(0.015, 0.008, -0.01), hue=[(225, 35, 0.8, 0, 1.0)])),
    "kodak-vision3-500t-style": dict(
        title="Kodak Vision3 500T style (approx)",
        fn=lambda x: grade(x, g=1.1, sat=0.95, lift=(0.02, 0.025, 0.03), sh=(-0.015, 0.008, 0.025),
                           hi=(0.03, 0.012, -0.02), gain=(0.98, 0.99, 1.0),
                           hue=[(120, 40, 0.85, 0, 1.0)])),
    "kodak-trix-style": dict(
        title="Kodak Tri-X style (approx, B&W)",
        fn=lambda x: grade(x, g=1.38, p=0.42, crush=0.008, lift=(0.008, 0.008, 0.008),
                           mono=(0.30, 0.55, 0.15))),
    "fuji-velvia-50-style": dict(
        title="Fuji Velvia 50 style (approx)",
        fn=lambda x: grade(x, g=1.32, sat=1.45, crush=0.015,
                           hue=[(0, 25, 1.2, 0, 1.0), (120, 40, 1.2, 0, 1.0), (225, 35, 1.15, 0, 1.0),
                                (300, 30, 1.1, -8, 1.0)])),
    "fuji-provia-100-style": dict(
        title="Fuji Provia 100 style (approx)",
        fn=lambda x: grade(x, g=1.15, sat=1.1, gain=(0.985, 1.0, 1.0), hue=[(120, 40, 1.05, 0, 1.0)])),
    "fuji-astia-100-style": dict(
        title="Fuji Astia 100 style (approx)",
        fn=lambda x: grade(x, g=1.0, sat=0.93, lift=(0.018, 0.016, 0.014), gain=(1.0, 0.995, 0.985),
                           hue=[(25, 25, 0.95, 0, 1.0), (120, 40, 0.9, 0, 1.0)])),
    "fuji-pro-400h-style": dict(
        title="Fuji Pro 400H style (approx)",
        fn=lambda x: grade(x, g=0.96, sat=0.85, lift=(0.05, 0.065, 0.065), gamma=(0.96, 0.94, 0.95),
                           sh=(-0.01, 0.012, 0.014), hi=(0.0, 0.005, 0.01),
                           hue=[(120, 40, 0.8, 18, 1.0), (0, 25, 0.9, 0, 1.0)])),
    "fuji-eterna-style": dict(
        title="Fuji Eterna style (approx)",
        fn=lambda x: grade(x, g=0.95, sat=0.72, lift=(0.032, 0.034, 0.038), sh=(-0.005, 0.004, 0.01),
                           hue=[(120, 40, 0.75, 0, 1.0)])),
    "cinestill-800t-style": dict(
        title="CineStill 800T style (approx)",
        fn=lambda x: grade(x, g=1.12, sat=1.05, lift=(0.015, 0.02, 0.03), sh=(-0.02, 0.0, 0.038),
                           mid=(0.0, -0.003, 0.008), hi=(0.035, 0.005, -0.035), gain=(1.0, 0.99, 0.96))),
}

def tint_shadows(x, delta, reach=0.5, crush=0.015):
    """Strong shadow tint meant to sit on top of a base look. Each delta adds the tint colour and
    takes away its opposite, so shadows change colour rather than just turning grey, and a small
    crush keeps the blacks dense. Strength in the app scales it down."""
    y = luma(x)
    w = (1 - smoothstep(0.0, reach, y))[..., None]
    x = x + w * np.array(delta)
    x = (x - crush) / (1 - crush)
    return np.clip(x, 0, 1)


BLACK_TINTS = {
    "blacks-red": (0.14, -0.03, -0.03),
    "blacks-amber": (0.13, 0.065, -0.06),
    "blacks-green": (-0.04, 0.12, -0.04),
    "blacks-teal": (-0.06, 0.085, 0.105),
    "blacks-blue": (-0.03, 0.025, 0.15),
    "blacks-magenta": (0.12, -0.04, 0.12),
}
for _k, _d in BLACK_TINTS.items():
    LOOKS[_k] = dict(title=f"Tinted blacks {_k.split('-')[1]} (Rec.709 in/out, use as a layer)",
                     fn=(lambda d: (lambda x: tint_shadows(x, d)))(_d))


def foggy(x, glow=(0, 0, 0), knee=0.78, glow_lo=0.45, glow_sat=0.28, **kw):
    """Vivid grade plus fog atmosphere. Fog glow tints pale, low-saturation, bright pixels only, so
    foliage colour is untouched by it. Shoulder compresses highlights so fog reads as soft light."""
    x = grade(x, **kw)
    hsv = rgb2hsv(x)
    w = smoothstep(glow_lo, 0.92, luma(x)) * (1 - smoothstep(0.02, glow_sat, hsv[..., 1]))
    x = x + w[..., None] * np.array(glow)
    return np.clip(shoulder(np.clip(x, 0, 1), knee), 0, 1)


BAMBOO_LOOKS = {
    "misty-emerald": dict(
        title="Misty emerald (vivid emerald foliage, teal shadows, soft glowing fog)",
        fn=lambda x: foggy(x, g=0.96, sat=1.12, lift=(0.012, 0.03, 0.036), sh=(-0.025, 0.012, 0.025),
                           glow=(-0.01, 0.025, 0.03), knee=0.74,
                           hue=[(120, 38, 1.55, 8, 1.0), (80, 28, 1.45, 14, 1.0), (175, 30, 1.4, 0, 1.0)])),
    "bamboo-haze": dict(
        title="Bamboo haze (hard-boosted greens, pale cool fog, matte blacks)",
        fn=lambda x: foggy(x, g=0.92, sat=1.0, lift=(0.05, 0.056, 0.06), glow=(-0.006, 0.004, 0.016),
                           knee=0.76,
                           hue=[(105, 32, 1.85, 4, 1.0), (75, 25, 1.7, 12, 1.0), (140, 30, 1.4, 0, 1.0)])),
    "golden-fog": dict(
        title="Golden fog (amber glowing fog against cool green shadows)",
        fn=lambda x: foggy(x, g=1.0, sat=1.1, gain=(1.03, 1.0, 0.93), lift=(0.01, 0.02, 0.022),
                           sh=(-0.022, 0.014, 0.0), hi=(0.03, 0.015, -0.03), glow=(0.045, 0.025, -0.025),
                           knee=0.76,
                           hue=[(110, 35, 1.4, -4, 1.0), (60, 25, 1.35, 0, 1.0)])),
    "jade-dream": dict(
        title="Jade dream (teal-green foliage, magenta-tinted highlights, soft shoulder)",
        fn=lambda x: foggy(x, g=0.98, sat=1.22, lift=(0.025, 0.03, 0.038), hi=(0.025, -0.012, 0.03),
                           glow=(0.03, 0.0, 0.035), knee=0.7,
                           hue=[(115, 38, 1.5, 20, 1.0), (80, 28, 1.45, 24, 1.0), (300, 40, 1.2, 0, 1.0)])),
    "neon-forest": dict(
        title="Neon forest (strong teal and magenta-orange split tone, punchy colour)",
        fn=lambda x: foggy(x, g=1.2, sat=1.5, sh=(-0.045, 0.028, 0.05), hi=(0.07, 0.0, 0.02),
                           glow=(0.05, -0.01, 0.03), knee=0.8, lift=(0.01, 0.012, 0.02),
                           hue=[(115, 38, 1.35, 12, 1.0), (25, 28, 1.25, 0, 1.0), (60, 25, 1.25, 0, 1.0)])),
    "pastel-mist": dict(
        title="Pastel mist (airy and bright, lifted blacks, pale fog, saturated foliage)",
        fn=lambda x: foggy(x, g=0.88, sat=0.88, gamma=(0.93, 0.92, 0.93), lift=(0.07, 0.075, 0.082),
                           glow=(0.005, 0.01, 0.022), knee=0.72, glow_lo=0.4,
                           hue=[(110, 38, 1.75, 8, 1.0), (80, 28, 1.6, 14, 1.0)])),
}


# ----------------------------------------------------------------------------
# Creative looks, one folder per category (folder name -> chip title in the app)
# ----------------------------------------------------------------------------
def overlay(a, b):
    return np.where(a < 0.5, 2 * a * b, 1 - 2 * (1 - a) * (1 - b))


def keep_hue(x, center, width, others=0.15, boost=1.2):
    """Selective colour: one hue range keeps (and gains) saturation, everything else drains."""
    hsv = rgb2hsv(x)
    dh = (hsv[..., 0] - center + 180.0) % 360.0 - 180.0
    w = np.exp(-0.5 * (dh / width) ** 2) * smoothstep(0.08, 0.3, hsv[..., 1])
    s = hsv[..., 1] * (others + (boost - others) * w)
    return hsv2rgb(np.stack([hsv[..., 0], np.clip(s, 0, 1), hsv[..., 2]], -1))


def look(x, mix=None, bleach=0.0, keep=None, tone=None, shadows=None, **kw):
    """grade() plus a channel mixer before it, and bleach bypass, selective colour, a mono tone
    (sepia etc) and a shadow tint after it."""
    x = np.clip(x, 0, 1)
    if mix is not None:
        x = np.clip(x @ np.array(mix).T, 0, 1)
    x = grade(x, **kw)
    if keep:
        x = keep_hue(x, *keep)
    if bleach:
        y = np.repeat(luma(x)[..., None], 3, -1)
        x = x * (1 - bleach) + overlay(y, x) * bleach
    if tone is not None:
        y = luma(x)[..., None]
        x = overlay(y, np.array(tone) * np.ones_like(x))
    if shadows is not None:
        x = tint_shadows(x, *shadows)
    return np.clip(x, 0, 1)


def xpro(x, black, white, gamma=(1, 1, 1), g=1.2, sat=1.15, hue=None):
    """Cross processing: each channel gets its own curve and its own black and white point, so the
    blacks and the whites take on different, opposing colours (the film-in-the-wrong-chemistry look).
    black/white are the RGB the darkest and brightest tones land on."""
    x = grade(x, g=g, sat=sat, hue=hue)
    x = np.power(np.clip(x, 0, 1), np.array(gamma))
    b = np.array(black)
    w = np.array(white)
    return np.clip(b + (w - b) * x, 0, 1)


def solarize(x, threshold=0.5, strength=1.0):
    """Sabattier effect: tones above the threshold fold back down, per channel."""
    folded = np.where(x > threshold, threshold - (x - threshold) * threshold / (1 - threshold), x)
    return x + (folded / max(threshold, 1e-6) - x) * strength


def posterize(x, levels):
    """Hard steps per channel. The 33-point LUT softens each step slightly."""
    return np.round(np.clip(x, 0, 1) * (levels - 1)) / (levels - 1)


def gradient_map(t, stops):
    """Maps 0..1 onto a colour ramp given as [(pos, (r, g, b)), ...]."""
    pos = np.array([p for p, _ in stops])
    cols = np.array([c for _, c in stops], dtype=float)
    out = np.stack([np.interp(t, pos, cols[:, i]) for i in range(3)], -1)
    return np.clip(out, 0, 1)


def hue_warp(x, amount, waves=3.0, sat=1.2, phase=0.0):
    """Rotates hue by an amount that itself swings with hue and brightness, so neighbouring
    colours land far apart: a scrambled, glitchy palette that stays smooth enough for a LUT."""
    hsv = rgb2hsv(np.clip(x, 0, 1))
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    shift = amount * np.sin(np.radians(h * waves) + v * 6.0 + phase)
    s = np.clip(s * sat, 0, 1)
    return hsv2rgb(np.stack([(h + shift) % 360.0, s, v], -1))


def thermal(x, stops, contrast=1.25):
    """Heat-camera false colour: brightness stands in for temperature and is mapped onto a palette.
    A gentle S-curve on brightness first spreads the mid-tones across more of the palette."""
    t = scurve(luma(np.clip(x, 0, 1)), PIVOT, contrast)
    return gradient_map(t, stops)


THERMAL_PALETTES = {
    "ironbow": ("Ironbow (classic heat camera: black, purple, magenta, orange, yellow, white)", [
        (0.0, (0.02, 0.0, 0.12)), (0.25, (0.35, 0.0, 0.6)), (0.45, (0.85, 0.05, 0.35)),
        (0.65, (1.0, 0.5, 0.0)), (0.85, (1.0, 0.9, 0.1)), (1.0, (1.0, 1.0, 0.9))]),
    "rainbow": ("Rainbow (high-contrast scientific palette, blue cold to red hot)", [
        (0.0, (0.0, 0.0, 0.25)), (0.18, (0.0, 0.2, 1.0)), (0.36, (0.0, 0.9, 1.0)), (0.52, (0.1, 1.0, 0.2)),
        (0.68, (1.0, 1.0, 0.0)), (0.84, (1.0, 0.25, 0.0)), (1.0, (1.0, 1.0, 1.0))]),
    "white-hot": ("White hot (greyscale, hot is white, cold is black)", [
        (0.0, (0.0, 0.0, 0.0)), (1.0, (1.0, 1.0, 1.0))]),
    "black-hot": ("Black hot (greyscale, hot is black, cold is white)", [
        (0.0, (1.0, 1.0, 1.0)), (1.0, (0.0, 0.0, 0.0))]),
    "lava": ("Lava (black through deep red and orange to yellow)", [
        (0.0, (0.0, 0.0, 0.0)), (0.3, (0.45, 0.0, 0.0)), (0.55, (0.9, 0.15, 0.0)),
        (0.8, (1.0, 0.6, 0.0)), (1.0, (1.0, 1.0, 0.55))]),
    "arctic": ("Arctic (black through navy and blue to icy cyan and white)", [
        (0.0, (0.0, 0.0, 0.03)), (0.3, (0.0, 0.08, 0.4)), (0.55, (0.0, 0.4, 0.85)),
        (0.8, (0.3, 0.85, 1.0)), (1.0, (0.95, 1.0, 1.0))]),
    "night-vision": ("Night vision (phosphor green image intensifier)", [
        (0.0, (0.0, 0.02, 0.0)), (0.5, (0.1, 0.6, 0.1)), (0.85, (0.55, 1.0, 0.45)), (1.0, (0.9, 1.0, 0.85))]),
    "medical": ("Medical (blue, green, yellow, red, as on a temperature scan)", [
        (0.0, (0.0, 0.0, 0.35)), (0.25, (0.0, 0.35, 1.0)), (0.5, (0.0, 0.85, 0.3)),
        (0.75, (1.0, 0.9, 0.0)), (1.0, (0.9, 0.0, 0.0))]),
}


def underwater(x, red_from=(0.0, 0.0), gain=(1, 1, 1), g=1.15, sat=1.15, crush=0.0, hue=None,
               lift=(0, 0, 0)):
    """Water absorbs red first, then orange, so subjects go blue or green with depth. Rebuild red
    from the green (and a little blue) channel that survives, rebalance the cast with per-channel
    gain, then add contrast to cut the haze. A fixed correction: pick the depth/water that fits."""
    x = np.clip(x, 0, 1)
    rg, rb = red_from
    r = x[..., 0] + rg * x[..., 1] + rb * x[..., 2]
    x = np.stack([r, x[..., 1], x[..., 2]], -1) * np.array(gain)
    return grade(np.clip(x, 0, 1), g=g, sat=sat, crush=crush, hue=hue, lift=lift)


def pastel(x, black, white, g=0.9, sat=0.9, hue=None, gamma=(1, 1, 1), glow=0.0, knee=0.72,
           sh=(0, 0, 0), hi=(0, 0, 0), bright=0.8):
    """Soft pastel / kawaii: low contrast, then the tonal range is squeezed between a coloured
    black (never true black) and a coloured white, so shadows turn lavender or rose instead of
    grey. bright < 1 lifts the mid-tones (pastels are light colours); a soft shoulder plus a
    little glow blooms the highlights."""
    x = grade(x, g=g, sat=sat, hue=hue, gamma=gamma, sh=sh, hi=hi)
    if glow:
        y = luma(x)[..., None]
        x = x + glow * smoothstep(0.45, 1.0, y) * (1 - x)
    x = shoulder(np.power(np.clip(x, 0, 1), bright), knee)
    b = np.array(black)
    w = np.array(white)
    return np.clip(b + (w - b) * x, 0, 1)


VELVIA_HUE = [(0, 25, 1.2, 0, 1.0), (120, 40, 1.2, 0, 1.0), (225, 35, 1.15, 0, 1.0), (300, 30, 1.1, -8, 1.0)]


def atmos(x, black, glow=0.0, knee=0.8, glow_tint=(1, 1, 1), **kw):
    """A saturated slide-film grade with atmosphere: the blacks are lifted onto a coloured floor
    (so shadows read as air and depth rather than ink), highlights roll off softly and a little
    tinted glow blooms the brights. Saturation stays high so the colour still pops."""
    x = grade(x, **kw)
    if glow:
        y = luma(x)[..., None]
        x = x + glow * smoothstep(0.5, 1.0, y) * (np.array(glow_tint) - x)
    x = shoulder(np.clip(x, 0, 1), knee)
    b = np.array(black)
    return np.clip(b + (1 - b) * x, 0, 1)


def floor_ceiling(x, black, white):
    """Squeezes the tonal range between a coloured black and a coloured white."""
    b = np.array(black)
    w = np.array(white)
    return np.clip(b + (w - b) * np.clip(x, 0, 1), 0, 1)


# Moody landscape looks, matched by eye and by luma-band colour averages to reference stills
# James collected (Instagram #moodylandscape). Working back from finished photos is approximate.
def fog_grey(x):
    """Hallstatt fog: colour drained (warm accents keep a little), low contrast, cool blue-grey
    shadows on a lifted floor, a soft off-white ceiling."""
    x = grade(x, g=0.85, sat=0.32, hue=[(30, 30, 1.9, -4, 1.0), (120, 50, 0.6, 25, 1.0)])
    y = luma(x)[..., None]
    x = x + (1 - smoothstep(0.0, 0.6, y)) * np.array([-0.012, 0.0, 0.012])
    x = np.power(np.clip(x, 0, 1), 0.8)
    return floor_ceiling(x, (0.09, 0.10, 0.112), (0.885, 0.892, 0.902))


def teal_night(x):
    """Moonlit pines: everything on one teal hue, crushed blacks, highlights stop short of white."""
    y = luma(np.clip(x, 0, 1))
    t = scurve(np.clip((y - 0.05) / 0.9, 0, 1), 0.4, 1.35)
    return gradient_map(t, [(0.0, (0.0, 0.012, 0.016)), (0.25, (0.03, 0.19, 0.245)),
                            (0.55, (0.16, 0.43, 0.53)), (0.8, (0.36, 0.64, 0.72)),
                            (1.0, (0.62, 0.82, 0.86))])


def dark_forest(x):
    """Wall of fog: a low-key teal forest. Tone comes from a dark teal ramp, a third of the
    original colour (greens turned toward teal) rides on top, the brightest fog is a dim cyan-grey."""
    x = np.clip(x, 0, 1)
    t = scurve(np.clip((luma(x) - 0.04) / 0.92, 0, 1), 0.45, 1.25)
    ramp = gradient_map(np.power(t, 1.12), [(0.0, (0.004, 0.022, 0.024)), (0.3, (0.07, 0.16, 0.17)),
                                            (0.65, (0.17, 0.38, 0.43)), (1.0, (0.4, 0.62, 0.68))])
    keep = grade(hue_adjust(x, [(100, 60, 0.7, 50, 1.0), (30, 40, 0.5, 0, 1.0)]), g=1.2, sat=0.6)
    return np.clip(ramp + (keep - luma(keep)[..., None]) * 0.35, 0, 1)


def faded_green(x):
    """Faded green film: navy-lifted blacks, greens drained toward olive, gentle contrast, cool
    clean whites."""
    x = grade(x, g=1.05, sat=0.7, hue=[(110, 45, 0.9, -6, 0.95), (30, 30, 0.8, 0, 1.0), (210, 40, 0.8, 0, 1.0)])
    y = luma(x)[..., None]
    x = (x + (1 - smoothstep(0.0, 0.55, y)) * np.array([-0.018, 0.01, 0.006])
         + smoothstep(0.55, 1.0, y) * np.array([-0.008, 0.0, 0.01]))
    return floor_ceiling(x, (0.055, 0.075, 0.115), (0.955, 0.958, 0.962))


def china_street_film(x):
    """China street film (toros.cangar): gentle filmic contrast, muted teal-green greens and blues,
    warm midtones and shadows, natural skin, reds and pinks kept punchy, lifted blacks."""
    x = grade(x, g=1.1, sat=0.82, sh=(0.012, 0.0, -0.014), mid=(0.008, 0.003, -0.01), hi=(-0.006, 0.006, -0.002),
              hue=[(0, 22, 1.35, 0, 1.0), (340, 20, 1.3, 0, 1.0), (25, 20, 1.08, 0, 1.0),
                   (110, 45, 0.55, 25, 0.95), (200, 40, 0.75, -12, 1.0)])
    x = shoulder(np.clip(x, 0, 1), 0.8)
    return floor_ceiling(x, (0.05, 0.048, 0.042), (0.93, 0.95, 0.93))


CATEGORY_LOOKS = {
    "moody-landscape": {
        "fog-grey": dict(title="Fog grey (drained cool greys, lifted shadows, soft white sky)", fn=fog_grey),
        "teal-night": dict(title="Teal night (moonlit monochrome teal, crushed blacks)", fn=teal_night),
        "dark-forest": dict(title="Dark forest (low-key teal woods in fog)", fn=dark_forest),
        "faded-green": dict(title="Faded green (muted olive greens, navy-lifted blacks)", fn=faded_green),
        "china-street-film": dict(title="China street film (warm mids, muted teal greens, punchy reds)",
                                  fn=china_street_film),
    },
    "slide-film": {
        "velvia-punch": dict(
            title="Velvia punch (Velvia 50 style pushed further: denser colour, deeper blues)",
            fn=lambda x: grade(x, g=1.36, sat=1.6, crush=0.02,
                               hue=[(0, 25, 1.25, 0, 1.0), (120, 40, 1.3, 0, 1.0), (215, 35, 1.3, 5, 0.95),
                                    (300, 30, 1.15, -8, 1.0), (45, 25, 1.15, 0, 1.0)])),
        "golden-slide": dict(
            title="Golden slide (saturated slide colour with warm golden-hour highlights)",
            fn=lambda x: grade(x, g=1.3, sat=1.45, crush=0.015, gain=(1.04, 1.0, 0.92),
                               hi=(0.03, 0.012, -0.03),
                               hue=[(30, 30, 1.3, 0, 1.0), (50, 25, 1.25, -5, 1.0), (120, 40, 1.15, -6, 1.0),
                                    (225, 35, 1.1, 0, 1.0)])),
        "emerald-slide": dict(
            title="Emerald slide (lush deep greens and cyan skies)",
            fn=lambda x: grade(x, g=1.32, sat=1.45, crush=0.015, gain=(0.98, 1.02, 1.0),
                               hue=[(110, 40, 1.45, 10, 0.95), (75, 25, 1.3, 20, 1.0), (200, 35, 1.35, -8, 1.0),
                                    (0, 25, 1.1, 0, 1.0)])),
        "crimson-slide": dict(
            title="Crimson slide (rich reds and magentas, made for sunsets and flowers)",
            fn=lambda x: grade(x, g=1.34, sat=1.5, crush=0.018, gain=(1.03, 0.98, 0.98),
                               hue=[(355, 30, 1.45, 0, 0.97), (25, 25, 1.3, -6, 1.0), (310, 35, 1.35, 0, 1.0),
                                    (225, 35, 1.1, 0, 1.0)])),
        "cobalt-slide": dict(
            title="Cobalt slide (cool and crisp, intense blue skies and water)",
            fn=lambda x: grade(x, g=1.3, sat=1.45, crush=0.015, gain=(0.97, 1.0, 1.04),
                               hue=[(215, 35, 1.45, 5, 0.93), (185, 30, 1.3, 10, 1.0), (120, 40, 1.15, 10, 1.0),
                                    (25, 22, 1.05, 0, 1.0)])),
    },
    "atmospheric": {
        "faded-velvia": dict(
            title="Faded Velvia (Velvia 50 style colour, softly lifted blacks)",
            fn=lambda x: atmos(x, black=(0.087, 0.087, 0.094), g=1.25, sat=1.45, hue=VELVIA_HUE, knee=0.82)),
        "misty-blue": dict(
            title="Misty blue (saturated colour, blue-grey lifted shadows, cool haze)",
            fn=lambda x: atmos(x, black=(0.087, 0.116, 0.160), glow=0.08, glow_tint=(0.85, 0.92, 1.0),
                               g=1.22, sat=1.4, hue=VELVIA_HUE, sh=(-0.01, 0.0, 0.02), knee=0.78)),
        "golden-haze": dict(
            title="Golden haze (warm amber shadows and a glowing, sunlit haze)",
            fn=lambda x: atmos(x, black=(0.160, 0.109, 0.058), glow=0.12, glow_tint=(1.0, 0.88, 0.65),
                               g=1.22, sat=1.4, gain=(1.03, 1.0, 0.94), knee=0.76,
                               hue=[(30, 30, 1.3, 0, 1.0), (120, 40, 1.15, -6, 1.0), (225, 35, 1.1, 0, 1.0)])),
        "teal-atmosphere": dict(
            title="Teal atmosphere (teal lifted shadows, warm saturated highlights)",
            fn=lambda x: atmos(x, black=(0.043, 0.123, 0.131), glow=0.06, glow_tint=(1.0, 0.92, 0.8),
                               g=1.25, sat=1.42, hi=(0.025, 0.008, -0.02), knee=0.8,
                               hue=[(200, 35, 1.3, -10, 1.0), (25, 25, 1.2, 0, 1.0), (120, 40, 1.2, 15, 1.0)])),
        "violet-dusk": dict(
            title="Violet dusk (purple lifted shadows, rosy glow, deep saturated colour)",
            fn=lambda x: atmos(x, black=(0.131, 0.072, 0.160), glow=0.1, glow_tint=(1.0, 0.82, 0.88),
                               g=1.24, sat=1.4, knee=0.78, sh=(0.01, -0.01, 0.02),
                               hue=[(300, 35, 1.35, 0, 1.0), (355, 30, 1.25, 0, 1.0), (225, 35, 1.2, 10, 1.0)])),
        "forest-mist": dict(
            title="Forest mist (deep greens, green-teal lifted shadows, pale soft haze)",
            fn=lambda x: atmos(x, black=(0.072, 0.131, 0.109), glow=0.1, glow_tint=(0.9, 0.97, 0.93),
                               g=1.22, sat=1.4, knee=0.76,
                               hue=[(110, 40, 1.4, 8, 0.95), (75, 25, 1.3, 16, 1.0), (200, 35, 1.15, 0, 1.0)])),
    },
    "kawaii": {
        "sakura": dict(
            title="Sakura (cherry-blossom pinks, rose shadows, soft bright whites)",
            fn=lambda x: pastel(x, black=(0.29, 0.22, 0.26), white=(1.0, 0.97, 0.98), g=0.88, sat=0.95,
                                glow=0.12, hue=[(340, 35, 1.5, 0, 1.03), (10, 25, 1.2, -10, 1.0),
                                                (110, 45, 0.55, 40, 1.05)])),
        "cotton-candy": dict(
            title="Cotton candy (pink highlights, baby-blue shadows, airy and pale)",
            fn=lambda x: pastel(x, black=(0.25, 0.26, 0.36), white=(1.0, 0.95, 0.98), g=0.85, sat=1.0,
                                glow=0.15, sh=(-0.02, 0.0, 0.04), hi=(0.03, -0.01, 0.01),
                                hue=[(330, 40, 1.4, 0, 1.0), (210, 40, 1.2, -10, 1.03)])),
        "strawberry-milk": dict(
            title="Strawberry milk (creamy pink all over, gentle and warm)",
            fn=lambda x: pastel(x, black=(0.33, 0.25, 0.26), white=(1.0, 0.96, 0.94), g=0.86, sat=0.95,
                                glow=0.1, gamma=(0.96, 1.0, 1.0), hi=(0.02, -0.01, 0.0),
                                hue=[(350, 40, 1.3, 0, 1.0), (110, 45, 0.6, 30, 1.0)])),
        "milk-tea": dict(
            title="Milk tea (beige and cream, cosy and muted)",
            fn=lambda x: pastel(x, black=(0.32, 0.27, 0.25), white=(1.0, 0.97, 0.90), g=0.86, sat=1.0,
                                glow=0.08, gamma=(0.97, 1.0, 1.05),
                                hue=[(30, 30, 1.15, 0, 1.0), (210, 40, 0.65, 0, 1.0)])),
        "mint-soda": dict(
            title="Mint soda (mint greens and aqua, peach skin, fresh and clean)",
            fn=lambda x: pastel(x, black=(0.22, 0.29, 0.29), white=(0.97, 1.0, 0.97), g=0.9, sat=0.95,
                                glow=0.1, sh=(-0.01, 0.015, 0.01),
                                hue=[(100, 45, 1.2, 35, 1.05), (190, 35, 1.25, 0, 1.03),
                                     (25, 25, 1.05, 5, 1.02)])),
        "lavender-dream": dict(
            title="Lavender dream (lilac shadows, rosy highlights, hazy glow)",
            fn=lambda x: pastel(x, black=(0.29, 0.25, 0.36), white=(1.0, 0.96, 1.0), g=0.84, sat=1.0,
                                glow=0.18, knee=0.68, sh=(0.0, -0.01, 0.03),
                                hue=[(260, 40, 1.4, 0, 1.03), (320, 35, 1.25, 0, 1.0), (110, 45, 0.6, 50, 1.0)])),
        "peach-blossom": dict(
            title="Peach blossom (peachy warmth, coral pinks, golden glow)",
            fn=lambda x: pastel(x, black=(0.32, 0.25, 0.23), white=(1.0, 0.97, 0.92), g=0.9, sat=0.95,
                                glow=0.12, hi=(0.02, 0.005, -0.01),
                                hue=[(20, 30, 1.3, 0, 1.03), (350, 30, 1.25, 0, 1.0), (210, 40, 0.75, 0, 1.0)])),
        "anime-sky": dict(
            title="Anime sky (clear cyan skies, bright clean whites, fluffy highlights)",
            fn=lambda x: pastel(x, black=(0.19, 0.20, 0.27), white=(1.0, 1.0, 1.0), g=1.0, sat=1.15,
                                glow=0.1, knee=0.75, sh=(-0.01, 0.0, 0.03),
                                hue=[(210, 35, 1.5, -12, 1.05), (110, 40, 1.15, 15, 1.03),
                                     (25, 25, 1.0, 0, 1.03)])),
    },
    "cross-processing": {
        "slide-in-c41": dict(
            title="Slide in C-41 (cyan-blue blacks, yellow-lime highlights, punchy)",
            fn=lambda x: xpro(x, black=(0.0, 0.1, 0.3), white=(0.95, 1.0, 0.55), gamma=(0.85, 0.75, 1.5),
                              g=1.35, sat=1.3)),
        "negative-in-e6": dict(
            title="Negative in E-6 (magenta blacks, pale cyan highlights, faded)",
            fn=lambda x: xpro(x, black=(0.28, 0.0, 0.2), white=(0.7, 1.0, 0.95), gamma=(1.3, 0.85, 0.9),
                              g=1.15, sat=1.15)),
        "teal-and-peach": dict(
            title="Teal and peach (deep teal blacks, warm peach whites)",
            fn=lambda x: xpro(x, black=(0.0, 0.2, 0.22), white=(1.0, 0.82, 0.65), gamma=(0.88, 1.0, 1.2),
                              g=1.25, sat=1.15)),
        "violet-and-gold": dict(
            title="Violet and gold (purple blacks, golden highlights)",
            fn=lambda x: xpro(x, black=(0.22, 0.0, 0.32), white=(1.0, 0.88, 0.45), gamma=(0.9, 1.05, 1.25),
                              g=1.3, sat=1.25)),
        "red-and-cyan": dict(
            title="Red and cyan (crimson blacks, icy cyan whites, clashing)",
            fn=lambda x: xpro(x, black=(0.32, 0.0, 0.05), white=(0.68, 1.0, 1.0), gamma=(1.35, 0.9, 0.88),
                              g=1.3, sat=1.25)),
        "green-and-pink": dict(
            title="Green and pink (bottle-green blacks, candy-pink highlights)",
            fn=lambda x: xpro(x, black=(0.0, 0.26, 0.08), white=(1.0, 0.7, 0.85), gamma=(0.85, 1.2, 1.0),
                              g=1.25, sat=1.2)),
        "acid-wash": dict(
            title="Acid wash (blue blacks, green mids, orange highs, very saturated)",
            fn=lambda x: xpro(x, black=(0.0, 0.05, 0.38), white=(1.0, 0.78, 0.3), gamma=(1.35, 0.7, 1.6),
                              g=1.4, sat=1.5)),
    },
    "glitch": {
        "channel-swap": dict(
            title="Channel swap (red, green and blue trade places)",
            fn=lambda x: look(x, mix=[(0, 0, 1), (1, 0, 0), (0, 1, 0)], g=1.15, sat=1.2)),
        "solarize": dict(
            title="Solarize (bright tones fold back into dark, Sabattier effect)",
            fn=lambda x: grade(solarize(grade(x, g=1.1, sat=1.2), 0.55), g=1.25, sat=1.3)),
        "posterize": dict(
            title="Posterize (flat colour bands, screen-print look)",
            fn=lambda x: posterize(grade(x, g=1.2, sat=1.4), 4)),
        "hue-scramble": dict(
            title="Hue scramble (colours thrown to random-looking neighbours)",
            fn=lambda x: grade(hue_warp(x, 150, waves=3, sat=1.35), g=1.15)),
        "psychedelic": dict(
            title="Psychedelic (rainbow bands follow brightness, saturated)",
            fn=lambda x: np.clip(hsv2rgb(np.stack([
                (luma(np.clip(x, 0, 1)) * 360.0 + rgb2hsv(np.clip(x, 0, 1))[..., 0] * 0.3) % 360.0,
                np.full(x.shape[:-1], 0.85),
                np.clip(0.25 + 0.85 * luma(np.clip(x, 0, 1)), 0, 1)], -1)), 0, 1)),
        "negative": dict(
            title="Negative (inverted tones and colours, cool cast)",
            fn=lambda x: grade(1.0 - np.clip(x, 0, 1), g=1.1, sat=1.1, gain=(0.92, 1.0, 1.05))),
        "broken-signal": dict(
            title="Broken signal (posterised, clipped channels, green-magenta fringe)",
            fn=lambda x: posterize(look(x, mix=[(1.5, -0.6, 0.2), (-0.4, 1.6, -0.3), (0.5, -0.5, 1.1)],
                                        g=1.5, sat=1.8, sh=(0.12, -0.08, 0.14), hi=(-0.1, 0.12, -0.08)), 5)),
    },
    "thermal": {
        _k: dict(title=_t, fn=(lambda st: (lambda x: thermal(x, st)))(_s))
        for _k, (_t, _s) in THERMAL_PALETTES.items()
    },
    "underwater": {
        "shallow-reef": dict(
            title="Shallow reef (0-5 m: warms skin and sand back up, clear blue water)",
            fn=lambda x: underwater(x, red_from=(0.18, 0.0), gain=(1.12, 1.0, 0.92), g=1.15, sat=1.12,
                                    hue=[(25, 25, 1.2, 0, 1.0)])),
        "deep-blue": dict(
            title="Deep blue (10-20 m: rebuilds red from green, cuts the strong blue cast)",
            fn=lambda x: underwater(x, red_from=(0.45, 0.1), gain=(1.2, 0.95, 0.78), g=1.22, sat=1.18,
                                    crush=0.015, hue=[(25, 30, 1.3, 0, 1.0), (200, 40, 0.9, 0, 1.0)])),
        "green-water": dict(
            title="Green water (lakes and temperate seas: removes green cast, adds magenta)",
            fn=lambda x: underwater(x, red_from=(0.35, 0.1), gain=(1.15, 0.86, 1.0), g=1.2, sat=1.15,
                                    crush=0.01, hue=[(100, 40, 0.8, 30, 1.0), (25, 30, 1.25, 0, 1.0)])),
        "vivid-coral": dict(
            title="Vivid coral (restored reds and oranges, extra punch for reef colour)",
            fn=lambda x: underwater(x, red_from=(0.5, 0.1), gain=(1.2, 0.95, 0.8), g=1.3, sat=1.35,
                                    crush=0.02, hue=[(10, 30, 1.4, 0, 1.0), (40, 25, 1.3, 0, 1.0),
                                                     (300, 35, 1.25, 0, 1.0)])),
        "open-blue": dict(
            title="Open blue (keeps the blue, deepens it and cuts the haze)",
            fn=lambda x: underwater(x, red_from=(0.12, 0.0), gain=(1.05, 1.0, 1.0), g=1.35, sat=1.25,
                                    crush=0.025, hue=[(205, 40, 1.25, 5, 0.95)])),
    },
    "cinematic": {
        "teal-and-orange": dict(
            title="Teal and orange (warm skin, teal shadows and blues)",
            fn=lambda x: look(x, g=1.15, sat=1.05, sh=(-0.035, 0.012, 0.04), hi=(0.03, 0.01, -0.025),
                              hue=[(25, 22, 1.15, 0, 1.0), (215, 45, 1.1, -25, 0.95), (120, 40, 0.7, 45, 0.95),
                                   (60, 20, 0.85, -10, 1.0)])),
        "blockbuster": dict(
            title="Blockbuster (punchy contrast, hard teal-orange split)",
            fn=lambda x: look(x, g=1.32, sat=1.12, crush=0.015, sh=(-0.05, 0.015, 0.055),
                              hi=(0.05, 0.015, -0.04),
                              hue=[(25, 22, 1.2, 0, 1.0), (210, 50, 1.2, -25, 0.92), (120, 45, 0.6, 50, 0.9)])),
        "golden-hour": dict(
            title="Golden hour (warm glow, soft contrast)",
            fn=lambda x: look(x, g=1.05, sat=1.08, gain=(1.05, 1.0, 0.86), lift=(0.02, 0.012, 0.0),
                              hi=(0.03, 0.015, -0.02), hue=[(40, 30, 1.15, -5, 1.0)])),
        "nordic-noir": dict(
            title="Nordic noir (cold, muted, steel-blue shadows)",
            fn=lambda x: look(x, g=1.18, sat=0.58, gain=(0.95, 0.99, 1.04), sh=(-0.02, 0.0, 0.025),
                              lift=(0.01, 0.014, 0.02), hue=[(120, 45, 0.6, 20, 0.95)])),
        "desert-heat": dict(
            title="Desert heat (scorched orange, teal shadows)",
            fn=lambda x: look(x, g=1.25, sat=1.2, gain=(1.08, 0.97, 0.78), crush=0.01,
                              sh=(-0.03, 0.02, 0.03), hue=[(35, 35, 1.3, -5, 1.0), (210, 40, 1.0, -15, 1.0)])),
        "day-for-night": dict(
            title="Day for night (moonlit blue, darkened, low saturation)",
            fn=lambda x: look(x, g=1.2, sat=0.45, gamma=(1.55, 1.45, 1.25), gain=(0.72, 0.82, 1.0),
                              sh=(-0.01, 0.0, 0.015))),
    },
    "moody": {
        "faded-matte": dict(
            title="Faded matte (lifted blacks, soft highlights, muted colour)",
            fn=lambda x: look(x, g=0.92, sat=0.78, lift=(0.07, 0.068, 0.072), gain=(0.93, 0.93, 0.92))),
        "dark-forest": dict(
            title="Dark forest (deep olive greens, heavy shadows)",
            fn=lambda x: look(x, g=1.22, sat=0.82, gamma=(1.12, 1.08, 1.12), crush=0.01,
                              sh=(-0.01, 0.012, 0.0), hue=[(110, 45, 0.75, -20, 0.85), (25, 22, 0.9, 0, 1.0)])),
        "blue-hour": dict(
            title="Blue hour (cool dusk, gentle contrast)",
            fn=lambda x: look(x, g=1.08, sat=0.9, gain=(0.92, 0.97, 1.06), sh=(-0.015, 0.0, 0.03),
                              hi=(0.0, 0.0, 0.015))),
        "melancholy": dict(
            title="Melancholy (grey-green, desaturated, soft)",
            fn=lambda x: look(x, g=1.0, sat=0.55, gain=(0.96, 1.0, 0.96), lift=(0.03, 0.04, 0.035))),
    },
    "horror": {
        "sickly-green": dict(
            title="Sickly green (jaundiced mids, crushed blacks, drained skin)",
            fn=lambda x: look(x, g=1.3, sat=0.7, crush=0.03, mid=(-0.01, 0.035, -0.035),
                              gain=(0.94, 1.03, 0.85), hue=[(25, 25, 0.55, 15, 0.95)])),
        "blood-red": dict(
            title="Blood red (only reds keep colour, everything else drained and dark)",
            fn=lambda x: look(x, g=1.35, crush=0.025, gamma=(1.1, 1.15, 1.15), keep=(0, 22, 0.08, 1.5))),
        "cold-dread": dict(
            title="Cold dread (icy cyan, harsh contrast, near-mono)",
            fn=lambda x: look(x, g=1.45, sat=0.3, crush=0.035, gain=(0.86, 0.98, 1.04),
                              sh=(-0.01, 0.01, 0.02))),
        "haunted": dict(
            title="Haunted (sepia-green mono, deep vignette-like shadows)",
            fn=lambda x: look(x, g=1.4, crush=0.03, gamma=(1.15, 1.15, 1.15), mono=(0.3, 0.59, 0.11),
                              tone=(0.48, 0.52, 0.40))),
    },
    "cyberpunk": {
        "neon-nights": dict(
            title="Neon nights (magenta highlights, cyan shadows, saturated)",
            fn=lambda x: look(x, g=1.22, sat=1.35, sh=(-0.05, 0.02, 0.06), hi=(0.06, -0.02, 0.05),
                              hue=[(120, 45, 1.0, 50, 0.95), (60, 30, 1.1, -30, 1.0), (215, 45, 1.3, -10, 1.0)])),
        "synthwave": dict(
            title="Synthwave (purple shadows, hot pink and orange highlights)",
            fn=lambda x: look(x, g=1.15, sat=1.25, sh=(0.03, -0.02, 0.07), mid=(0.02, -0.02, 0.02),
                              hi=(0.06, 0.0, -0.01), hue=[(120, 50, 0.8, 80, 0.9), (210, 40, 1.15, 22, 1.0)])),
        "digital-green": dict(
            title="Digital green (green-tinted code world, hard contrast)",
            fn=lambda x: look(x, g=1.3, sat=0.6, crush=0.02, gain=(0.85, 1.05, 0.82),
                              mid=(-0.02, 0.03, -0.02), sh=(-0.01, 0.015, 0.0))),
        "acid-cyan": dict(
            title="Acid cyan (electric cyan and yellow, crushed blacks)",
            fn=lambda x: look(x, g=1.3, sat=1.4, crush=0.02, sh=(-0.05, 0.03, 0.05),
                              hi=(0.03, 0.04, -0.03), hue=[(210, 50, 1.3, -25, 1.0), (25, 25, 0.9, 15, 1.0)])),
    },
    "vintage": {
        "seventies": dict(
            title="Seventies (warm, faded, yellow-green shadows)",
            fn=lambda x: look(x, g=1.0, sat=0.88, lift=(0.06, 0.055, 0.03), gain=(1.02, 0.98, 0.86),
                              sh=(0.0, 0.015, -0.015), hue=[(210, 40, 0.75, 15, 1.0)])),
        "eighties-video": dict(
            title="Eighties video (hot reds, magenta cast, lifted and soft)",
            fn=lambda x: look(x, g=0.95, sat=1.25, lift=(0.05, 0.035, 0.05), gain=(1.0, 0.95, 1.0),
                              hue=[(0, 25, 1.3, 0, 1.0), (120, 40, 0.8, 0, 1.0)])),
        "instant-film": dict(
            title="Instant film (cyan shadows, creamy warm highlights, faded)",
            fn=lambda x: look(x, g=0.98, sat=0.85, lift=(0.04, 0.06, 0.07), gain=(0.98, 0.96, 0.9),
                              sh=(-0.02, 0.01, 0.025), hi=(0.03, 0.02, -0.01))),
        "super-8": dict(
            title="Super 8 (warm, contrasty, yellowed home movie)",
            fn=lambda x: look(x, g=1.28, sat=1.1, crush=0.02, lift=(0.03, 0.02, 0.0),
                              gain=(1.04, 0.98, 0.8))),
        "cross-process": dict(
            title="Cross process (yellow-green highlights, blue shadows, wild colour)",
            fn=lambda x: look(x, g=1.2, sat=1.2, gamma=(0.85, 0.9, 1.25), lift=(0.0, 0.0, 0.08),
                              gain=(1.05, 1.05, 0.85))),
        "sepia": dict(
            title="Sepia (warm brown mono)",
            fn=lambda x: look(x, g=1.1, mono=(0.3, 0.59, 0.11), tone=(0.6, 0.48, 0.36), lift=(0.02, 0.015, 0.01))),
    },
    "black-and-white": {
        "noir": dict(
            title="Noir (hard black and white, crushed shadows)",
            fn=lambda x: look(x, g=1.55, crush=0.04, mono=(0.3, 0.6, 0.1))),
        "silver-soft": dict(
            title="Silver soft (gentle matte black and white)",
            fn=lambda x: look(x, g=0.95, lift=(0.05, 0.05, 0.05), gain=(0.95, 0.95, 0.95), mono=(0.25, 0.65, 0.1))),
        "red-filter": dict(
            title="Red filter (black and white, dark skies, bright skin)",
            fn=lambda x: look(x, g=1.3, mono=(0.75, 0.3, -0.05))),
        "selenium": dict(
            title="Selenium (cool-toned black and white)",
            fn=lambda x: look(x, g=1.25, mono=(0.3, 0.6, 0.1), tone=(0.46, 0.49, 0.56))),
    },
    "vivid": {
        "pop": dict(
            title="Pop (bright, clean, saturated)",
            fn=lambda x: look(x, g=1.15, sat=1.38, hue=[(25, 22, 0.85, 0, 1.0)])),
        "tropical": dict(
            title="Tropical (aqua blues, lush greens, warm skin)",
            fn=lambda x: look(x, g=1.12, sat=1.25, gain=(1.0, 1.0, 0.98),
                              hue=[(210, 40, 1.35, -20, 1.0), (110, 40, 1.3, 10, 1.0), (25, 22, 1.05, 0, 1.0)])),
    },
    "gritty": {
        "bleach-bypass": dict(
            title="Bleach bypass (silvery, harsh, low saturation)",
            fn=lambda x: look(x, g=1.15, sat=0.85, bleach=0.75)),
        "urban-grit": dict(
            title="Urban grit (cool green-grey, hard contrast)",
            fn=lambda x: look(x, g=1.35, sat=0.6, crush=0.02, gain=(0.95, 1.0, 0.97),
                              sh=(-0.015, 0.01, 0.01), bleach=0.3)),
    },
    "quirky": {
        "infrared": dict(
            title="Infrared (pink-white foliage, dark skies)",
            fn=lambda x: look(x, mix=[(0.15, 0.85, 0.0), (0.2, 0.4, 0.4), (0.7, 0.1, 0.2)], g=1.15, sat=0.9)),
        "pastel-pink": dict(
            title="Pastel pink (candy colours, soft and lifted)",
            fn=lambda x: look(x, g=0.9, sat=0.85, lift=(0.08, 0.06, 0.07), gain=(1.0, 0.96, 0.97),
                              hi=(0.02, -0.005, 0.01), hue=[(120, 45, 0.7, 30, 1.0)])),
        "lomo": dict(
            title="Lomo (oversaturated, contrasty, warm-green cast)",
            fn=lambda x: look(x, g=1.4, sat=1.45, crush=0.03, gain=(1.0, 1.02, 0.88))),
    },
}


# ----------------------------------------------------------------------------
# Input transform: log code -> Rec.709 display
# ----------------------------------------------------------------------------
def input_transform(prof, code):
    lin = prof["dec"](code) @ prof["M"].T
    disp = oetf709(np.clip(lin, 0.0, None))
    disp = np.clip(shoulder(disp), 0.0, 1.0)
    return scurve(disp, PIVOT, NEUTRAL_G)


def grid():
    lin = np.linspace(0.0, 1.0, N)
    B, G, R = np.meshgrid(lin, lin, lin, indexing="ij")
    return np.stack([R, G, B], -1)  # [b, g, r, 3]


def write_cube(path, title, comments, lut, digits=6):
    with open(path, "w") as f:
        f.write(f'TITLE "{title}"\n')
        for c in comments:
            f.write(f"# {c}\n")
        f.write(f"LUT_3D_SIZE {N}\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        fmt = f"%.{digits}f %.{digits}f %.{digits}f\n"
        for row in lut.reshape(-1, 3):
            f.write(fmt % tuple(row))


def apply_lut(lut, img):
    n = lut.shape[0]
    s = np.clip(img, 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(s).astype(int), n - 2)
    f = s - i0
    r0, g0, b0 = i0[..., 0], i0[..., 1], i0[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]

    def L(db, dg, dr):
        return lut[b0 + db, g0 + dg, r0 + dr]

    c00 = L(0, 0, 0) * (1 - fr) + L(0, 0, 1) * fr
    c01 = L(0, 1, 0) * (1 - fr) + L(0, 1, 1) * fr
    c10 = L(1, 0, 0) * (1 - fr) + L(1, 0, 1) * fr
    c11 = L(1, 1, 0) * (1 - fr) + L(1, 1, 1) * fr
    c0 = c00 * (1 - fg) + c01 * fg
    c1 = c10 * (1 - fg) + c11 * fg
    return c0 * (1 - fb) + c1 * fb


# ----------------------------------------------------------------------------
# Synthetic scene (defined in linear Rec.709, encoded per camera)
# ----------------------------------------------------------------------------
W, H = 320, 192
ROWS = 6


def scene_linear709():
    img = np.zeros((H, W, 3))
    rh = H // ROWS

    def put(row, patches):
        n = len(patches)
        for i, v in enumerate(patches):
            img[row * rh:(row + 1) * rh, i * W // n:(i + 1) * W // n] = v

    grey = [0.0, 0.0045, 0.01, 0.0225, 0.045, 0.09, 0.18, 0.36, 0.72]
    put(0, [(v, v, v) for v in grey])
    put(1, [(0, 0, 0), (0.004, 0.004, 0.004), (0.36, 0.20, 0.13), (0.12, 0.065, 0.04),
            (0.07, 0.14, 0.04), (0.12, 0.20, 0.40), (0.30, 0.18, 0.07), (0.72, 0.72, 0.72)])
    put(2, [(0.25, 0.01, 0.01), (0.01, 0.25, 0.01), (0.01, 0.01, 0.25),
            (0.25, 0.25, 0.01), (0.01, 0.25, 0.25), (0.25, 0.01, 0.25)])
    ramp = np.linspace(0.0, 0.9, W)  # scene-linear grey ramp
    img[3 * rh:4 * rh] = np.power(ramp, 2.0)[None, :, None]
    hsv = np.stack([np.linspace(0, 360, W, endpoint=False), np.full(W, 0.6), np.full(W, 0.35)], -1)
    img[4 * rh:5 * rh] = np.power(hsv2rgb(hsv), 2.2)[None, :, :]
    img[5 * rh:] = np.linspace(0, 0.12, W)[None, :, None]
    return img


def scene_codes(prof, lin709):
    cam = lin709 @ np.linalg.inv(prof["M"]).T
    return prof["enc"](np.maximum(cam, 0.0))


def patch_set(lin709):
    """(grey patches, colour patches) arrays of shape (n,3) from the scene definition."""
    grey = [0.0, 0.0045, 0.01, 0.0225, 0.045, 0.09, 0.18, 0.36, 0.72]
    grey = np.array([(v, v, v) for v in grey])
    colour = np.array([(0.36, 0.20, 0.13), (0.12, 0.065, 0.04), (0.07, 0.14, 0.04), (0.12, 0.20, 0.40),
                       (0.30, 0.18, 0.07), (0.25, 0.01, 0.01), (0.01, 0.25, 0.01), (0.01, 0.01, 0.25),
                       (0.25, 0.25, 0.01), (0.01, 0.25, 0.25), (0.25, 0.01, 0.25)])
    return grey, colour


def to_u8(x):
    return (np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8)


BW, BH = 480, 288
RAMP_Y0, RAMP_Y1 = 258, 274
PRIM_Y0, PRIM_Y1 = 274, 288
SKIN_BOX = (24, 214, 74, 254)


def bamboo_scene():
    """Display-referred Rec.709 scene: foggy bamboo grove with a few test patches."""
    rng = np.random.default_rng(7)
    yy = np.linspace(0, 1, BH)[:, None, None]
    fog = np.array([0.80, 0.84, 0.84]) * (1 - yy) + np.array([0.60, 0.67, 0.65]) * yy
    img = np.broadcast_to(fog, (BH, BW, 3)).copy()
    stalks = [  # x centre, width, depth (0 near .. 1 far), colour
        (40, 16, 0.78, (0.30, 0.52, 0.26)), (96, 12, 0.85, (0.50, 0.62, 0.24)),
        (150, 20, 0.45, (0.26, 0.48, 0.20)), (214, 14, 0.7, (0.46, 0.60, 0.22)),
        (270, 26, 0.12, (0.22, 0.44, 0.16)), (338, 15, 0.6, (0.52, 0.64, 0.22)),
        (392, 22, 0.3, (0.27, 0.50, 0.21)), (448, 13, 0.82, (0.34, 0.54, 0.28)),
    ]
    xs = np.arange(BW)[None, :]
    ys = np.arange(BH)[:, None]
    for cx, wd, depth, col in stalks:
        d = np.abs(xs - cx) / (wd / 2.0)
        m = np.clip(1.5 - d * 1.5, 0, 1)  # soft edge
        shade = 0.72 + 0.28 * np.sqrt(np.clip(1 - np.minimum(d, 1) ** 2, 0, 1))  # round stalk shading
        node = 1 - 0.25 * np.exp(-((ys % 54 - 27) / 1.6) ** 2)  # node rings
        c = np.array(col)[None, None, :] * (shade * node)[..., None]
        c = c * (1 - depth) + fog * depth  # fog between camera and stalk
        img = img * (1 - m[..., None]) + c * m[..., None]
    ground = smoothstep(190, 258, np.arange(BH))[:, None, None]  # dark green undergrowth
    img = img * (1 - 0.8 * ground) + np.array([0.05, 0.11, 0.06]) * 0.8 * ground
    img += rng.normal(0, 0.004, img.shape)
    x0, y0, x1, y1 = SKIN_BOX
    img[y0:y1, x0:x1] = (0.72, 0.52, 0.42)
    img[y0:y1, 90:140] = (0.05, 0.10, 0.05)  # deep shadow green
    img[y0:y1, 156:206] = (0.40, 0.45, 0.40)  # neutral mid grey
    img[RAMP_Y0:RAMP_Y1] = np.linspace(0, 1, BW)[None, :, None]
    prim = [(0.8, 0.1, 0.1), (0.1, 0.7, 0.15), (0.1, 0.2, 0.85), (0.9, 0.85, 0.15), (0.1, 0.75, 0.8),
            (0.8, 0.15, 0.75)]
    for i, c in enumerate(prim):
        img[PRIM_Y0:PRIM_Y1, i * BW // 6:(i + 1) * BW // 6] = c
    return np.clip(img, 0, 1)


def bamboo_report(name, lut, scene, out):
    ramp = out[RAMP_Y0:RAMP_Y1].mean(0)
    lum = ramp @ LUMA
    d = np.diff(lum)
    steps = np.unique(to_u8(ramp)[:, 1]).size
    body = out[:RAMP_Y0 - 50]
    clip_hi = float((body >= 0.995).any(-1).mean()) * 100
    clip_lo = float((body <= 0.004).all(-1).mean()) * 100
    grey = out[RAMP_Y0:RAMP_Y1, BW // 2]
    x0, y0, x1, y1 = SKIN_BOX
    print(f"  {name:14s} ramp monotonic={bool((d >= -1e-4).all())} max step={d.max():.4f} "
          f"levels={steps}/480  clip hi {clip_hi:.2f}% lo {clip_lo:.2f}%  "
          f"mid-ramp rgb={np.round(grey.mean(0), 3)}  skin={np.round(out[(y0 + y1) // 2, (x0 + x1) // 2], 3)}")


def bamboo_sheet(look_luts):
    scene = bamboo_scene()
    tiles = [("untouched scene", scene)]
    print("== bamboo looks on the foggy-bamboo scene (Rec.709 in/out) ==")
    for name in BAMBOO_LOOKS:
        out = apply_lut(look_luts[name], scene)
        bamboo_report(name, look_luts[name], scene, out)
        tiles.append((name, out))
    cols, lab = 4, 22
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * BW, rows * (BH + lab)), (24, 24, 24))
    dr = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(size=14)
    except TypeError:
        font = ImageFont.load_default()
    for i, (name, arr) in enumerate(tiles):
        cx, cy = (i % cols) * BW, (i // cols) * (BH + lab)
        sheet.paste(Image.fromarray(to_u8(arr)), (cx, cy + lab))
        dr.text((cx + 4, cy + 3), name, fill=(235, 235, 235), font=font)
    sheet.save(os.path.join(HERE, "bamboo-sheet.png"))


def photo_sheet(look_luts):
    """Every creative look and tinted-blacks layer on a neutral reference photo (ref/street.jpg, AI-generated)."""
    src = os.path.join(HERE, "ref", "street.jpg")
    if not os.path.exists(src):
        return
    img = Image.open(src).convert("RGB")
    img = np.asarray(img.resize((384, 256), Image.LANCZOS), dtype=np.float64) / 255.0
    tiles = [("reference", img)]
    names = [f"{f}/{n}" for f, grp in CATEGORY_LOOKS.items() for n in grp] + list(BLACK_TINTS)
    for name in names:
        tiles.append((name, apply_lut(look_luts[name], img)))
    for k in ("blacks-teal", "blacks-amber"):
        tiles.append((f"teal-and-orange + {k}", apply_lut(look_luts[k], apply_lut(look_luts["cinematic/teal-and-orange"], img))))
    tw, th, lab, cols = 384, 256, 20, 6
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows * (th + lab)), (24, 24, 24))
    dr = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(size=14)
    except TypeError:
        font = ImageFont.load_default()
    for i, (name, arr) in enumerate(tiles):
        cx, cy = (i % cols) * tw, (i // cols) * (th + lab)
        sheet.paste(Image.fromarray(to_u8(arr)), (cx, cy + lab))
        dr.text((cx + 4, cy + 2), name, fill=(235, 235, 235), font=font)
    sheet.save(os.path.join(HERE, "photo-sheet.jpg"), quality=85)


def main():
    for f in glob.glob(os.path.join(OUT, "**", "*.cube"), recursive=True):
        os.remove(f)
    os.makedirs(OUT_IN, exist_ok=True)
    os.makedirs(OUT_LOOK, exist_ok=True)
    g = grid()

    # --- matrix checks -------------------------------------------------
    print("== gamut matrices: derived from published primaries vs manufacturer matrix ==")
    for nm, prim, pub in [("S-Gamut3.Cine", PSGAMUT3CINE, M_SGAMUT3CINE_PUB),
                          ("V-Gamut", PVGAMUT, M_VGAMUT_PUB), ("D-Gamut", PDGAMUT, M_DGAMUT_PUB)]:
        d = np.abs(derive_to_709(prim) - pub).max()
        print(f"  {nm}: max |derived - published| = {d:.5f}; row sums published = {np.round(pub.sum(1), 6)}")
    for nm in ("fujifilm-flog-to-rec709", "nikon-nlog-to-rec709"):
        print(f"  {nm}: BT.2020->709 derived, row sums {np.round(PROFILES[nm]['M'].sum(1), 6)}")

    # --- input transforms ----------------------------------------------
    in_luts = {}
    for name, prof in PROFILES.items():
        lut = np.clip(input_transform(prof, g), 0.0, 1.0)
        in_luts[name] = lut
        write_cube(os.path.join(OUT_IN, name + ".cube"), f"{prof['title']} to Rec.709",
                   [f"Input: {prof['title']}, normalized 10-bit code value / 1023. Output: Rec.709 display.",
                    f"Source: {prof['source']}.",
                    "Technical transform: log decode, gamut to Rec.709, Rec.709 OETF, soft highlight shoulder (knee 0.85),",
                    "gentle S-curve (g=1.15) pivoting on 18% grey = 0.409. No creative look."], lut)
    ident = g.copy()
    write_cube(os.path.join(OUT_IN, "rec709-passthrough.cube"), "Rec.709 passthrough (identity)",
               ["Identity LUT for footage that is already graded to Rec.709."], ident)

    # --- looks ----------------------------------------------------------
    look_luts = {}
    for name, look in LOOKS.items():
        lut = np.clip(look["fn"](g), 0.0, 1.0)
        look_luts[name] = lut
        write_cube(os.path.join(OUT_LOOK, name + ".cube"), look["title"],
                   ["Input and output: Rec.709 display-referred. Apply after any input transform.",
                    "Names ending -style are approximations, not measured or official stock profiles."], lut)

    for name, look in BAMBOO_LOOKS.items():
        lut = np.clip(look["fn"](g), 0.0, 1.0)
        look_luts[name] = lut
        write_cube(os.path.join(OUT_LOOK, name + ".cube"), look["title"],
                   ["Input and output: Rec.709 display-referred. Apply after any input transform.",
                    "Vivid looks for foggy foliage. Tone and colour only: a LUT cannot blur or add bloom."], lut)

    for folder, group in CATEGORY_LOOKS.items():
        os.makedirs(os.path.join(OUT_LOOK, folder), exist_ok=True)
        for name, lk in group.items():
            lut = np.clip(lk["fn"](g), 0.0, 1.0)
            look_luts[f"{folder}/{name}"] = lut
            # 4 decimals is ten times finer than a 10-bit step and keeps the APK smaller.
            write_cube(os.path.join(OUT_LOOK, folder, name + ".cube"), lk["title"],
                       ["Input and output: Rec.709 display-referred. Apply after any input transform."],
                       lut, digits=4)

    # --- anchor checks vs published code values ---------------------------
    print("== log anchors: computed 10-bit code vs published ==")
    for name, prof in PROFILES.items():
        parts = []
        for refl, pub in prof["anchors"].items():
            parts.append(f"{int(refl * 100)}%: {float(prof['enc'](np.array(refl))) * 1023:.1f} (published {pub})")
        if name == "nikon-nlog-to-rec709":
            parts.append(f"18%: {float(prof['enc'](np.array(0.18))) * 1023:.1f} (no table; formula only)")
        print(f"  {prof['label']}: " + ", ".join(parts))

    # --- neutral behaviour ---------------------------------------------
    print("== neutral behaviour per input transform (grey 18% / black), want ~0.409 / ~0 ==")
    for name, prof in PROFILES.items():
        lut = in_luts[name]
        g18 = apply_lut(lut, prof["enc"](np.array([[0.18, 0.18, 0.18]])))[0]
        blk = apply_lut(lut, prof["enc"](np.array([[0.0, 0.0, 0.0]])))[0]
        print(f"  {prof['label']}: grey {np.round(g18, 4)}, black {np.round(blk, 4)}")

    # --- cross-camera agreement vs S-Log3 -----------------------------
    ref = PROFILES["sony-slog3-to-rec709"]
    grey_p, colour_p = patch_set(None)
    print("== cross-camera agreement vs S-Log3 through the LUTs (max error, 10-bit code values) ==")
    print("   (same linear Rec.709 patches encoded with each camera's own log + gamut)")
    for name, prof in PROFILES.items():
        if name == "sony-slog3-to-rec709":
            continue
        res = {}
        for label, patches in (("grey", grey_p), ("colour", colour_p)):
            a = apply_lut(in_luts["sony-slog3-to-rec709"], scene_codes(ref, patches))
            b = apply_lut(in_luts[name], scene_codes(prof, patches))
            res[label] = np.abs(a - b).max() * 1023
        exact = np.abs(
            input_transform(prof, scene_codes(prof, np.vstack([grey_p, colour_p])))
            - apply_lut(in_luts[name], scene_codes(prof, np.vstack([grey_p, colour_p])))).max() * 1023
        print(f"  {prof['label']:7s}: grey {res['grey']:.2f}, colour {res['colour']:.2f}; "
              f"own-LUT interpolation error {exact:.2f}")
    lutref = apply_lut(in_luts["sony-slog3-to-rec709"], scene_codes(ref, np.vstack([grey_p, colour_p])))
    exactref = input_transform(ref, scene_codes(ref, np.vstack([grey_p, colour_p])))
    print(f"  S-Log3 own-LUT interpolation error: {np.abs(lutref - exactref).max() * 1023:.2f}")

    # --- blacks-* check --------------------------------------------------
    print("== blacks-* through S-Log3 input transform: black patch ==")
    blk_code = ref["enc"](np.array([[0.0, 0.0, 0.0]]))
    base = apply_lut(in_luts["sony-slog3-to-rec709"], blk_code)
    for k in BLACK_TINTS:
        out = apply_lut(look_luts[k], base)[0]
        dark = apply_lut(look_luts[k], np.array([[0.12, 0.12, 0.12]]))[0]
        print(f"  {k}: black -> {np.round(out, 3)}, 0.12 grey -> {np.round(dark, 3)}")

    # --- contact sheet -------------------------------------------------
    scene = scene_linear709()
    tiles = []
    for name, prof in PROFILES.items():
        tiles.append((f"{prof['label']} -> input LUT", apply_lut(in_luts[name], scene_codes(prof, scene))))
    base_img = apply_lut(in_luts["sony-slog3-to-rec709"], scene_codes(ref, scene))
    for name in LOOKS:
        tiles.append((f"S-Log3 + {name}", apply_lut(look_luts[name], base_img)))
    cols = 6
    rows = (len(tiles) + cols - 1) // cols
    lab = 22
    sheet = Image.new("RGB", (cols * W, rows * (H + lab)), (24, 24, 24))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.load_default(size=13)
    except TypeError:
        font = ImageFont.load_default()
    for i, (name, arr) in enumerate(tiles):
        cx, cy = (i % cols) * W, (i // cols) * (H + lab)
        sheet.paste(Image.fromarray(to_u8(arr)), (cx, cy + lab))
        d.text((cx + 4, cy + 3), name, fill=(235, 235, 235), font=font)
    sheet.save(os.path.join(HERE, "contact-sheet.png"))
    bamboo_sheet(look_luts)
    photo_sheet(look_luts)
    n_cat = sum(len(grp) for grp in CATEGORY_LOOKS.values())
    print(f"wrote {len(PROFILES) + 1} input LUTs, {len(LOOKS) + len(BAMBOO_LOOKS) + n_cat} looks, "
          "contact-sheet.png, bamboo-sheet.png")


if __name__ == "__main__":
    main()
