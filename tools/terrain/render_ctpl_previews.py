#!/usr/bin/env python3
"""Render offline PNG previews and layout QA for CrownsTerrain .ctpl kits."""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


@dataclass(frozen=True)
class Template:
    key: str
    path: Path
    anchor: tuple[int, int, int]
    palette: dict[str, str]
    blocks: dict[tuple[int, int, int], str]

    @property
    def size(self) -> tuple[int, int, int]:
        if not self.blocks:
            return (0, 0, 0)
        max_x = max(x for x, _, _ in self.blocks)
        max_y = max(y for _, y, _ in self.blocks)
        max_z = max(z for _, _, z in self.blocks)
        return (max_x + 1, max_y + 1, max_z + 1)

    @property
    def block_count(self) -> int:
        return len(self.blocks)


MATERIAL_COLORS = {
    "ANDESITE": (126, 126, 126),
    "AMETHYST_BLOCK": (133, 92, 183),
    "BARREL": (104, 70, 36),
    "BARRIER": (255, 0, 0),
    "BLAST_FURNACE": (75, 72, 68),
    "BRICKS": (143, 73, 56),
    "BROWN_WOOL": (96, 59, 31),
    "CAMPFIRE": (95, 64, 45),
    "CHISELED_STONE_BRICKS": (122, 121, 122),
    "COBBLESTONE": (111, 111, 111),
    "COPPER_BLOCK": (192, 107, 78),
    "DARK_OAK_PLANKS": (66, 43, 23),
    "DEEPSLATE_BRICKS": (54, 54, 60),
    "DEEPSLATE_TILES": (45, 45, 51),
    "DIRT": (116, 78, 40),
    "FARMLAND": (91, 58, 30),
    "GLASS_PANE": (176, 217, 220),
    "GREEN_WOOL": (84, 110, 35),
    "HAY_BLOCK": (166, 132, 38),
    "LANTERN": (235, 157, 52),
    "MOSS_BLOCK": (89, 119, 54),
    "MOSSY_COBBLESTONE": (91, 109, 84),
    "MOSSY_STONE_BRICKS": (92, 109, 86),
    "OAK_FENCE": (151, 108, 55),
    "OAK_LEAVES": (72, 121, 48),
    "OAK_LOG": (109, 82, 49),
    "OAK_PLANKS": (162, 130, 78),
    "POLISHED_ANDESITE": (136, 136, 136),
    "POLISHED_DEEPSLATE": (64, 63, 68),
    "RED_WOOL": (160, 45, 40),
    "ROOTED_DIRT": (93, 72, 46),
    "SMOOTH_STONE": (158, 158, 158),
    "SPRUCE_FENCE": (92, 60, 32),
    "SPRUCE_LOG": (82, 56, 33),
    "SPRUCE_PLANKS": (114, 84, 48),
    "STONE": (125, 125, 125),
    "STONE_BRICKS": (122, 121, 122),
    "STRIPPED_SPRUCE_LOG": (154, 107, 54),
    "WATER": (54, 91, 193),
    "WHEAT": (195, 171, 70),
    "WHITE_WOOL": (224, 226, 220),
}


FLOOR1_PLACEMENTS = [
    ("fh_town_hall_grand", 0, -50, "civic"),
    ("fh_gatehouse_grand", 0, -82, "defensive"),
    ("fh_farm_terrace_stamp", -6, 90, "farming"),
    ("fh_bridge_stream", 48, 120, "route"),
    ("fh_spawn_plaza_grand", 0, 0, "civic"),
    ("fh_waystone_platform", 24, -2, "civic"),
    ("notice_board", -20, -18, "civic"),
    ("fh_shrine_grove", -60, 42, "wilderness"),
    ("fh_house_large_a", -54, -34, "residential"),
    ("fh_house_large_b", 58, -34, "residential"),
    ("fh_house_row", -48, 8, "residential"),
    ("fh_house_row", 70, 8, "residential"),
    ("fh_market_street", 54, 40, "market"),
    ("fh_market_hall", 82, 18, "market"),
    ("fh_blacksmith_large", 82, -20, "market"),
    ("fh_watchtower_tall", 72, 78, "defensive"),
    ("fh_barn_large", -54, 100, "farming"),
    ("fh_road_straight", 0, -38, "route"),
    ("fh_road_curve", 34, 24, "route"),
    ("retaining_wall", -78, 2, "support"),
    ("switchback_stair", -86, -12, "support"),
    ("fh_watchtower_tall", -32, -94, "defensive"),
    ("fh_watchtower_tall", 32, -94, "defensive"),
    ("wall_fragment", -62, -82, "defensive"),
    ("wall_fragment", 62, -82, "defensive"),
    ("fh_cliff_rocks", -108, -20, "landmark"),
    ("fh_starter_camp", 275, -120, "wilderness"),
    ("fh_shrine_grove", -90, -180, "wilderness"),
    ("fh_waystone_platform", 18, 12, "civic"),
    ("road_marker", 0, -285, "route"),
    ("fh_first_gate_platform", 960, 768, "arena"),
    ("fh_arena_approach", 960, 682, "arena"),
    ("arena_staging", 960, 636, "arena"),
    ("arena_threshold", 960, 714, "arena"),
    ("fh_gatehouse_grand", 960, 700, "arena"),
]


CATEGORY_COLORS = {
    "arena": (154, 69, 79),
    "civic": (218, 184, 87),
    "defensive": (128, 133, 147),
    "farming": (116, 151, 72),
    "landmark": (151, 105, 181),
    "market": (190, 134, 72),
    "residential": (120, 165, 190),
    "route": (180, 180, 180),
    "support": (104, 103, 105),
    "wilderness": (76, 135, 82),
}


def parse_ctpl(path: Path) -> Template:
    key = path.stem
    anchor = (0, 0, 0)
    palette: dict[str, str] = {}
    blocks: dict[tuple[int, int, int], str] = {}
    mode = ""
    y = 0
    z = 0
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.rstrip("\n")
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("key:"):
            key = stripped.split(":", 1)[1].strip()
            continue
        if stripped.startswith("anchor:"):
            values = [int(value.strip()) for value in stripped.split(":", 1)[1].split(",")]
            if len(values) == 3:
                anchor = (values[0], values[1], values[2])
            continue
        if stripped == "palette:":
            mode = "palette"
            continue
        if stripped == "layers:":
            mode = "layers"
            continue
        if mode == "palette":
            symbol, value = stripped.split("=", 1)
            palette[symbol] = value
            continue
        if mode == "layers":
            if stripped.startswith("y="):
                y = int(stripped[2:].strip())
                z = 0
                continue
            for x, symbol in enumerate(line):
                if symbol == "." or symbol == " ":
                    continue
                blocks[(x, y, z)] = palette.get(symbol, "STONE")
            z += 1
    return Template(key, path, anchor, palette, blocks)


def material_key(value: str) -> str:
    value = value.split("[", 1)[0].split(":", 1)[-1]
    return value.upper()


def color_for(value: str, y: int = 0, max_y: int = 1) -> tuple[int, int, int]:
    base = MATERIAL_COLORS.get(material_key(value), (150, 150, 150))
    shade = 0.72 + 0.28 * (y / max(1, max_y))
    return tuple(min(255, int(channel * shade)) for channel in base)


def draw_label(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str, fill=(230, 230, 230)) -> None:
    draw.text((xy[0] + 1, xy[1] + 1), text, fill=(0, 0, 0))
    draw.text(xy, text, fill=fill)


def render_top(template: Template, scale: int = 5) -> Image.Image:
    sx, _, sz = template.size
    max_y = max((y for _, y, _ in template.blocks), default=1)
    image = Image.new("RGB", (max(1, sx) * scale, max(1, sz) * scale), (26, 31, 29))
    draw = ImageDraw.Draw(image)
    highest: dict[tuple[int, int], tuple[int, str]] = {}
    for (x, y, z), block in template.blocks.items():
        if (x, z) not in highest or y > highest[(x, z)][0]:
            highest[(x, z)] = (y, block)
    for (x, z), (y, block) in highest.items():
        draw.rectangle([x * scale, z * scale, (x + 1) * scale - 1, (z + 1) * scale - 1], fill=color_for(block, y, max_y))
    return image


def render_front(template: Template, scale: int = 5) -> Image.Image:
    sx, sy, _ = template.size
    image = Image.new("RGB", (max(1, sx) * scale, max(1, sy) * scale), (22, 24, 27))
    draw = ImageDraw.Draw(image)
    visible: dict[tuple[int, int], tuple[int, str]] = {}
    for (x, y, z), block in template.blocks.items():
        if (x, y) not in visible or z < visible[(x, y)][0]:
            visible[(x, y)] = (z, block)
    for (x, y), (_, block) in visible.items():
        yy = sy - y - 1
        draw.rectangle([x * scale, yy * scale, (x + 1) * scale - 1, (yy + 1) * scale - 1], fill=color_for(block, y, sy))
    return image


def render_side(template: Template, scale: int = 5) -> Image.Image:
    _, sy, sz = template.size
    image = Image.new("RGB", (max(1, sz) * scale, max(1, sy) * scale), (22, 24, 27))
    draw = ImageDraw.Draw(image)
    visible: dict[tuple[int, int], tuple[int, str]] = {}
    for (x, y, z), block in template.blocks.items():
        if (z, y) not in visible or x < visible[(z, y)][0]:
            visible[(z, y)] = (x, block)
    for (z, y), (_, block) in visible.items():
        yy = sy - y - 1
        draw.rectangle([z * scale, yy * scale, (z + 1) * scale - 1, (yy + 1) * scale - 1], fill=color_for(block, y, sy))
    return image


def render_iso(template: Template, scale: int = 4) -> Image.Image:
    sx, sy, sz = template.size
    width = max(320, (sx + sz) * scale * 2 + 80)
    height = max(220, (sx + sz) * scale + sy * scale * 2 + 80)
    image = Image.new("RGB", (width, height), (18, 20, 24))
    draw = ImageDraw.Draw(image)
    origin_x = width // 2
    origin_y = 40 + sy * scale
    ordered = sorted(template.blocks.items(), key=lambda item: (item[0][0] + item[0][2] + item[0][1], item[0][1]))
    for (x, y, z), block in ordered:
        px = origin_x + (x - z) * scale * 2
        py = origin_y + (x + z) * scale - y * scale * 2
        color = color_for(block, y, sy)
        top = [(px, py), (px + scale * 2, py + scale), (px, py + scale * 2), (px - scale * 2, py + scale)]
        left = [(px - scale * 2, py + scale), (px, py + scale * 2), (px, py + scale * 4), (px - scale * 2, py + scale * 3)]
        right = [(px + scale * 2, py + scale), (px, py + scale * 2), (px, py + scale * 4), (px + scale * 2, py + scale * 3)]
        draw.polygon(left, fill=tuple(max(0, int(c * 0.68)) for c in color))
        draw.polygon(right, fill=tuple(max(0, int(c * 0.82)) for c in color))
        draw.polygon(top, fill=color)
    return image


def add_title(image: Image.Image, title: str) -> Image.Image:
    out = Image.new("RGB", (image.width, image.height + 28), (12, 14, 18))
    out.paste(image, (0, 28))
    draw = ImageDraw.Draw(out)
    draw_label(draw, (8, 7), title)
    return out


def make_contact_sheet(cards: list[tuple[str, Image.Image]], output: Path, columns: int = 4) -> None:
    cell_w = max(card.width for _, card in cards) + 24
    cell_h = max(card.height for _, card in cards) + 48
    rows = math.ceil(len(cards) / columns)
    sheet = Image.new("RGB", (columns * cell_w, rows * cell_h), (15, 17, 20))
    draw = ImageDraw.Draw(sheet)
    for index, (key, card) in enumerate(cards):
        col = index % columns
        row = index // columns
        x = col * cell_w + 12
        y = row * cell_h + 12
        draw_label(draw, (x, y), key)
        sheet.paste(card, (x, y + 24))
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output)


def transform_bounds(template: Template, origin_x: int, origin_z: int) -> tuple[int, int, int, int]:
    xs = []
    zs = []
    ax, _, az = template.anchor
    for x, _, z in template.blocks:
        xs.append(origin_x + x - ax)
        zs.append(origin_z + z - az)
    return (min(xs), min(zs), max(xs), max(zs))


def overlap(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> int:
    x = max(0, min(a[2], b[2]) - max(a[0], b[0]) + 1)
    z = max(0, min(a[3], b[3]) - max(a[1], b[1]) + 1)
    return x * z


def render_layout(templates: dict[str, Template], output: Path, report_path: Path) -> None:
    entries = []
    warnings = []
    for key, x, z, category in FLOOR1_PLACEMENTS:
        template = templates.get(key)
        if template is None:
            warnings.append(f"Missing template in layout: {key}")
            continue
        bounds = transform_bounds(template, x, z)
        entries.append({"key": key, "x": x, "z": z, "category": category, "bounds": bounds, "blocks": template.block_count, "size": template.size})
    for i, a in enumerate(entries):
        for b in entries[i + 1:]:
            area = overlap(a["bounds"], b["bounds"])
            if area > 18 and "road" not in a["key"] and "road" not in b["key"]:
                warnings.append(f"Possible overlap: {a['key']} with {b['key']} ({area} block columns)")
    min_x = min(entry["bounds"][0] for entry in entries) - 40
    min_z = min(entry["bounds"][1] for entry in entries) - 40
    max_x = max(entry["bounds"][2] for entry in entries) + 40
    max_z = max(entry["bounds"][3] for entry in entries) + 40
    scale = min(3.0, max(0.35, 1500 / max(max_x - min_x, max_z - min_z, 1)))
    width = int((max_x - min_x) * scale)
    height = int((max_z - min_z) * scale)
    image = Image.new("RGB", (max(800, width), max(600, height)), (31, 48, 35))
    draw = ImageDraw.Draw(image)

    def sx(x: int) -> int:
        return int((x - min_x) * scale)

    def sz(z: int) -> int:
        return int((z - min_z) * scale)

    for entry in entries:
        x1, z1, x2, z2 = entry["bounds"]
        color = CATEGORY_COLORS.get(entry["category"], (150, 150, 150))
        draw.rectangle([sx(x1), sz(z1), sx(x2), sz(z2)], outline=(20, 20, 20), fill=tuple(int(c * 0.72) for c in color))
        draw_label(draw, (sx(x1) + 2, sz(z1) + 2), entry["key"], fill=(245, 245, 225))
    for entry in entries:
        x, z = entry["x"], entry["z"]
        draw.ellipse([sx(x) - 3, sz(z) - 3, sx(x) + 3, sz(z) + 3], fill=(255, 255, 255))
    draw_label(draw, (12, 12), "Floor 1 Layout Preview - First Haven, route, camp, shrine, arena")
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)
    report = {
        "placements": entries,
        "bounds": [min_x, min_z, max_x, max_z],
        "scale": scale,
        "warnings": warnings,
        "warning_count": len(warnings),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Render CrownsTerrain .ctpl previews and Floor 1 layout QA.")
    parser.add_argument("--structures", type=Path, default=Path("terrain/src/main/resources/structures"))
    parser.add_argument("--output", type=Path, default=Path("build/terrain-preview"))
    parser.add_argument("--pattern", default="fh_*.ctpl")
    args = parser.parse_args()

    output = args.output
    templates = {template.key: template for template in [parse_ctpl(path) for path in sorted(args.structures.glob("*.ctpl"))]}
    preview_templates = [parse_ctpl(path) for path in sorted(args.structures.glob(args.pattern))]
    cards = []
    index = []
    for template in preview_templates:
        folder = output / "templates" / template.key
        folder.mkdir(parents=True, exist_ok=True)
        views = {
            "top": render_top(template),
            "front": render_front(template),
            "side": render_side(template),
            "iso": render_iso(template),
        }
        for name, image in views.items():
            image.save(folder / f"{name}.png")
        cards.append((template.key, add_title(views["iso"].resize((min(360, views["iso"].width), min(240, views["iso"].height))), f"{template.key} {template.size}")))
        index.append({
            "key": template.key,
            "blocks": template.block_count,
            "size": template.size,
            "category": category_for_key(template.key),
            "status": "review-needed",
            "preview": str(folder / "iso.png"),
        })
    make_contact_sheet(cards, output / "floor1-kit-contact-sheet.png")
    render_layout(templates, output / "floor1-layout.png", output / "floor1-layout-report.json")
    (output / "floor1-kit-index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    write_markdown_index(index, output / "floor1-kit-index.md")
    print(f"Rendered {len(preview_templates)} template preview set(s) into {output}")
    print(f"Contact sheet: {output / 'floor1-kit-contact-sheet.png'}")
    print(f"Layout preview: {output / 'floor1-layout.png'}")


def category_for_key(key: str) -> str:
    if "arena" in key or "gate_platform" in key:
        return "arena"
    if "farm" in key or "barn" in key:
        return "farming"
    if "market" in key or "blacksmith" in key:
        return "market"
    if "house" in key:
        return "residential"
    if "road" in key or "bridge" in key:
        return "route"
    if "camp" in key or "shrine" in key or "cliff" in key:
        return "wilderness"
    return "civic"


def write_markdown_index(index: list[dict], output: Path) -> None:
    lines = [
        "# CrownsTerrain Floor 1 Kit Index",
        "",
        "Generated by `tools/terrain/render_ctpl_previews.py`.",
        "",
        "## Review Rules",
        "",
        "- Production-ready pieces should have a clear silhouette from top and isometric views.",
        "- Houses should avoid single-box massing; add porches, roof breaks, chimneys, dormers, gardens, or elevation changes.",
        "- Civic pieces should be visually larger and more important than residential pieces.",
        "- Market pieces should read as trade spaces from above: counters, awnings, stalls, crates, or road frontage.",
        "- Route stamps should connect cleanly and avoid creating featureless gray slabs.",
        "- Arena pieces should have strong threshold language: approach, staging, gate, and boss platform.",
        "",
        "## Templates",
        "",
        "| Key | Category | Size | Blocks | Status |",
        "| --- | --- | --- | ---: | --- |",
    ]
    for entry in index:
        size = "x".join(str(value) for value in entry["size"])
        lines.append(f"| `{entry['key']}` | {entry['category']} | {size} | {entry['blocks']} | {entry['status']} |")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
