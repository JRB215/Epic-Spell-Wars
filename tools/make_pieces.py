"""Prepares the loose game pieces (tokens, markers, towers) for the game screens.

Usage:  python tools/make_pieces.py

Reads  Base/<name>.png  (and Expansion 1/Tower 2.png), which are never modified.
Writes Base/cards-upright/Pieces/<short name>.png: turned upright, cleaned of scanner halos, trimmed of empty
margins and made small enough for a web page. Copy that folder into the assets folder along with the card pictures.
"""
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

Image.MAX_IMAGE_PIXELS = None
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "Base", "cards-upright", "Pieces")

# short name -> (source file relative to the repo, longest side in pixels, clockwise turn in degrees, clean scanner halo)
PIECES = {
    "lws": ("Base/Last Wizard Standing token.png", 256, 90, True),  # scanned on its side
    "skull": ("Base/skull token.png", 192, 90, True),      # scanned on its side
    "marker": ("Base/marker.png", 192, 90, True),          # the blood drop: scanned with its point to the right
    "tower1": ("Base/Tower 1.png", 1000, 180, False),      # scanned upside down
    "tower2": ("Expansion 1/Tower 2.png", 1000, 0, False),
}


def clean(im):
    """Removes the faint halo and specks the scanner left around a piece, keeping the solid shape."""
    alpha = im.getchannel("A")
    solid = alpha.point(lambda v: 255 if v > 140 else 0)
    solid = solid.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.MaxFilter(5))
    im.putalpha(ImageChops.multiply(alpha, solid))
    return im


def trim(im):
    """Cuts away fully transparent margins."""
    box = im.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()
    return im.crop(box) if box else im


# The five glyph icons are cut from the "Magical Glyphs" list in the base rulebook (Part 2, first page).
# glyph -> vertical position of its icon on a 150 dpi render of that page (the icons all sit at x = 108).
GLYPHS = {  # glyph -> (x, y) of the icon's centre on a 150 dpi render of that page
    "arcane": (111.9, 1012.0), "dark": (112.2, 1063.3), "elemental": (107.5, 1119.6),
    "illusion": (111.4, 1169.7), "primal": (111.1, 1221.2),
}
GLYPH_DIAMETER = 37  # in 150 dpi pixels
# The elemental icon's red ring is too dark for the automatic centring, so its centre is set by hand (150 dpi pixels).
GLYPH_FIXED_CENTRE = {"elemental": (113.0, 1118.0)}
GLYPH_PDF = os.path.join(REPO, "Base", "ESW_Rulebook_Part2.pdf")


def make_glyphs():
    """Cuts the glyph icons out of the rulebook page as round pictures with a transparent background."""
    if not os.path.isfile(GLYPH_PDF):
        print("skipped glyph icons: Base/ESW_Rulebook_Part2.pdf not found")
        return
    import pymupdf  # only needed for this step

    page = pymupdf.open(GLYPH_PDF)[0]
    pix = page.get_pixmap(dpi=400)
    sheet = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
    s = 400 / 150
    for name, (gx, gy) in GLYPHS.items():
        cx, cy, r = int(gx * s), int(gy * s), int(22 * s)
        crop = sheet.crop((cx - r, cy - r, cx + r, cy + r))
        # The icon is the bright part on the dark red page: nudge the centre onto it (within the small window,
        # so neighbouring icons are never picked up), then cut a fixed-size round icon.
        bright = crop.convert("L").point(lambda v: 255 if v > 120 else 0).filter(ImageFilter.MinFilter(7))
        box = bright.getbbox()
        mx, my = ((box[0] + box[2]) // 2, (box[1] + box[3]) // 2) if box else (r, r)
        if name in GLYPH_FIXED_CENTRE:
            fx, fy = GLYPH_FIXED_CENTRE[name]
            mx, my = r + int((fx - gx) * s), r + int((fy - gy) * s)
        half = int(GLYPH_DIAMETER * s / 2)
        big = sheet.crop((cx - r + mx - half, cy - r + my - half, cx - r + mx + half, cy - r + my + half))
        icon = big.convert("RGBA")
        mask = Image.new("L", icon.size, 0)
        ImageDraw.Draw(mask).ellipse((2, 2, icon.width - 3, icon.height - 3), fill=255)
        icon.putalpha(mask)
        icon = icon.resize((128, 128), Image.LANCZOS)
        icon.save(os.path.join(OUT, f"glyph-{name}.png"), optimize=True)
        print(f"glyph-{name}: 128x128")


def main():
    os.makedirs(OUT, exist_ok=True)
    make_glyphs()
    for name, (relative, longest, clockwise, halo) in PIECES.items():
        source = os.path.join(REPO, *relative.split("/"))
        if not os.path.isfile(source):
            print(f"skipped {name}: {relative} not found")
            continue
        im = Image.open(source).convert("RGBA")
        if halo:
            im = clean(im)
        if clockwise:
            im = im.rotate(-clockwise, expand=True, resample=Image.BICUBIC)
        im = trim(im)
        scale = longest / max(im.size)
        if scale < 1:
            im = im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))), Image.LANCZOS)
        im.save(os.path.join(OUT, name + ".png"), optimize=True)
        print(f"{name}: {im.size[0]}x{im.size[1]}")


if __name__ == "__main__":
    sys.exit(main())
