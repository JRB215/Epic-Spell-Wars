"""Turn the raw card scans upright.

Usage:  python tools/orient_scans.py            (the base game)
        python tools/orient_scans.py "Expansion 1"

Reads  <set>/<Deck folder>/*.png   (raw scans, never modified)
Writes <set>/cards-upright/<Deck folder>/<same name>.jpg

Most scans are landscape images with the card lying on its side, so those get a
quarter turn. In the base game the portrait scans were all upside down, so they get
a half turn. In Expansion 1 only three portrait scans were upside down (listed in
EXPANSION_PORTRAIT_UPSIDE_DOWN); the other portrait scans, including the rescans,
are already upright. Anything that still comes out wrong can be listed in
EXTRA_TURN (degrees counter-clockwise, applied last).
"""
import glob
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SET_NAME = sys.argv[1] if len(sys.argv) > 1 else "Base"
REPO = os.path.join(ROOT, SET_NAME)
DECKS_BY_SET = {
    "Base": ["Source", "Quality", "Delivery", "Graveyards", "Treasures", "Card Back and Wild Card",
             "Character Sheets"],
    "Expansion 1": ["Source", "Quality", "Delivery", "Treasures", "Wild Magic", "Dead Wizard"],
}
DECKS = DECKS_BY_SET[SET_NAME]
EXPANSION_PORTRAIT_UPSIDE_DOWN = {"0043", "0045", "0047"}

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
    elif SET_NAME == "Base" or number in EXPANSION_PORTRAIT_UPSIDE_DOWN:
        im = im.rotate(180, expand=True)  # these portrait scans were upside down
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
