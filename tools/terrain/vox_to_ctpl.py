#!/usr/bin/env python3
"""Convert MagicaVoxel .vox files into CrownsTerrain .ctpl templates."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
from ctpl_writer import Block, normalize_key, normalize_material, write_ctpl


DEFAULT_MATERIAL_PALETTE = {
    "STONE_BRICKS": (122, 118, 111),
    "MOSSY_STONE_BRICKS": (98, 120, 82),
    "COBBLESTONE": (116, 116, 116),
    "OAK_PLANKS": (162, 130, 78),
    "OAK_LOG": (112, 84, 52),
    "SPRUCE_PLANKS": (114, 84, 48),
    "SPRUCE_LOG": (72, 50, 34),
    "DARK_OAK_PLANKS": (70, 44, 25),
    "DARK_OAK_LOG": (59, 39, 22),
    "GLASS_PANE": (180, 220, 230),
    "WHITE_WOOL": (235, 236, 229),
    "RED_WOOL": (160, 40, 40),
    "CUT_COPPER": (184, 100, 75),
    "LANTERN": (228, 170, 70),
    "CAMPFIRE": (120, 74, 42),
    "MOSS_BLOCK": (84, 112, 58),
    "GRASS_BLOCK": (92, 132, 62),
    "DIRT": (120, 86, 56),
    "WATER": (45, 86, 185),
}


def read_chunks(blob: bytes, offset: int, end: int):
    while offset < end:
        chunk_id = blob[offset:offset + 4].decode("ascii")
        content_size, children_size = struct.unpack_from("<II", blob, offset + 4)
        content_start = offset + 12
        content_end = content_start + content_size
        children_end = content_end + children_size
        yield chunk_id, blob[content_start:content_end], blob[content_end:children_end]
        offset = children_end


def parse_vox(path: Path):
    blob = path.read_bytes()
    if blob[:4] != b"VOX ":
        raise ValueError("Not a MagicaVoxel .vox file.")
    models = []
    palette = None
    pending_size = None
    for chunk_id, content, children in read_chunks(blob, 8, len(blob)):
        if chunk_id == "MAIN":
            stack = list(read_chunks(children, 0, len(children)))
        else:
            stack = [(chunk_id, content, children)]
        while stack:
            cid, ccontent, cchildren = stack.pop(0)
            if cid == "SIZE":
                pending_size = struct.unpack_from("<III", ccontent, 0)
            elif cid == "XYZI":
                count = struct.unpack_from("<I", ccontent, 0)[0]
                voxels = []
                for index in range(count):
                    x, y, z, color_index = struct.unpack_from("<BBBB", ccontent, 4 + index * 4)
                    voxels.append((x, y, z, color_index))
                models.append((pending_size, voxels))
            elif cid == "RGBA":
                palette = []
                for index in range(256):
                    r, g, b, a = struct.unpack_from("<BBBB", ccontent, index * 4)
                    palette.append((r, g, b, a))
            if cchildren:
                stack.extend(read_chunks(cchildren, 0, len(cchildren)))
    return models, palette


def load_material_palette(path: Path | None) -> dict[str, tuple[int, int, int]]:
    if path is None:
        return DEFAULT_MATERIAL_PALETTE
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    palette = {}
    for material, rgb in data.items():
        if isinstance(rgb, list) and len(rgb) >= 3:
            palette[normalize_material(material)] = (int(rgb[0]), int(rgb[1]), int(rgb[2]))
    return palette or DEFAULT_MATERIAL_PALETTE


def nearest_material(rgb: tuple[int, int, int], palette: dict[str, tuple[int, int, int]]) -> str:
    r, g, b = rgb
    return min(
        palette.items(),
        key=lambda item: (r - item[1][0]) ** 2 + (g - item[1][1]) ** 2 + (b - item[1][2]) ** 2,
    )[0]


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert MagicaVoxel .vox into a CrownsTerrain .ctpl template.")
    parser.add_argument("input", type=Path, help="Input .vox file.")
    parser.add_argument("output", type=Path, nargs="?", help="Output .ctpl path. Defaults beside input.")
    parser.add_argument("--key", help="Template key. Defaults to input filename.")
    parser.add_argument("--palette", type=Path, help="Optional JSON material-to-RGB palette.")
    args = parser.parse_args()

    models, vox_palette = parse_vox(args.input)
    if not models:
        raise ValueError("No voxel models found in file.")
    materials = load_material_palette(args.palette)
    blocks: list[Block] = []
    for _, voxels in models:
        for x, y, z, color_index in voxels:
            rgba = vox_palette[color_index - 1] if vox_palette and color_index > 0 else (128, 128, 128, 255)
            if rgba[3] == 0:
                continue
            material = nearest_material((rgba[0], rgba[1], rgba[2]), materials)
            blocks.append(Block(x, z, y, material))

    key = normalize_key(args.key or args.input.stem)
    output = args.output or args.input.with_suffix(".ctpl")
    write_ctpl(output, key, blocks, source=str(args.input))
    print(f"Wrote {output} ({len(blocks)} blocks)")


if __name__ == "__main__":
    main()
