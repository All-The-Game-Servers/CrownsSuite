#!/usr/bin/env python3
"""Convert simple Blender/export JSON block data into CrownsTerrain .ctpl."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from ctpl_writer import Block, normalize_key, normalize_material, write_ctpl


def load_blocks(path: Path) -> tuple[str, tuple[int, int, int] | None, list[Block]]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    key = normalize_key(data.get("key") or path.stem)
    anchor_data = data.get("anchor")
    anchor = tuple(int(value) for value in anchor_data) if isinstance(anchor_data, list) and len(anchor_data) == 3 else None
    blocks: list[Block] = []
    for entry in data.get("blocks", []):
        material = normalize_material(entry.get("material", "STONE"))
        if material == "AIR":
            continue
        blocks.append(Block(int(entry["x"]), int(entry["y"]), int(entry["z"]), material))
    return key, anchor, blocks


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert Crowns/Blender block JSON into a CrownsTerrain .ctpl template.")
    parser.add_argument("input", type=Path, help="Input JSON file with key/anchor/blocks.")
    parser.add_argument("output", type=Path, nargs="?", help="Output .ctpl path. Defaults beside input.")
    args = parser.parse_args()

    key, anchor, blocks = load_blocks(args.input)
    output = args.output or args.input.with_suffix(".ctpl")
    write_ctpl(output, key, blocks, anchor=anchor, source=str(args.input))
    print(f"Wrote {output} ({len(blocks)} blocks)")


if __name__ == "__main__":
    main()
