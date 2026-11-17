# -*- coding: utf-8 -*-
"""生成小程序图标（81x81 RGBA PNG），无第三方依赖。

线条走距离场 + 4x 超采样，避免锯齿。改图形只需改下面 *_ops() 的图元。

输出：
  assets/tabbar/  底部导航（未选中灰 / 选中橙）与购物车（白）
  assets/icons/   「我的」页宫格图标（深灰）
"""
import math
import os
import struct
import zlib

SIZE = 81
SS = 4          # 超采样倍数
STROKE = 6.0    # 线宽（81 坐标系内）

GRAY = (0x99, 0x99, 0x99)
ORANGE = (0xFF, 0x9F, 0x1C)
WHITE = (0xFF, 0xFF, 0xFF)
INK = (0x4A, 0x4A, 0x4A)

ROOT = os.path.dirname(os.path.abspath(__file__))


def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    L2 = vx * vx + vy * vy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L2))
    dx, dy = wx - t * vx, wy - t * vy
    return math.hypot(dx, dy)


def arc_points(cx, cy, r, a1, a2, steps=48):
    """圆弧离散成折线（屏幕坐标：y 向下）。"""
    pts = []
    for i in range(steps + 1):
        t = math.radians(a1 + (a2 - a1) * i / steps)
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    return pts


def round_rect_dist(px, py, cx, cy, hw, hh, r):
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    inside = min(max(qx, qy), 0.0)
    return abs(outside + inside - r)


def op_dist(op, x, y):
    kind = op[0]
    if kind == 'seg':
        return seg_dist(x, y, op[1], op[2], op[3], op[4])
    if kind == 'poly':
        pts = op[1]
        return min(seg_dist(x, y, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
                   for i in range(len(pts) - 1))
    if kind == 'ring':
        return abs(math.hypot(x - op[1], y - op[2]) - op[3])
    if kind == 'roundrect':
        return round_rect_dist(x, y, op[1], op[2], op[3], op[4], op[5])
    raise ValueError(kind)


def render(ops, color):
    hw = STROKE / 2.0
    raw = bytearray()
    for py in range(SIZE):
        raw.append(0)  # filter type 0
        for px in range(SIZE):
            hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = px + (sx + 0.5) / SS
                    y = py + (sy + 0.5) / SS
                    if min(op_dist(op, x, y) for op in ops) <= hw:
                        hits += 1
            a = int(round(255.0 * hits / (SS * SS)))
            raw += bytes((color[0], color[1], color[2], a))

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    return png


# ---------------- tabBar 图形 ----------------

def home_ops():
    return [
        ('seg', 17.0, 40.0, 40.5, 17.0),          # 屋顶左
        ('seg', 40.5, 17.0, 64.0, 40.0),          # 屋顶右
        ('seg', 23.0, 37.0, 23.0, 64.0),          # 左墙
        ('seg', 58.0, 37.0, 58.0, 64.0),          # 右墙
        ('seg', 23.0, 64.0, 58.0, 64.0),          # 地面
        ('poly', [(35.0, 64.0), (35.0, 49.0), (46.0, 49.0), (46.0, 64.0)]),  # 门
    ]


def menu_ops():
    return [
        ('poly', arc_points(40.5, 43.0, 23.0, 0, 180, 40)),   # 碗身
        ('seg', 14.0, 43.0, 67.0, 43.0),                      # 碗口
        ('seg', 63.0, 17.0, 46.0, 38.0),                      # 筷子
        ('seg', 55.0, 15.0, 39.0, 36.0),
    ]


def mine_ops():
    return [
        ('ring', 40.5, 30.0, 11.5),
        ('poly', arc_points(40.5, 72.0, 23.0, 200, 340, 40)),
    ]


def cart_ops():
    return [
        ('roundrect', 40.5, 49.0, 20.5, 19.0, 6.0),                   # 袋身
        ('poly', arc_points(40.5, 35.0, 12.0, 180, 360, 32)),         # 提手
    ]


# ---------------- 「我的」宫格图形 ----------------

def orders_ops():
    return [
        ('roundrect', 40.5, 40.5, 21.0, 25.0, 5.0),   # 单据外框
        ('seg', 30.0, 32.0, 51.0, 32.0),              # 三行内容
        ('seg', 30.0, 42.0, 51.0, 42.0),
        ('seg', 30.0, 52.0, 44.0, 52.0),
    ]


def address_ops():
    # 定位针：上圆环 + 两条斜线汇到底部尖端
    return [
        ('ring', 40.5, 33.0, 12.5),
        ('seg', 31.5, 42.5, 40.5, 66.0),
        ('seg', 49.5, 42.5, 40.5, 66.0),
    ]


def phone_ops():
    return [
        ('roundrect', 40.5, 40.5, 14.0, 25.0, 5.0),   # 机身
        ('seg', 34.0, 22.0, 47.0, 22.0),              # 听筒
        ('seg', 37.0, 58.0, 44.0, 58.0),              # home 键
    ]


def merchant_ops():
    return [
        ('seg', 16.0, 33.0, 65.0, 33.0),                       # 檐口
        ('poly', arc_points(24.5, 33.0, 8.5, 180, 360, 16)),    # 三段篷形
        ('poly', arc_points(40.5, 33.0, 8.5, 180, 360, 16)),
        ('poly', arc_points(56.5, 33.0, 8.5, 180, 360, 16)),
        ('poly', [(23.0, 33.0), (23.0, 64.0), (58.0, 64.0), (58.0, 33.0)]),  # 店面
        ('poly', [(36.0, 64.0), (36.0, 49.0), (45.0, 49.0), (45.0, 64.0)]),  # 门
    ]


TARGETS = [
    ('tabbar', 'home.png', home_ops, GRAY),
    ('tabbar', 'home-on.png', home_ops, ORANGE),
    ('tabbar', 'menu.png', menu_ops, GRAY),
    ('tabbar', 'menu-on.png', menu_ops, ORANGE),
    ('tabbar', 'mine.png', mine_ops, GRAY),
    ('tabbar', 'mine-on.png', mine_ops, ORANGE),
    ('tabbar', 'cart.png', cart_ops, WHITE),
    ('icons', 'orders.png', orders_ops, INK),
    ('icons', 'address.png', address_ops, INK),
    ('icons', 'phone.png', phone_ops, INK),
    ('icons', 'merchant.png', merchant_ops, INK),
]


def main():
    for folder, name, ops_fn, color in TARGETS:
        out_dir = os.path.join(ROOT, folder)
        os.makedirs(out_dir, exist_ok=True)
        data = render(ops_fn(), color)
        with open(os.path.join(out_dir, name), 'wb') as f:
            f.write(data)
        print(folder + '/' + name, len(data), 'bytes')


if __name__ == '__main__':
    main()
