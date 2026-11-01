#!/usr/bin/env python3
"""Generates the F04-S02 brand image fixtures in this folder: plain shapes and gradients, no
photographs and nothing personal. Run with Pillow installed (`python3 generate.py`); the files are
committed, so a test run needs no Python. Large and hostile files (over 5 MB, decompression bombs)
are built by the tests themselves in qa/shared/salon-brand.ts and never committed."""
import os
from PIL import Image, ImageDraw
from PIL.PngImagePlugin import PngInfo

HERE = os.path.dirname(os.path.abspath(__file__))
out = lambda name: os.path.join(HERE, name)
RED, BLUE, GREEN, WHITE = (255, 0, 0), (0, 0, 255), (0, 160, 80), (255, 255, 255)


def banded_logo():
    # 700 x 600: a 50-pixel red band at each side and blue between, so a centre-square crop
    # (x 50 to 650) keeps no red. A text chunk carries a marker that re-encoding must drop.
    image = Image.new('RGB', (700, 600), BLUE)
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, 49, 599], fill=RED)
    draw.rectangle([650, 0, 699, 599], fill=RED)
    info = PngInfo()
    info.add_text('Comment', 'QA-PNG-MARKER-7f3a')
    info.add_text('Author', 'QA-PNG-AUTHOR-7f3a')
    image.save(out('logo-700x600-banded.png'), pnginfo=info)


def gradient(size, start, end):
    width, height = size
    image = Image.new('RGB', size)
    draw = ImageDraw.Draw(image)
    for x in range(width):
        t = x / max(1, width - 1)
        draw.line([(x, 0), (x, height)], fill=tuple(int(a + (b - a) * t) for a, b in zip(start, end)))
    return image


def exif_cover():
    image = gradient((2000, 800), (214, 8, 106), (47, 75, 138))
    exif = Image.Exif()
    exif[0x010F] = 'QA-CAMERA-MAKE-7f3a'      # Make
    exif[0x0110] = 'QA-CAMERA-MODEL-7f3a'     # Model
    exif[0x0131] = 'QA-SOFTWARE-7f3a'         # Software
    gps = exif.get_ifd(0x8825)
    gps[1] = 'N'; gps[2] = (6.0, 54.0, 21.96)  # 6.9061 N
    gps[3] = 'E'; gps[4] = (79.0, 51.0, 39.96) # 79.8611 E
    image.save(out('cover-2000x800-exif.jpg'), quality=85, exif=exif.tobytes(), comment=b'QA-JPEG-COMMENT-7f3a')


def circle(size, colour):
    image = Image.new('RGB', size, WHITE)
    ImageDraw.Draw(image).ellipse([10, 10, size[0] - 10, size[1] - 10], fill=colour)
    return image


def main():
    banded_logo()
    exif_cover()
    circle((300, 300), GREEN).save(out('logo-300.webp'), quality=80)
    gradient((1000, 400), (47, 107, 94), (204, 85, 0)).save(out('cover-1000x400.webp'), quality=80)
    circle((128, 128), BLUE).save(out('logo-128-min.png'))
    gradient((800, 300), (20, 20, 19), (138, 75, 107)).save(out('cover-800x300-min.jpg'), quality=85)
    circle((127, 127), BLUE).save(out('logo-127-small.png'))
    gradient((900, 299), (20, 20, 19), (138, 75, 107)).save(out('cover-900x299-short.jpg'), quality=85)
    gradient((900, 1200), (20, 20, 19), (138, 75, 107)).save(out('cover-900x1200-tall.jpg'), quality=85)
    circle((300, 300), RED).save(out('logo-300.gif'))
    circle((300, 300), RED).save(out('logo-300.bmp'))
    with open(out('logo-300.svg'), 'w') as f:
        f.write('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="300"><circle cx="150" cy="150" r="140" fill="red"/></svg>\n')
    with open(out('not-an-image.txt'), 'w') as f:
        f.write('This is a plain text file pretending to be a logo.\n' * 20)
    with open(out('logo-700x600-banded.png'), 'rb') as f:
        data = f.read()
    with open(out('logo-truncated.png'), 'wb') as f:
        f.write(data[: len(data) // 2])


if __name__ == '__main__':
    main()
