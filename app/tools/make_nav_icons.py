# -*- coding: utf-8 -*-
"""生成底部导航栏的三个图标（白色字形，透明底，由代码按选中状态染色）。

设计统一在 24x24 单位坐标系里画，超采样后缩小以获得平滑边缘。
用法： python tools/make_nav_icons.py
产物： res/drawable-*/nav_events.png / nav_add.png / nav_mine.png
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)

DENS = [('mdpi', 24), ('hdpi', 36), ('xhdpi', 48), ('xxhdpi', 72), ('xxxhdpi', 96)]
SS = 6          # 超采样倍数
WHITE = (255, 255, 255, 255)


def canvas(size):
    S = size * SS
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img), S / 24.0      # k: 24 单位 → 像素


def cap(d, p, r, fill=WHITE):
    d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=fill)


def bar(d, a, b, w, fill=WHITE):
    """圆头线段。"""
    w = max(int(round(w)), 1)
    d.line([a, b], fill=fill, width=w)
    for p in (a, b):
        cap(d, p, w / 2.0, fill)


def draw_events(size):
    """事件：三行「圆点 + 横条」的清单。"""
    img, d, k = canvas(size)
    w = 2.15 * k
    for i in range(3):
        y = (5.6 + i * 6.4) * k
        cap(d, (4.6 * k, y), 1.75 * k)
        bar(d, (9.0 * k, y), (19.4 * k, y), w)
    return img.resize((size, size), Image.LANCZOS)


def draw_add(size):
    """添加：粗加号。"""
    img, d, k = canvas(size)
    w = 2.6 * k
    bar(d, (12.0 * k, 5.1 * k), (12.0 * k, 18.9 * k), w)
    bar(d, (5.1 * k, 12.0 * k), (18.9 * k, 12.0 * k), w)
    return img.resize((size, size), Image.LANCZOS)


def draw_mine(size):
    """我的：头 + 肩。"""
    img, d, k = canvas(size)
    w = 2.5 * k
    # 头
    hr = 3.5 * k
    d.ellipse([12 * k - hr, 7.6 * k - hr, 12 * k + hr, 7.6 * k + hr],
              outline=WHITE, width=int(round(w)))
    # 肩：上半圆弧
    sr = 6.6 * k
    cy = 20.6 * k
    d.arc([12 * k - sr, cy - sr, 12 * k + sr, cy + sr],
          start=196, end=344, fill=WHITE, width=int(round(w)))
    return img.resize((size, size), Image.LANCZOS)


def main():
    names = {'nav_events': draw_events, 'nav_add': draw_add, 'nav_mine': draw_mine}
    n = 0
    for dens, size in DENS:
        for name, fn in names.items():
            path = os.path.join(PROJ, 'res', 'drawable-' + dens, name + '.png')
            os.makedirs(os.path.dirname(path), exist_ok=True)
            fn(size).save(path)
            n += 1
    print('已生成 %d 个导航图标' % n)

    # 预览：三种状态并排（未选中灰、选中蓝）
    P = 96
    prev = Image.new('RGBA', (P * 6 + 40, P + 40), (246, 248, 251, 255))
    colors = [(138, 147, 160, 255), (36, 86, 166, 255)]
    for i, (name, fn) in enumerate(names.items()):
        for j, col in enumerate(colors):
            ic = Image.new('RGBA', (P, P), (0, 0, 0, 0))
            g = fn(P)
            tinted = Image.new('RGBA', (P, P), col)
            tinted.putalpha(g.getchannel('A'))
            ic.alpha_composite(tinted)
            prev.alpha_composite(ic, (20 + (i * 2 + j) * P, 20))
    out = os.path.join(PROJ, 'nav_preview.png')
    prev.save(out)
    print('预览:', os.path.relpath(out, PROJ))


if __name__ == '__main__':
    main()
