"""Older line-art generator. The live Chud MAXXX badge is logo_maxxx.jpg, already
written into brand_mascot.png, the launcher icons, and dial_banner.png.
Do not run this script over those files; it would put the old face back.
"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "app", "tv", "src", "main", "res")
SOURCE = os.path.join(HERE, "logo_source.jpeg")
DISPLAY_FONT = os.path.join(RES, "font", "audiowide_regular.ttf")
CJK_FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"

NIGHT = (11, 10, 31)
FACE = (29, 22, 70)
CYAN = (25, 240, 255)
PALE_CYAN = (170, 250, 255)
MAGENTA = (255, 43, 214)


def masks():
    src = Image.open(SOURCE).convert("L")
    ink = src.point(lambda v: 255 if v < 128 else 0)
    closed = ink.filter(ImageFilter.MaxFilter(5))
    region = ImageOps.invert(closed)
    for corner in [(0, 0), (src.width - 1, 0), (0, src.height - 1), (src.width - 1, src.height - 1)]:
        ImageDraw.floodfill(region, corner, 128)
    face = region.point(lambda v: 0 if v == 128 else 255)
    return ink, face


def grid(draw, box, color, alpha, width):
    """Retro perspective floor grid inside box = (x0, y0, x1, y1)."""
    x0, y0, x1, y1 = box
    cx = (x0 + x1) / 2
    for i in range(-10, 11):
        draw.line([(cx + i * (x1 - x0) * 0.02, y0), (cx + i * (x1 - x0) * 0.16, y1)],
                  fill=color + (alpha,), width=width)
    h = y1 - y0
    for k in range(1, 8):
        y = y0 + h * (k / 7) ** 1.8
        draw.line([(x0, y), (x1, y)], fill=color + (alpha,), width=width)


def scanlines(img, spacing, alpha):
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    for y in range(0, img.height, spacing):
        d.line([(0, y), (img.width, y)], fill=(0, 0, 0, alpha), width=max(1, spacing // 2))
    img.alpha_composite(over)


def badge(size):
    S = size * 3
    ink, face = masks()
    art = Image.new("RGBA", (S, S), NIGHT + (255,))
    d = ImageDraw.Draw(art, "RGBA")
    grid(d, (0, int(S * 0.62), S, S), MAGENTA, 70, max(2, S // 300))
    # face: scaled to 80% of the badge height, centred, slightly low
    h = int(S * 0.80)
    w = int(ink.width * h / ink.height)
    # Thicken the ink before shrinking, more for small sizes, so lines survive at icon size.
    k = max(3, int(ink.height / h * 1.6)) | 1
    thick = ink.filter(ImageFilter.MaxFilter(k)).resize((w, h), Image.LANCZOS)
    face_s = face.resize((w, h), Image.LANCZOS)
    ox, oy = (S - w) // 2, int(S * 0.13)
    art.paste(Image.new("RGBA", (w, h), FACE + (255,)), (ox, oy), face_s)
    shift = max(3, S // 110)
    art.paste(Image.new("RGBA", (w, h), MAGENTA + (255,)), (ox + shift, oy + shift // 2), thick)
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    glow.paste(Image.new("RGBA", (w, h), CYAN + (255,)), (ox, oy), thick)
    art.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S / 90)))
    art.paste(Image.new("RGBA", (w, h), PALE_CYAN + (255,)), (ox, oy), thick)
    scanlines(art, max(3, S // 170), 55)
    # disc with neon rings
    disc = Image.new("L", (S, S), 0)
    ImageDraw.Draw(disc).ellipse([S * 0.02, S * 0.02, S * 0.98, S * 0.98], fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(art, (0, 0), disc)
    rings = ImageDraw.Draw(out)
    rings.ellipse([S * 0.02, S * 0.02, S * 0.98, S * 0.98], outline=CYAN, width=int(S * 0.022))
    rings.ellipse([S * 0.055, S * 0.055, S * 0.945, S * 0.945], outline=MAGENTA + (180,), width=max(2, S // 200))
    return out.resize((size, size), Image.LANCZOS)


def icon(size):
    S = size * 3
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.2), fill=NIGHT + (255,))
    b = badge(int(S * 0.88) // 3).resize((int(S * 0.88), int(S * 0.88)), Image.LANCZOS)
    img.alpha_composite(b, ((S - b.width) // 2, (S - b.height) // 2))
    return img.resize((size, size), Image.LANCZOS)


def banner(w=320, h=180):
    S = 3
    W, H = w * S, h * S
    img = Image.new("RGBA", (W, H), NIGHT + (255,))
    grid(ImageDraw.Draw(img, "RGBA"), (0, int(H * 0.66), W, H), MAGENTA, 80, 2)
    b = badge(int(H * 0.82) // S * S // S).resize((int(H * 0.82), int(H * 0.82)), Image.LANCZOS)
    img.alpha_composite(b, (int(W * 0.04), (H - b.height) // 2))
    d = ImageDraw.Draw(img)
    x = int(W * 0.04) + b.width + int(W * 0.045)
    avail = W - x - int(W * 0.04)

    def fit(text, font_path, target):
        size = int(target)
        while size > 8:
            f = ImageFont.truetype(font_path, size)
            bb = d.textbbox((0, 0), text, font=f)
            if bb[2] - bb[0] <= avail:
                return f, bb
            size -= 2
        f = ImageFont.truetype(font_path, 8)
        return f, d.textbbox((0, 0), text, font=f)

    top_f, top_bb = fit("CHUD", DISPLAY_FONT, H * 0.30)
    mid_f, mid_bb = fit("MAXXX", DISPLAY_FONT, H * 0.22)
    kata = "チャッド・マックス"
    kata_f, kata_bb = fit(kata, CJK_FONT, H * 0.075) if os.path.exists(CJK_FONT) else (None, (0, 0, 0, 0))
    gap = H * 0.035
    total = (top_bb[3] - top_bb[1]) + gap + (mid_bb[3] - mid_bb[1]) + (gap + kata_bb[3] - kata_bb[1] if kata_f else 0)
    y = (H - total) / 2
    shift = max(2, H // 160)
    d.text((x + shift, y - top_bb[1] + shift), "CHUD", font=top_f, fill=MAGENTA)
    d.text((x, y - top_bb[1]), "CHUD", font=top_f, fill=CYAN)
    y += (top_bb[3] - top_bb[1]) + gap
    d.text((x, y - mid_bb[1]), "MAXXX", font=mid_f, fill=MAGENTA)
    if kata_f:
        y += (mid_bb[3] - mid_bb[1]) + gap
        d.text((x, y - kata_bb[1]), kata, font=kata_f, fill=PALE_CYAN)
    scanlines(img, 3, 40)
    return img.resize((w, h), Image.LANCZOS).convert("RGB")


if __name__ == "__main__":
    for folder, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        icon(px).save(os.path.join(RES, f"mipmap-{folder}", "ic_launcher.png"))
    banner().save(os.path.join(RES, "drawable-xhdpi", "dial_banner.png"))
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    badge(512).save(os.path.join(RES, "drawable-nodpi", "brand_mascot.png"))
    print("Icons, banner and splash badge written to", RES)
