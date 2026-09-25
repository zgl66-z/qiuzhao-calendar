# -*- coding: utf-8 -*-
"""生成「秋招截止闹钟」的自适应图标（前景 + 背景两层）以及一张预览图。

设计：蓝色渐变底 + 白色闹钟（表盘/铃铛/指针），时针用暖色做视觉焦点。
自适应图标规范：画布 108x108，可见安全区是居中 72x72，所以内容要画在中心。

用法： python tools/make_icon.py
产物：
  res/mipmap-*/ic_launcher_foreground.png   前景（白色闹钟，透明底）
  res/mipmap-*/ic_launcher_background.png   背景（蓝色渐变）
  res/mipmap-*/ic_launcher.png              传统图标（合成后圆形裁切，兜底用）
  icon_preview.png                          预览：圆形与方圆形两种裁切效果
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)

# 自适应图标：画布 108x108dp，密度倍数 → 像素
ADAPTIVE = [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]
# 传统图标：48dp 基准
LEGACY = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]

BLUE_HI = (46, 99, 184)      # #2E63B8
BLUE_LO = (23, 55, 107)      # #17376B
WHITE = (255, 255, 255)
ACCENT = (255, 209, 102)     # #FFD166

SS = 4  # 超采样倍数，用来得到平滑边缘

# ---- 108 单位坐标系里的形状 ----
CIRCLE_C = (54.0, 58.0)
CIRCLE_R = 22.0
STROKE = 7.0
BELL_L = ((33.5, 36.5), (41.0, 29.0))
BELL_R = ((74.5, 36.5), (67.0, 29.0))
HAND_MIN = ((54.0, 58.0), (54.0, 44.0))
HAND_HOUR = ((54.0, 58.0), (64.5, 63.0))


def gradient(size, c1, c2):
    """左上到右下的线性渐变。"""
    img = Image.new('RGB', (size, size))
    px = img.load()
    denom = 2.0 * max(size - 1, 1)
    for y in range(size):
        for x in range(size):
            t = (x + y) / denom
            px[x, y] = (
                int(c1[0] + (c2[0] - c1[0]) * t),
                int(c1[1] + (c2[1] - c1[1]) * t),
                int(c1[2] + (c2[2] - c1[2]) * t),
            )
    return img.convert('RGBA')


def cap(d, p, r, fill):
    """圆头线段的一端：画个实心圆，模拟 round cap。"""
    d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=fill)


def draw_clock(size):
    """在 size x size 的透明画布上画白色闹钟（含超采样）。"""
    S = size * SS
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = S / 108.0                      # 108 单位 → 像素
    w = STROKE * k
    r = w / 2.0

    # 铃铛（两根圆头斜线）
    for a, b in (BELL_L, BELL_R):
        p1 = (a[0] * k, a[1] * k)
        p2 = (b[0] * k, b[1] * k)
        d.line([p1, p2], fill=WHITE, width=int(round(w)))
        cap(d, p1, r, WHITE)
        cap(d, p2, r, WHITE)

    # 表盘
    cx, cy = CIRCLE_C[0] * k, CIRCLE_C[1] * k
    rr = CIRCLE_R * k
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
              outline=WHITE, width=int(round(w)))

    # 指针：分针白色，时针暖色
    for (a, b), col in ((HAND_MIN, WHITE), (HAND_HOUR, ACCENT)):
        p1 = (a[0] * k, a[1] * k)
        p2 = (b[0] * k, b[1] * k)
        d.line([p1, p2], fill=col, width=int(round(w)))
        cap(d, p1, r, col)
        cap(d, p2, r, col)

    return img.resize((size, size), Image.LANCZOS)


def compose(bg, fg):
    out = bg.copy()
    out.alpha_composite(fg)
    return out


def round_mask(size, shape='circle'):
    """超采样画掩膜，边缘更平滑。"""
    S = size * SS
    m = Image.new('L', (S, S), 0)
    d = ImageDraw.Draw(m)
    inset = S * 0.02
    if shape == 'circle':
        d.ellipse([inset, inset, S - inset, S - inset], fill=255)
    else:  # 方圆形（近似 Android 的 squircle）
        rad = S * 0.28
        d.rounded_rectangle([inset, inset, S - inset, S - inset], radius=rad, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def adaptive_preview(size, shape='circle', frac=0.667):
    """
    按自适应图标的真实合成方式预览：
    - 背景层：全出血铺满整个 108 画布（不能缩）
    - 前景层：只占中心 72/108，居中
    - 掩膜：覆盖中心约 72/108 的区域（各启动器略有差异）
    """
    S = size * SS
    bg = gradient(size, BLUE_HI, BLUE_LO)
    inner = int(round(size * frac))
    clock = draw_clock(inner)
    layer = bg.copy()
    off = (size - inner) // 2
    layer.alpha_composite(clock, (off, off))

    m = Image.new('L', (S, S), 0)
    d = ImageDraw.Draw(m)
    inset = (1.0 - frac) / 2.0 * S
    if shape == 'circle':
        d.ellipse([inset, inset, S - inset, S - inset], fill=255)
    else:
        d.rounded_rectangle([inset, inset, S - inset, S - inset],
                            radius=S * 0.17, fill=255)
    mask = m.resize((size, size), Image.LANCZOS)

    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(layer, (0, 0), mask)
    return out


def save(img, *parts):
    path = os.path.join(PROJ, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    return path


def main():
    made = []
    for dens, mult in ADAPTIVE:
        size = int(round(108 * mult))          # 前景/背景画布
        bg = gradient(size, BLUE_HI, BLUE_LO)
        fg = draw_clock(size)
        made.append(save(fg, 'res', 'mipmap-' + dens, 'ic_launcher_foreground.png'))
        made.append(save(bg, 'res', 'mipmap-' + dens, 'ic_launcher_background.png'))

    for dens, size in LEGACY:
        bg = gradient(size, BLUE_HI, BLUE_LO)
        fg = draw_clock(size)
        flat = compose(bg, fg)
        # 传统图标按 108 画布缩放：可见区约为 72/108，这里直接整体略微放大再裁圆
        flat = flat.resize((int(size * 1.0), int(size * 1.0)), Image.LANCZOS)
        mask = round_mask(size, 'circle')
        legacy = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        legacy.paste(flat, (0, 0), mask)
        made.append(save(legacy, 'res', 'mipmap-' + dens, 'ic_launcher.png'))

    # 预览：按自适应图标的真实合成方式（背景全出血 + 前景居中 66%）展示两种裁切
    P = 540
    prev = Image.new('RGBA', (P * 2 + 60, P + 40), (246, 248, 251, 255))
    for i, shape in enumerate(('circle', 'squircle')):
        prev.alpha_composite(adaptive_preview(P, shape), (20 + i * (P + 20), 20))
    made.append(save(prev, 'icon_preview.png'))

    # 桌面上的实际观感：48dp 圆形图标，放大 4 倍用最近邻查看像素
    icon48 = adaptive_preview(48, 'circle')
    made.append(save(icon48.resize((192, 192), Image.NEAREST), 'icon_preview_48px.png'))

    print('已生成 %d 个文件：' % len(made))
    for p in made:
        print('  ', os.path.relpath(p, PROJ))


if __name__ == '__main__':
    main()
