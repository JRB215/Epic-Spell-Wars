"""Prepares the loose game pieces (tokens, markers, towers) for the game screens.

Usage:  python tools/make_pieces.py

Reads  Base/<name>.png  (and Expansion 1/Tower 2.png), which are never modified.
Writes Base/cards-upright/Pieces/<short name>.png: turned upright, cleaned of scanner halos, trimmed of empty
margins and made small enough for a web page. Copy that folder into the assets folder along with the card pictures.
"""
import os
import sys

from PIL import Image, ImageChops, ImageFilter

Image.MAX_IMAGE_PIXELS = None
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "Base", "cards-upright", "Pieces")

# short name -> (source file relative to the repo, longest side in pixels, clockwise turn in degrees, clean scanner halo)
PIECES = {
    "lws": ("Base/Last Wizard Standing token.png", 256, 0, True),
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


def main():
    os.makedirs(OUT, exist_ok=True)
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
