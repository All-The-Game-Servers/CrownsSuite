"""Blender script: export cube-style objects as CrownsTerrain block JSON.

Run from Blender:
  blender --background scene.blend --python blender_export_blocks.py -- output.json

Objects can set a custom property named `minecraft_material`; otherwise the active
material name is used. Each cube object's world bounding box is rounded to block
coordinates and filled as a cuboid.
"""

from __future__ import annotations

import json
import sys

import bpy
from mathutils import Vector


def material_for(obj) -> str:
    if "minecraft_material" in obj:
        return str(obj["minecraft_material"]).upper().replace(" ", "_").replace("-", "_")
    if obj.active_material:
        return obj.active_material.name.upper().replace(" ", "_").replace("-", "_")
    return "STONE"


def export_blocks(output_path: str) -> None:
    blocks = {}
    objects = [obj for obj in bpy.context.scene.objects if obj.type == "MESH" and not obj.hide_get()]
    for obj in objects:
        material = material_for(obj)
        corners = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
        min_x = round(min(corner.x for corner in corners))
        max_x = round(max(corner.x for corner in corners)) - 1
        min_y = round(min(corner.z for corner in corners))
        max_y = round(max(corner.z for corner in corners)) - 1
        min_z = round(min(corner.y for corner in corners))
        max_z = round(max(corner.y for corner in corners)) - 1
        for x in range(min_x, max_x + 1):
            for y in range(min_y, max_y + 1):
                for z in range(min_z, max_z + 1):
                    blocks[(x, y, z)] = material

    data = {
        "key": bpy.context.scene.name.lower().replace(" ", "_"),
        "anchor": [0, 0, 0],
        "blocks": [
            {"x": x, "y": y, "z": z, "material": material}
            for (x, y, z), material in sorted(blocks.items())
        ],
    }
    with open(output_path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2)
    print(f"Wrote {output_path} ({len(blocks)} blocks)")


if __name__ == "__main__":
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    export_blocks(args[0] if args else "crowns_blender_export.json")

