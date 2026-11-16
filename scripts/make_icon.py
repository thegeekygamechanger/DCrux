"""Draws the DCrux app icon for the desktop: the black D-with-play mark centred on a white tile.
The geometry is the same as app/src/main/res/drawable/ic_launcher_foreground.xml (108 x 108 viewport,
of which the middle 72 is what a launcher shows).
Usage: python3 scripts/make_icon.py desktop/icons/icon.png desktop/icons/icon.ico   (needs Pillow)"""
import sys
from PIL import Image, ImageDraw

OUT = 512
SS = 4                      # drawn four times larger, then reduced, for smooth edges
N = OUT * SS
INK = (11, 11, 12, 255)
WHITE = (255, 255, 255, 255)
EDGE = (218, 218, 221, 255)

img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# The white tile: a rounded square with a hairline edge so it holds its shape on a white desktop.
margin = N * 0.035
radius = N * 0.225
d.rounded_rectangle([margin, margin, N - margin, N - margin], radius=radius, fill=EDGE)
hair = N * 0.004
d.rounded_rectangle([margin + hair, margin + hair, N - margin - hair, N - margin - hair], radius=radius - hair, fill=WHITE)

# Viewport units 14..94 map onto the tile, which leaves the mark a comfortable margin.
tile = N - 2 * margin
def X(v): return margin + (v - 14) / 80 * tile
def Y(v): return margin + (v - 14) / 80 * tile
def S(v): return v / 80 * tile

# The D: a bar with softly rounded left corners, joined to a half disc.
d.rounded_rectangle([X(33.5), Y(32), X(56), Y(76)], radius=S(2.5), fill=INK)
d.pieslice([X(53 - 22), Y(54 - 22), X(53 + 22), Y(54 + 22)], start=-90, end=90, fill=INK)
# Its counter: a play triangle with rounded corners (an inset triangle, thickened by a round-jointed outline).
r = 1.4
tri = [(46.5 + r, 45 + r * 1.9), (46.5 + r, 63 - r * 1.9), (61.2 - r * 0.9, 54)]
pts = [(X(x), Y(y)) for x, y in tri]
d.polygon(pts, fill=WHITE)
d.line(pts + [pts[0], pts[1]], fill=WHITE, width=int(S(2 * r)), joint="curve")
# The crux: one point.
d.ellipse([X(73 - 4.2), Y(35 - 4.2), X(73 + 4.2), Y(35 + 4.2)], fill=INK)

icon = img.resize((OUT, OUT), Image.LANCZOS)
icon.save(sys.argv[1])
icon.save(sys.argv[2], sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("saved", sys.argv[1], sys.argv[2])
