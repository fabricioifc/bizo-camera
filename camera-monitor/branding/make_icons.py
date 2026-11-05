"""Gera os ícones do app (legado e adaptativo) a partir da imagem do logotipo.

Uso: python make_icons.py <imagem> <pasta res> <arquivo de prévia>
"""
import os
import sys

from PIL import Image, ImageDraw

src, res_dir, preview_path = sys.argv[1], sys.argv[2], sys.argv[3]

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
BACKGROUND = (15, 17, 21, 255)  # #0F1115, o mesmo fundo do site e da splash

# 1) Fundo branco ligado às bordas vira transparente (o chapéu passa do círculo, então não dá
#    para só recortar um círculo).
image = Image.open(src).convert("RGBA")
w, h = image.size
for corner in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
    ImageDraw.floodfill(image, corner, (255, 255, 255, 0), thresh=60)

# 2) Recorta só o desenho e centraliza num quadrado transparente.
box = image.getchannel("A").getbbox()
logo = image.crop(box)
side = max(logo.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.paste(logo, ((side - logo.width) // 2, (side - logo.height) // 2), logo)


def fit(size_px, content_px):
    """Logo com content_px de lado, centralizado num quadro transparente de size_px."""
    canvas = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
    scaled = square.resize((content_px, content_px), Image.LANCZOS)
    offset = (size_px - content_px) // 2
    canvas.paste(scaled, (offset, offset), scaled)
    return canvas


for name, scale in DENSITIES.items():
    folder = os.path.join(res_dir, f"mipmap-{name}")
    os.makedirs(folder, exist_ok=True)
    # Ícone legado (Android 7 e anteriores): 48dp, o desenho quase ocupa tudo.
    legacy = fit(round(48 * scale), round(46 * scale))
    legacy.save(os.path.join(folder, "ic_launcher.png"), optimize=True)
    legacy.save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)
    # Frente do ícone adaptativo (Android 8+): 108dp, desenho dentro da área segura central.
    foreground = fit(round(108 * scale), round(68 * scale))
    foreground.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)

# 3) Prévia: ícone adaptativo com máscara redonda, sobre fundo escuro e claro.
size = 432
fg = fit(size, round(68 * 4))
icon = Image.new("RGBA", (size, size), BACKGROUND)
icon.alpha_composite(fg)
inner = icon.crop((72, 72, 360, 360))  # parte visível (72dp de 108dp)
mask = Image.new("L", inner.size, 0)
ImageDraw.Draw(mask).ellipse((0, 0, inner.width - 1, inner.height - 1), fill=255)
round_icon = Image.new("RGBA", inner.size, (0, 0, 0, 0))
round_icon.paste(inner, (0, 0), mask)

preview = Image.new("RGBA", (inner.width * 2 + 60, inner.height + 40), (240, 240, 240, 255))
preview.paste(Image.new("RGBA", (inner.width + 20, inner.height + 40), (30, 30, 30, 255)), (0, 0))
preview.alpha_composite(round_icon, (10, 20))
preview.alpha_composite(round_icon, (inner.width + 40, 20))
preview.convert("RGB").save(preview_path)
print("ok", square.size, "recorte", box)
