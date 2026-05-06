#!/usr/bin/env python3
"""Shared CrownsTerrain .ctpl writer utilities."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
import re


SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&()*+,-/:;<=>?@[]^_{|}~"


@dataclass(frozen=True)
class Block:
    x: int
    y: int
    z: int
    material: str


def normalize_key(value: str) -> str:
    value = value.strip().lower().replace(" ", "_").replace("-", "_")
    value = re.sub(r"[^a-z0-9_]+", "", value)
    return value or "converted_structure"


def normalize_material(value: str) -> str:
    value = value.strip().upper().replace(" ", "_").replace("-", "_")
    value = re.sub(r"[^A-Z0-9_]+", "", value)
    return value or "STONE"


def write_ctpl(path: Path, key: str, blocks: list[Block], anchor: tuple[int, int, int] | None = None, source: str | None = None) -> None:
    if not blocks:
        raise ValueError("Cannot write an empty .ctpl template.")

    key = normalize_key(key)
    min_x = min(block.x for block in blocks)
    min_y = min(block.y for block in blocks)
    min_z = min(block.z for block in blocks)
    shifted = [
        Block(block.x - min_x, block.y - min_y, block.z - min_z, normalize_material(block.material))
        for block in blocks
    ]

    max_x = max(block.x for block in shifted)
    max_y = max(block.y for block in shifted)
    max_z = max(block.z for block in shifted)
    if anchor is None:
        anchor = (max_x // 2, 0, max_z // 2)
    else:
        anchor = (anchor[0] - min_x, anchor[1] - min_y, anchor[2] - min_z)

    materials = sorted({block.material for block in shifted})
    if len(materials) > len(SYMBOLS):
        raise ValueError(f"Too many materials for .ctpl palette: {len(materials)} > {len(SYMBOLS)}")
    symbols = {material: SYMBOLS[index] for index, material in enumerate(materials)}

    layers: dict[int, dict[tuple[int, int], str]] = defaultdict(dict)
    for block in shifted:
        layers[block.y][(block.x, block.z)] = symbols[block.material]

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(f"key: {key}\n")
        if source:
            handle.write(f"# source: {source}\n")
        handle.write(f"anchor: {anchor[0]},{anchor[1]},{anchor[2]}\n")
        handle.write("palette:\n")
        for material in materials:
            handle.write(f"{symbols[material]}={material}\n")
        handle.write("layers:\n")
        for y in range(0, max_y + 1):
            handle.write(f"y={y}\n")
            layer = layers.get(y, {})
            for z in range(0, max_z + 1):
                row = [layer.get((x, z), ".") for x in range(0, max_x + 1)]
                handle.write("".join(row) + "\n")

