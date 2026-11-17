"""Draws the picture the desktop app shows the instant it is started, while the app itself is loading:
the mark and the name on the app's own light background. Usage: python3 scripts/make_splash.py   (needs Pillow)"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
W, H = 440, 300
BG, EDGE, INK, INDIGO, SOFT = (252, 248, 255), (230, 224, 236), (28, 27, 32), (63, 75, 196), (72, 69, 78)

img = Image.new('RGB', (W, H), EDGE)
d = ImageDraw.Draw(img)
d.rectangle([1, 1, W - 2, H - 2], fill=BG)                      # a hairline edge, so it reads as a card on any desktop
mark = Image.open(os.path.join(ROOT, 'desktop/icons/icon.png')).resize((112, 112), Image.LANCZOS)
img.paste(mark, ((W - 112) // 2, 52), mark)
fonts = os.path.join(ROOT, 'app/src/main/res/font')
name = ImageFont.truetype(os.path.join(fonts, 'heros_bold.otf'), 40)
line = ImageFont.truetype(os.path.join(fonts, 'inter_regular.ttf'), 15)
d.text((W / 2, 196), 'DCrux', font=name, fill=INDIGO, anchor='mm')
d.text((W / 2, 238), "The day's crux of current affairs", font=line, fill=SOFT, anchor='mm')
out = os.path.join(ROOT, 'desktop/icons/splash.png')
img.save(out, optimize=True)
print('saved', os.path.relpath(out, ROOT))
