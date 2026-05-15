"""Generate an editable Blender source scene for the CrownsTerrain Floor 1 kit.

This script is intentionally block-authored: every object is a cuboid with
Minecraft material metadata. The exporter converts collections into .ctpl
templates, so these generated scenes are a starting point that can be refined by
hand in Blender without changing the server runtime.
"""

from __future__ import annotations

import sys
from pathlib import Path

import bpy


FLOOR = "POLISHED_ANDESITE"
ROAD = "COBBLESTONE"
ROAD_ALT = "ANDESITE"
MOSS = "MOSSY_COBBLESTONE"
STONE = "STONE_BRICKS"
MOSSY_STONE = "MOSSY_STONE_BRICKS"
WOOD = "SPRUCE_PLANKS"
LOG = "SPRUCE_LOG"
STRIPPED_LOG = "STRIPPED_SPRUCE_LOG"
ROOF = "DARK_OAK_PLANKS"
TRIM = "OAK_PLANKS"
GLASS = "minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]"
LANTERN = "minecraft:lantern[hanging=false,waterlogged=false]"
WATER = "minecraft:water[level=0]"
FARMLAND = "minecraft:farmland[moisture=7]"
WHEAT = "minecraft:wheat[age=5]"


def clean_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete()
    for collection in list(bpy.data.collections):
        bpy.data.collections.remove(collection)


def mat(name: str):
    if name not in bpy.data.materials:
        material = bpy.data.materials.new(name)
        material.diffuse_color = material_color(name)
    return bpy.data.materials[name]


def material_color(name: str):
    colors = {
        "POLISHED_ANDESITE": (0.55, 0.55, 0.55, 1),
        "COBBLESTONE": (0.42, 0.42, 0.42, 1),
        "ANDESITE": (0.50, 0.50, 0.50, 1),
        "MOSSY_COBBLESTONE": (0.32, 0.42, 0.30, 1),
        "STONE_BRICKS": (0.46, 0.45, 0.49, 1),
        "MOSSY_STONE_BRICKS": (0.36, 0.43, 0.34, 1),
        "SPRUCE_PLANKS": (0.36, 0.22, 0.12, 1),
        "SPRUCE_LOG": (0.32, 0.20, 0.12, 1),
        "STRIPPED_SPRUCE_LOG": (0.55, 0.36, 0.18, 1),
        "DARK_OAK_PLANKS": (0.18, 0.10, 0.05, 1),
        "OAK_PLANKS": (0.62, 0.43, 0.22, 1),
        "GRASS_BLOCK": (0.25, 0.50, 0.18, 1),
        "DIRT": (0.38, 0.24, 0.12, 1),
        "ROOTED_DIRT": (0.32, 0.24, 0.16, 1),
        "WATER": (0.08, 0.20, 0.80, 0.65),
        "FARMLAND": (0.30, 0.18, 0.09, 1),
        "WHEAT": (0.78, 0.64, 0.18, 1),
        "LANTERN": (1.00, 0.58, 0.18, 1),
    }
    return colors.get(name.split("[", 1)[0].split(":", 1)[-1].upper(), (0.70, 0.70, 0.70, 1))


def collection(key: str, category: str):
    coll = bpy.data.collections.new(key)
    bpy.context.scene.collection.children.link(coll)
    coll["crowns_category"] = category
    return coll


def box(coll, name: str, x: int, y: int, z: int, w: int, h: int, d: int, block: str):
    bpy.ops.mesh.primitive_cube_add(size=1, location=(x + w / 2, z + d / 2, y + h / 2))
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = (w, d, h)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if block.startswith("minecraft:"):
        obj["minecraft_blockdata"] = block
        obj.data.materials.append(mat(block.split("[", 1)[0].split(":", 1)[-1].upper()))
    else:
        obj["minecraft_material"] = block
        obj.data.materials.append(mat(block))
    obj["crowns_category"] = category_for_collection(coll)
    for existing in list(obj.users_collection):
        existing.objects.unlink(obj)
    coll.objects.link(obj)
    return obj


def category_for_collection(coll) -> str:
    return str(coll.get("crowns_category", "uncategorized"))


def anchor(coll, x: int = 0, y: int = 0, z: int = 0):
    obj = box(coll, "_anchor", x, y, z, 1, 1, 1, "BARRIER")
    obj["crowns_anchor"] = True
    return obj


def lamp(coll, x: int, y: int, z: int):
    box(coll, "lamp_post", x, y, z, 1, 3, 1, STRIPPED_LOG)
    box(coll, "lamp", x, y + 3, z, 1, 1, 1, LANTERN)


def road_surface(coll, width: int, depth: int, edge: bool = True):
    anchor(coll, 0, 0, 0)
    box(coll, "road_core", -width // 2, 0, -depth // 2, width, 1, depth, ROAD)
    box(coll, "road_mix_a", -width // 2 + 1, 1, -depth // 2 + 1, max(1, width - 2), 1, max(1, depth - 2), ROAD_ALT)
    if edge:
        box(coll, "left_edge", -width // 2 - 1, 0, -depth // 2, 1, 1, depth, MOSS)
        box(coll, "right_edge", width // 2, 0, -depth // 2, 1, 1, depth, MOSS)


def gable_roof(coll, x: int, y: int, z: int, w: int, d: int, block: str = ROOF):
    layers = max(2, min(6, w // 2))
    for i in range(layers):
        inset = i
        roof_w = max(1, w - inset * 2)
        box(coll, f"roof_layer_{i}", x + inset, y + i, z - 1, roof_w, 1, d + 2, block)


def simple_building(coll, w: int, d: int, h: int, roof_h: int = 4, porch: bool = True):
    anchor(coll, 0, 0, 0)
    box(coll, "foundation", -w // 2 - 1, 0, -d // 2 - 1, w + 2, 1, d + 2, STONE)
    box(coll, "floor", -w // 2, 1, -d // 2, w, 1, d, WOOD)
    box(coll, "back_wall", -w // 2, 2, d // 2 - 1, w, h, 1, LOG)
    box(coll, "left_wall", -w // 2, 2, -d // 2, 1, h, d, LOG)
    box(coll, "right_wall", w // 2 - 1, 2, -d // 2, 1, h, d, LOG)
    box(coll, "front_wall_left", -w // 2, 2, -d // 2, w // 2 - 2, h, 1, LOG)
    box(coll, "front_wall_right", 2, 2, -d // 2, w // 2 - 2, h, 1, LOG)
    box(coll, "front_trim", -2, 5, -d // 2, 4, 1, 1, TRIM)
    box(coll, "window_l", -w // 2 - 1, 4, -2, 1, 2, 3, GLASS)
    box(coll, "window_r", w // 2, 4, -2, 1, 2, 3, GLASS)
    gable_roof(coll, -w // 2 - 2, 2 + h, -d // 2, w + 4, d, ROOF)
    if porch:
        box(coll, "porch", -4, 1, -d // 2 - 5, 8, 1, 5, TRIM)
        box(coll, "porch_left", -4, 2, -d // 2 - 4, 1, 3, 1, STRIPPED_LOG)
        box(coll, "porch_right", 3, 2, -d // 2 - 4, 1, 3, 1, STRIPPED_LOG)
        box(coll, "porch_roof", -5, 5, -d // 2 - 5, 10, 1, 5, ROOF)


def create_kit() -> None:
    c = collection("fh_spawn_plaza_grand", "civic")
    anchor(c)
    box(c, "plaza", -17, 0, -17, 34, 1, 34, FLOOR)
    box(c, "moss_inlay_ns", -1, 1, -16, 2, 1, 32, MOSS)
    box(c, "moss_inlay_ew", -16, 1, -1, 32, 1, 2, MOSS)
    box(c, "center_dais", -5, 1, -5, 10, 2, 10, STONE)
    box(c, "crest", -2, 3, -2, 4, 1, 4, "COPPER_BLOCK")
    for x, z in [(-15, -15), (14, -15), (-15, 14), (14, 14), (0, -18), (0, 17)]:
        lamp(c, x, 1, z)

    c = collection("fh_town_hall_grand", "civic")
    simple_building(c, 28, 22, 8, porch=True)
    box(c, "rear_hall", -9, 2, 9, 18, 7, 10, LOG)
    box(c, "bell_tower", -4, 10, -2, 8, 10, 8, STONE)
    box(c, "tower_roof", -5, 20, -3, 10, 3, 10, ROOF)
    box(c, "hall_steps", -5, 1, -18, 10, 1, 5, STONE)

    c = collection("fh_market_hall", "market")
    simple_building(c, 24, 16, 6, porch=False)
    box(c, "open_counter", -10, 2, -9, 20, 2, 1, TRIM)
    box(c, "awning", -12, 5, -12, 24, 1, 4, "RED_WOOL")
    for x in [-8, -2, 4, 10]:
        box(c, f"crate_{x}", x, 2, -13, 2, 2, 2, "BARREL")

    c = collection("fh_market_street", "market")
    road_surface(c, 18, 42)
    for z in [-16, -6, 6, 16]:
        box(c, f"stall_left_{z}", -13, 1, z, 6, 1, 5, STONE)
        box(c, f"stall_right_{z}", 7, 1, z, 6, 1, 5, STONE)
        box(c, f"awning_left_{z}", -14, 4, z - 1, 8, 1, 7, "RED_WOOL")
        box(c, f"awning_right_{z}", 6, 4, z - 1, 8, 1, 7, "WHITE_WOOL")
        lamp(c, -5, 1, z)
        lamp(c, 4, 1, z)

    for key, size, cat in [
        ("fh_house_large_a", (18, 16, 6), "residential"),
        ("fh_house_large_b", (20, 14, 7), "residential"),
        ("fh_house_row", (32, 12, 6), "residential"),
    ]:
        c = collection(key, cat)
        simple_building(c, size[0], size[1], size[2], porch=True)

    c = collection("fh_blacksmith_large", "market")
    simple_building(c, 22, 18, 6, porch=False)
    box(c, "forge_pad", 7, 2, -7, 6, 1, 6, "SMOOTH_STONE")
    box(c, "forge_core", 9, 3, -5, 3, 2, 3, "BLAST_FURNACE")
    box(c, "chimney", 9, 8, -5, 3, 8, 3, "BRICKS")
    box(c, "work_yard", -14, 1, -12, 10, 1, 24, MOSS)

    c = collection("fh_barn_large", "farming")
    simple_building(c, 26, 18, 8, porch=False)
    box(c, "hay_loft", -11, 6, -6, 22, 2, 8, "HAY_BLOCK")
    box(c, "yard", -18, 0, -14, 36, 1, 28, "DIRT")
    box(c, "fence_front", -18, 1, -14, 36, 2, 1, "OAK_FENCE")
    box(c, "fence_back", -18, 1, 13, 36, 2, 1, "OAK_FENCE")

    c = collection("fh_farm_terrace_stamp", "farming")
    anchor(c)
    for i in range(4):
        z = i * 9
        box(c, f"terrace_wall_{i}", -24, i, z, 48, 2, 1, STONE)
        box(c, f"farmland_{i}", -22, i + 1, z + 1, 44, 1, 7, FARMLAND)
        box(c, f"wheat_{i}", -22, i + 2, z + 1, 44, 1, 7, WHEAT)
        box(c, f"water_{i}", 0, i + 1, z + 1, 2, 1, 7, WATER)

    c = collection("fh_watchtower_tall", "defensive")
    anchor(c)
    for x, z in [(-4, -4), (3, -4), (-4, 3), (3, 3)]:
        box(c, f"post_{x}_{z}", x, 0, z, 2, 18, 2, STRIPPED_LOG)
    box(c, "lower_deck", -6, 6, -6, 12, 1, 12, WOOD)
    box(c, "upper_deck", -7, 16, -7, 14, 1, 14, WOOD)
    box(c, "railing_n", -7, 17, -7, 14, 2, 1, "SPRUCE_FENCE")
    box(c, "railing_s", -7, 17, 6, 14, 2, 1, "SPRUCE_FENCE")
    box(c, "railing_w", -7, 17, -7, 1, 2, 14, "SPRUCE_FENCE")
    box(c, "railing_e", 6, 17, -7, 1, 2, 14, "SPRUCE_FENCE")
    gable_roof(c, -8, 19, -8, 16, 16)

    c = collection("fh_gatehouse_grand", "defensive")
    anchor(c)
    box(c, "left_tower", -18, 0, -6, 8, 16, 12, STONE)
    box(c, "right_tower", 10, 0, -6, 8, 16, 12, STONE)
    box(c, "arch_top", -10, 10, -6, 20, 6, 12, STONE)
    box(c, "road_pass", -10, 0, -4, 20, 1, 8, ROAD)
    box(c, "roof_l", -19, 16, -7, 10, 2, 14, ROOF)
    box(c, "roof_r", 9, 16, -7, 10, 2, 14, ROOF)
    lamp(c, -8, 1, -7)
    lamp(c, 7, 1, -7)

    c = collection("fh_shrine_grove", "wilderness")
    anchor(c)
    box(c, "moss_circle", -14, 0, -14, 28, 1, 28, "MOSS_BLOCK")
    box(c, "shrine_base", -5, 1, -5, 10, 2, 10, MOSSY_STONE)
    box(c, "altar", -2, 3, -2, 4, 2, 4, "CHISELED_STONE_BRICKS")
    box(c, "back_arch", -6, 3, 5, 12, 7, 2, MOSSY_STONE)
    for x, z in [(-12, -12), (10, -10), (-11, 9), (12, 11)]:
        box(c, f"root_{x}_{z}", x, 1, z, 5, 1, 2, "ROOTED_DIRT")
        box(c, f"trunk_{x}_{z}", x + 1, 1, z + 1, 2, 7, 2, "OAK_LOG")
        box(c, f"canopy_{x}_{z}", x - 1, 8, z - 1, 6, 4, 6, "OAK_LEAVES")

    c = collection("fh_waystone_platform", "civic")
    anchor(c)
    box(c, "platform", -8, 0, -8, 16, 2, 16, STONE)
    box(c, "inner", -5, 2, -5, 10, 1, 10, FLOOR)
    box(c, "obelisk", -1, 3, -1, 2, 8, 2, "DEEPSLATE_TILES")
    box(c, "cap", -2, 11, -2, 4, 2, 4, "AMETHYST_BLOCK")
    for x, z in [(-7, -7), (6, -7), (-7, 6), (6, 6)]:
        lamp(c, x, 2, z)

    c = collection("fh_starter_camp", "wilderness")
    anchor(c)
    box(c, "clearing", -18, 0, -14, 36, 1, 28, "ROOTED_DIRT")
    box(c, "fire_ring", -2, 1, -2, 4, 1, 4, "CAMPFIRE")
    box(c, "tent_left", -14, 1, -8, 9, 4, 8, "GREEN_WOOL")
    box(c, "tent_right", 5, 1, -8, 9, 4, 8, "BROWN_WOOL")
    box(c, "log_bench_a", -8, 1, 7, 7, 1, 2, "OAK_LOG")
    box(c, "log_bench_b", 2, 1, 7, 7, 1, 2, "OAK_LOG")

    for key, width, depth in [("fh_road_straight", 9, 32), ("fh_road_curve", 22, 22)]:
        c = collection(key, "route")
        road_surface(c, width, depth)
        if key.endswith("curve"):
            box(c, "corner_fill", -11, 0, -11, 22, 1, 11, ROAD)

    c = collection("fh_bridge_stream", "route")
    anchor(c)
    box(c, "water_channel", -18, 0, -5, 36, 1, 10, WATER)
    box(c, "bridge_deck", -16, 1, -3, 32, 1, 6, WOOD)
    box(c, "rail_n", -16, 2, -4, 32, 2, 1, "SPRUCE_FENCE")
    box(c, "rail_s", -16, 2, 3, 32, 2, 1, "SPRUCE_FENCE")
    for x in [-14, 14]:
        lamp(c, x, 2, -5)
        lamp(c, x, 2, 4)

    c = collection("fh_cliff_rocks", "landmark")
    anchor(c)
    box(c, "base_rock", -14, 0, -10, 28, 5, 20, "STONE")
    box(c, "terrace_rock", -9, 5, -7, 18, 4, 14, "ANDESITE")
    box(c, "moss_cap", -7, 9, -5, 14, 1, 10, "MOSS_BLOCK")
    box(c, "root", -11, 6, 5, 8, 1, 3, "ROOTED_DIRT")

    c = collection("fh_arena_approach", "arena")
    road_surface(c, 17, 64)
    box(c, "ruin_left", -18, 1, -20, 5, 8, 5, MOSSY_STONE)
    box(c, "ruin_right", 13, 1, -20, 5, 8, 5, MOSSY_STONE)
    box(c, "threshold", -15, 1, 22, 30, 2, 6, STONE)
    for z in [-24, -8, 8, 24]:
        lamp(c, -10, 1, z)
        lamp(c, 9, 1, z)

    c = collection("fh_first_gate_platform", "arena")
    anchor(c)
    box(c, "arena_floor", -30, 0, -30, 60, 2, 60, STONE)
    box(c, "inner_ring", -22, 2, -22, 44, 1, 44, MOSS)
    box(c, "center", -6, 3, -6, 12, 2, 12, "POLISHED_DEEPSLATE")
    box(c, "gate_left", -18, 3, -32, 6, 16, 6, "DEEPSLATE_BRICKS")
    box(c, "gate_right", 12, 3, -32, 6, 16, 6, "DEEPSLATE_BRICKS")
    box(c, "gate_top", -18, 17, -32, 36, 6, 6, "DEEPSLATE_BRICKS")
    for x, z in [(-30, -30), (27, -30), (-30, 27), (27, 27)]:
        box(c, f"boundary_{x}_{z}", x, 2, z, 3, 6, 3, MOSSY_STONE)


def main() -> None:
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    output = Path(args[0]) if args else Path("floor1_kit.blend")
    clean_scene()
    create_kit()
    output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=str(output))
    print(f"Wrote editable CrownsTerrain Floor 1 Blender kit: {output}")


if __name__ == "__main__":
    main()
