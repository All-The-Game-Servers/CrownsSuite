#!/usr/bin/env python3
"""Generate WorldPainter source masks for the Floor 1 vertical slice.

The output is intentionally deterministic and dependency-free so it can run on a
plain Windows Python install. WorldPainter remains the macro-terrain authoring
tool; these masks provide the first readable route plan for the 2k slice.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import zlib
from pathlib import Path


WORLD_NAME = "crowns_floor_1_wp_slice"


def write_png(path: Path, width: int, height: int, channels: int, data: bytearray) -> None:
    color_type = 0 if channels == 1 else 2
    raw = bytearray()
    row_len = width * channels
    for y in range(height):
        raw.append(0)
        raw.extend(data[y * row_len : (y + 1) * row_len])

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    path.parent.mkdir(parents=True, exist_ok=True)
    payload = b"\x89PNG\r\n\x1a\n"
    payload += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    payload += chunk(b"IEND", b"")
    path.write_bytes(payload)


def idx(width: int, x: int, y: int, channels: int) -> int:
    return (y * width + x) * channels


def clamp(value: float, lo: int = 0, hi: int = 255) -> int:
    return max(lo, min(hi, int(round(value))))


def set_gray(buffer: bytearray, width: int, x: int, y: int, value: int) -> None:
    if 0 <= x < width and 0 <= y < width:
        buffer[y * width + x] = clamp(value)


def set_rgb(buffer: bytearray, width: int, x: int, y: int, color: tuple[int, int, int]) -> None:
    if 0 <= x < width and 0 <= y < width:
        offset = idx(width, x, y, 3)
        buffer[offset : offset + 3] = bytes(color)


def blend_rgb(buffer: bytearray, width: int, x: int, y: int, color: tuple[int, int, int], alpha: float) -> None:
    if 0 <= x < width and 0 <= y < width:
        offset = idx(width, x, y, 3)
        for channel in range(3):
            buffer[offset + channel] = clamp(buffer[offset + channel] * (1.0 - alpha) + color[channel] * alpha)


def world_to_pixel(size: int, x: float, z: float) -> tuple[int, int]:
    center = size // 2
    return int(round(center + x)), int(round(center + z))


def disk_gray(buffer: bytearray, size: int, cx: int, cy: int, radius: int, value: int) -> None:
    radius_sq = radius * radius
    for y in range(cy - radius, cy + radius + 1):
        for x in range(cx - radius, cx + radius + 1):
            if (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius_sq:
                set_gray(buffer, size, x, y, value)


def disk_rgb(buffer: bytearray, size: int, cx: int, cy: int, radius: int, color: tuple[int, int, int], alpha: float = 1.0) -> None:
    radius_sq = radius * radius
    for y in range(cy - radius, cy + radius + 1):
        for x in range(cx - radius, cx + radius + 1):
            if (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius_sq:
                if alpha >= 1.0:
                    set_rgb(buffer, size, x, y, color)
                else:
                    blend_rgb(buffer, size, x, y, color, alpha)


def rect_gray(buffer: bytearray, size: int, x1: int, y1: int, x2: int, y2: int, value: int) -> None:
    for y in range(max(0, y1), min(size, y2 + 1)):
        for x in range(max(0, x1), min(size, x2 + 1)):
            set_gray(buffer, size, x, y, value)


def rect_rgb(buffer: bytearray, size: int, x1: int, y1: int, x2: int, y2: int, color: tuple[int, int, int], alpha: float = 1.0) -> None:
    for y in range(max(0, y1), min(size, y2 + 1)):
        for x in range(max(0, x1), min(size, x2 + 1)):
            if alpha >= 1.0:
                set_rgb(buffer, size, x, y, color)
            else:
                blend_rgb(buffer, size, x, y, color, alpha)


def line_gray(buffer: bytearray, size: int, a: tuple[int, int], b: tuple[int, int], radius: int, value: int) -> None:
    ax, ay = a
    bx, by = b
    steps = max(1, int(math.hypot(bx - ax, by - ay)))
    for step in range(steps + 1):
        t = step / steps
        x = int(round(ax + (bx - ax) * t))
        y = int(round(ay + (by - ay) * t))
        disk_gray(buffer, size, x, y, radius, value)


def line_rgb(buffer: bytearray, size: int, a: tuple[int, int], b: tuple[int, int], radius: int, color: tuple[int, int, int], alpha: float = 1.0) -> None:
    ax, ay = a
    bx, by = b
    steps = max(1, int(math.hypot(bx - ax, by - ay)))
    for step in range(steps + 1):
        t = step / steps
        x = int(round(ax + (bx - ax) * t))
        y = int(round(ay + (by - ay) * t))
        disk_rgb(buffer, size, x, y, radius, color, alpha)


def generate(output: Path, size: int) -> dict:
    height = bytearray(size * size)
    biomes = bytearray(size * size * 3)
    rivers = bytearray(size * size)
    roads = bytearray(size * size)
    settlement = bytearray(size * size)
    landmarks = bytearray(size * size * 3)
    composite = bytearray(size * size * 3)

    nodes = {
        "first-haven": {"type": "village", "x": 0, "z": 0, "color": (255, 214, 112)},
        "market-square": {"type": "landmark", "x": 18, "z": 20, "color": (255, 190, 75)},
        "farm-gate": {"type": "road_marker", "x": -190, "z": 125, "color": (194, 220, 111)},
        "starter-camp": {"type": "camp", "x": 275, "z": -120, "color": (255, 128, 77)},
        "starter-shrine": {"type": "shrine", "x": -90, "z": -180, "color": (134, 214, 255)},
        "first-waystone": {"type": "waystone", "x": 18, "z": 12, "color": (128, 176, 255)},
        "arena-approach": {"type": "road_marker", "x": 720, "z": 610, "color": (213, 119, 255)},
        "first-gate-arena": {"type": "arena", "x": 900, "z": 760, "color": (255, 80, 88)},
    }
    routes = [
        ("first-haven", "farm-gate"),
        ("first-haven", "starter-camp"),
        ("first-haven", "starter-shrine"),
        ("first-haven", "arena-approach"),
        ("arena-approach", "first-gate-arena"),
    ]

    center = size / 2.0
    river_points = [world_to_pixel(size, -520, -780), world_to_pixel(size, -290, -330), world_to_pixel(size, -160, 80), world_to_pixel(size, -290, 360), world_to_pixel(size, -520, 760)]

    for py in range(size):
        z = py - center
        for px in range(size):
            x = px - center
            dist = math.hypot(x, z) / (size / 2)
            ridge = math.sin((x + z * 0.45) / 92.0) * 15 + math.sin((x * 0.28 - z) / 135.0) * 22
            basin = max(0.0, 1.0 - math.hypot(x / 360.0, z / 270.0))
            gate = max(0.0, 1.0 - math.hypot((x - 850) / 300.0, (z - 700) / 260.0))
            value = 126 + ridge + dist * 42 - basin * 30 + gate * 14
            height[py * size + px] = clamp(value)

            wobble = 0.12 * math.sin(x / 95.0) + 0.10 * math.sin(z / 127.0) + 0.08 * math.sin((x + z) / 141.0)
            region_scores = [
                ((91, 142, 78), 1.25 * math.exp(-((x / 520.0) ** 2 + (z / 420.0) ** 2)) + wobble),  # meadow basin
                ((151, 142, 85), 1.05 * math.exp(-(((x + 250) / 360.0) ** 2 + ((z - 250) / 300.0) ** 2)) + wobble * 0.7),  # fields
                ((39, 82, 59), 1.10 * math.exp(-(((x + 300) / 520.0) ** 2 + ((z + 420) / 340.0) ** 2)) - wobble),  # old growth
                ((57, 104, 83), 1.00 * math.exp(-(((x + 530) / 240.0) ** 2 + ((z - 80) / 720.0) ** 2)) + wobble * 0.4),  # riverlands
                ((105, 117, 93), 0.85 * math.exp(-(((x - 440) / 430.0) ** 2 + ((z + 330) / 360.0) ** 2)) + max(0, (value - 155) / 70.0)),  # highlands
                ((94, 90, 104), 1.20 * math.exp(-(((x - 760) / 390.0) ** 2 + ((z - 700) / 320.0) ** 2)) - wobble * 0.5),  # gate wilds
            ]
            color = max(region_scores, key=lambda item: item[1])[0]
            set_rgb(biomes, size, px, py, color)
            set_rgb(composite, size, px, py, color)

    for a, b in zip(river_points, river_points[1:]):
        line_gray(rivers, size, a, b, 10, 230)
        line_rgb(composite, size, a, b, 12, (59, 119, 184), 0.8)

    for start, end in routes:
        a = world_to_pixel(size, nodes[start]["x"], nodes[start]["z"])
        b = world_to_pixel(size, nodes[end]["x"], nodes[end]["z"])
        line_gray(roads, size, a, b, 7, 235)
        line_rgb(composite, size, a, b, 8, (215, 205, 178), 0.85)

    haven = world_to_pixel(size, 0, 0)
    rect_gray(settlement, size, haven[0] - 145, haven[1] - 120, haven[0] + 145, haven[1] + 120, 225)
    rect_rgb(composite, size, haven[0] - 145, haven[1] - 120, haven[0] + 145, haven[1] + 120, (218, 178, 92), 0.28)
    farm = world_to_pixel(size, -190, 125)
    rect_gray(settlement, size, farm[0] - 90, farm[1] - 58, farm[0] + 86, farm[1] + 64, 190)
    rect_rgb(composite, size, farm[0] - 90, farm[1] - 58, farm[0] + 86, farm[1] + 64, (176, 196, 97), 0.35)

    for key, node in nodes.items():
        px, py = world_to_pixel(size, node["x"], node["z"])
        disk_rgb(landmarks, size, px, py, 10, node["color"])
        disk_rgb(composite, size, px, py, 12, node["color"], 0.95)

    artifacts = {
        "heightmap": "floor1-heightmap.png",
        "biomes": "floor1-biomes.png",
        "rivers": "floor1-rivers.png",
        "roads": "floor1-roads.png",
        "settlement": "floor1-settlement-mask.png",
        "landmarks": "floor1-landmarks.png",
        "composite": "floor1-composite-preview.png",
    }
    write_png(output / artifacts["heightmap"], size, size, 1, height)
    write_png(output / artifacts["biomes"], size, size, 3, biomes)
    write_png(output / artifacts["rivers"], size, size, 1, rivers)
    write_png(output / artifacts["roads"], size, size, 1, roads)
    write_png(output / artifacts["settlement"], size, size, 1, settlement)
    write_png(output / artifacts["landmarks"], size, size, 3, landmarks)
    write_png(output / artifacts["composite"], size, size, 3, composite)

    return {
        "worldName": WORLD_NAME,
        "sliceSize": size,
        "coordinateMode": "image center maps to world 0,0; +x east, +z south",
        "artifacts": artifacts,
        "nodes": nodes,
        "routes": routes,
        "notes": [
            "WorldPainter owns macro landforms, water, broad biome paint, and route readability.",
            "CrownsTerrain still owns runtime readiness, anchors, pregeneration overlays, and MMO-facing TerrainProvider points.",
            "Blender/.ctpl structures are intentionally not baked into this mask set.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Floor 1 WorldPainter masks.")
    parser.add_argument("--output", default=r"D:\CrownsSuiteTools\Projects\CrownsTerrain\WorldPainter\Floor1Slice")
    parser.add_argument("--size", type=int, default=2048)
    args = parser.parse_args()

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = generate(output, args.size)
    report_path = output / "floor1-worldpainter-report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Generated Floor 1 WorldPainter masks in {output}")
    print(f"Report: {report_path}")


if __name__ == "__main__":
    main()
