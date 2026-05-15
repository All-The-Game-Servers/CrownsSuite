"""Blender script: export block-authored collections as CrownsTerrain JSON.

Run from Blender:
  blender --background scene.blend --python blender_export_blocks.py -- output_dir --collections --manifest manifest.json

Objects can set:
  minecraft_material = OAK_PLANKS
  minecraft_blockdata = minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]

Each visible mesh object's world bounding box is rounded to block coordinates and
filled as a cuboid. This intentionally favors Minecraft-block-authored scenes
over arbitrary mesh voxelization so generated .ctpl files stay predictable.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def normalize_key(value: str) -> str:
    return "".join(ch if ch.isalnum() or ch == "_" else "_" for ch in value.lower()).strip("_") or "crowns_template"


def material_for(obj) -> tuple[str, str | None]:
    if "minecraft_blockdata" in obj:
        blockdata = str(obj["minecraft_blockdata"]).replace(" ", "")
        material = blockdata.split("[", 1)[0].split(":", 1)[-1].upper()
        return material, blockdata
    if "minecraft_material" in obj:
        material = str(obj["minecraft_material"]).upper().replace(" ", "_").replace("-", "_")
        return material, None
    if obj.active_material:
        material = obj.active_material.name.upper().replace(" ", "_").replace("-", "_")
        return material, None
    return "STONE", None


def visible_meshes(collection=None):
    objects = collection.objects if collection else bpy.context.scene.objects
    for obj in objects:
        if obj.type == "MESH" and not obj.hide_get() and not obj.hide_viewport:
            yield obj


def object_blocks(obj) -> list[dict]:
    material, blockdata = material_for(obj)
    corners = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    min_x = round(min(corner.x for corner in corners))
    max_x = round(max(corner.x for corner in corners)) - 1
    min_y = round(min(corner.z for corner in corners))
    max_y = round(max(corner.z for corner in corners)) - 1
    min_z = round(min(corner.y for corner in corners))
    max_z = round(max(corner.y for corner in corners)) - 1
    if max_x < min_x or max_y < min_y or max_z < min_z:
        return []
    blocks = []
    for x in range(min_x, max_x + 1):
        for y in range(min_y, max_y + 1):
            for z in range(min_z, max_z + 1):
                entry = {"x": x, "y": y, "z": z, "material": material}
                if blockdata:
                    entry["blockdata"] = blockdata
                blocks.append(entry)
    return blocks


def export_template(key: str, objects, output_path: Path, source_blend: str | None = None) -> dict:
    blocks_by_coord: dict[tuple[int, int, int], dict] = {}
    anchor = None
    category = "uncategorized"
    for obj in objects:
        if "crowns_anchor" in obj and bool(obj["crowns_anchor"]):
            corners = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
            anchor = [
                round(sum(corner.x for corner in corners) / len(corners)),
                round(sum(corner.z for corner in corners) / len(corners)),
                round(sum(corner.y for corner in corners) / len(corners)),
            ]
            continue
        if "crowns_category" in obj:
            category = str(obj["crowns_category"])
        for block in object_blocks(obj):
            blocks_by_coord[(block["x"], block["y"], block["z"])] = block
    blocks = [blocks_by_coord[coord] for coord in sorted(blocks_by_coord)]
    data = {
        "key": normalize_key(key),
        "anchor": anchor or [0, 0, 0],
        "source": source_blend,
        "blocks": blocks,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    xs = [block["x"] for block in blocks] or [0]
    ys = [block["y"] for block in blocks] or [0]
    zs = [block["z"] for block in blocks] or [0]
    return {
        "key": data["key"],
        "category": category,
        "json": str(output_path),
        "source": source_blend,
        "blocks": len(blocks),
        "size": [max(xs) - min(xs) + 1, max(ys) - min(ys) + 1, max(zs) - min(zs) + 1],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Export Blender block collections to CrownsTerrain JSON.")
    parser.add_argument("output", help="Output JSON file or directory when --collections is set.")
    parser.add_argument("--collections", action="store_true", help="Export each non-empty top-level collection as a separate template.")
    parser.add_argument("--manifest", help="Optional manifest JSON path.")
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])

    source_blend = bpy.data.filepath or None
    output = Path(args.output)
    entries = []
    if args.collections:
        for collection in bpy.context.scene.collection.children:
            objects = list(visible_meshes(collection))
            if not objects:
                continue
            key = normalize_key(collection.name)
            entries.append(export_template(key, objects, output / f"{key}.json", source_blend))
    else:
        entries.append(export_template(bpy.context.scene.name, list(visible_meshes()), output, source_blend))

    if args.manifest:
        manifest_path = Path(args.manifest)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps({"source": source_blend, "templates": entries}, indent=2), encoding="utf-8")
    print(f"Exported {len(entries)} CrownsTerrain template JSON file(s).")


if __name__ == "__main__":
    main()
