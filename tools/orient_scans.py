"""Turn the raw card scans upright.

Usage:  python tools/orient_scans.py

Reads  <repo>/<Deck folder>/*.png   (raw scans, never modified)
Writes <repo>/cards-upright/<Deck folder>/<same name>.jpg

Most scans are landscape images with the card lying on its side, so those get a
quarter turn. Portrait scans were all upside down, so they get a half turn.
Anything that still comes out wrong can be listed in EXTRA_TURN (degrees
counter-clockwise, applied last).
"""
import glob
import os
import sys

from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DECKS = ["Source", "Quality", "Delivery", "Graveyards", "Treasures", "Card Back and Wild Card"]

# (deck, file number) -> extra counter-clockwise degrees to apply to the finished card.
EXTRA_TURN = {}


def upright(path, deck):
    im = Image.open(path).convert("RGB")
    number = os.path.basename(path)[-8:-4]
    if im.width > im.height:
        im = im.rotate(270, expand=True)  # card was lying on its side
    else:
        im = im.rotate(180, expand=True)  # portrait scans were all upside down
    extra = EXTRA_TURN.get((deck, number), 0)
    if extra:
        im = im.rotate(extra, expand=True)
    return im


def main():
    total = 0
    for deck in DECKS:
        out_dir = os.path.join(REPO, "cards-upright", deck)
        os.makedirs(out_dir, exist_ok=True)
        for path in sorted(glob.glob(os.path.join(REPO, deck, "*.png"))):
            im = upright(path, deck)
            name = os.path.splitext(os.path.basename(path))[0] + ".jpg"
            im.save(os.path.join(out_dir, name), quality=92)
            total += 1
    print(f"Wrote {total} upright cards to {os.path.join(REPO, 'cards-upright')}")


if __name__ == "__main__":
    sys.exit(main())
