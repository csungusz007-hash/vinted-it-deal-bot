"""Az app ikonját rajzolja meg (app.ico). A build futtatja, Pillow kell hozzá."""
from PIL import Image, ImageDraw

S = 256
im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
d.rounded_rectangle([8, 8, S - 8, S - 8], radius=56, fill=(43, 87, 201, 255))
c, w = S // 2, 14
for r in (86, 54):
    d.arc([c - r, c - r, c + r, c + r], start=-40, end=270, fill="white", width=w)
d.ellipse([c - 17, c - 17, c + 17, c + 17], fill="white")
d.line([c + 10, c - 10, c + 78, c - 78], fill="white", width=w)
im.save("app.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("app.ico kész")
