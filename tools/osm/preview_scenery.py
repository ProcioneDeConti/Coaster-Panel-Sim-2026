# Renders app/build/scenery-preview/*.txt (written by SceneryTest) to PNGs, plus a contact sheet, for tuning by eye.
import glob, os, sys
from PIL import Image, ImageDraw
d = sys.argv[1] if len(sys.argv) > 1 else 'app/build/scenery-preview'
K, OX, OY = 8, 120, 100
tiles = []
for f in sorted(glob.glob(os.path.join(d, '*.txt'))):
    im = Image.new('RGB', (1040, 640), (15, 26, 36)); g = ImageDraw.Draw(im)
    rows = [l.split() for l in open(f, encoding='utf-8') if l.strip()]
    title = rows[0]
    pts = lambda r: [(OX + float(r[i]) * K, OY + float(r[i + 1]) * K) for i in range(1, len(r) - 1, 2)]
    for kind in 'EKWLTR':
        for r in rows[1:]:
            if r[0] != kind: continue
            p = pts(r)
            if kind == 'E': g.polygon(p, fill=(20, 53, 82))
            if kind == 'K': g.line(p + p[:1], fill=(140, 123, 87), width=10); g.polygon(p, fill=(15, 26, 36))
            if kind == 'W': g.polygon(p, fill=(20, 53, 82), outline=(46, 95, 134))
            if kind == 'L': g.polygon(p, fill=(15, 26, 36), outline=(46, 95, 134))
            if kind == 'T': x, y = p[0]; g.ellipse((x - 7, y - 7, x + 7, y + 7), fill=(40, 70, 40), outline=(165, 214, 167))
            if kind == 'R': g.line(p + p[:1], fill=(67, 160, 71), width=4)
    g.rectangle((OX, OY, OX + 100 * K, OY + 56 * K), outline=(90, 90, 90))
    g.text((10, 10), ' '.join(title[1:]) + '  ' + os.path.basename(f)[:-4], fill=(255, 255, 255))
    im.save(f[:-4] + '.png'); tiles.append(im)
cols = 3
sheet = Image.new('RGB', (cols * 520, ((len(tiles) + cols - 1) // cols) * 320))
for i, t in enumerate(tiles): sheet.paste(t.resize((520, 320)), ((i % cols) * 520, (i // cols) * 320))
sheet.save(os.path.join(d, 'sheet.png'))
print(len(tiles), 'previews')
