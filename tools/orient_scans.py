"""Turn the raw card scans upright.

Usage:  python tools/orient_scans.py

Reads  Base/<Deck folder>/*.png   (raw scans, never modified)
Writes Base/cards-upright/<Deck folder>/<same name>.jpg

For an expansion, point REPO at that expansion's folder instead of Base.

Most scans are landscape images with the card lying on its side, so those get a
quarter turn. Portrait scans were all upside down, so they get a half turn.
Anything that still comes out wrong can be listed in EXTRA_TURN (degrees
counter-clockwise, applied last).
"""
import glob
import os
import sys

from PIL import Image

REPO = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "Base")
DECKS = ["Source", "Quality", "Delivery", "Graveyards", "Treasures", "Card Back and Wild Card",
         "Character Sheets"]

# (deck, file number) -> extra counter-clockwise degrees to apply to the finished card.
EXTRA_TURN = {}

# Character sheets were scanned in no consistent direction, so each one is listed
# (counter-clockwise degrees). Every hero has two scans:
#   "board" = HP track beside the art; upright means art on the left, track on the right
#   "art"   = the full-art side, upright as a portrait card
HERO_BOARD_UPSIDE_DOWN = {"0005", "0009", "0010", "0013", "0014", "0017", "0018", "0021",
                          "0025", "0026", "0029", "0030"}
HERO_BOARD_UPRIGHT = {"0006", "0022"}
HERO_BOARD_PORTRAIT = {"0001", "0003"}
HERO_ART_PORTRAIT = {"0002", "0004"}
HERO_ART_SIDEWAYS_OTHER_WAY = {"0008", "0024"}


def hero_turn(number):
    if number in HERO_BOARD_UPSIDE_DOWN:
        return 180
    if number in HERO_BOARD_UPRIGHT or number in HERO_ART_PORTRAIT:
        return 0
    if number in HERO_BOARD_PORTRAIT:
        return 270
    if number in HERO_ART_SIDEWAYS_OTHER_WAY:
        return 270
    return 90  # landscape art scans: card lying on its side


def upright(path, deck):
    im = Image.open(path).convert("RGB")
    number = os.path.basename(path)[-8:-4]
    if deck == "Character Sheets":
        turn = hero_turn(number) + EXTRA_TURN.get((deck, number), 0)
        return im.rotate(turn % 360, expand=True) if turn % 360 else im
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
