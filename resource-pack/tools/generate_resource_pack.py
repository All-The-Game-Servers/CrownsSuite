from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import stat
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "1.8.1"
PACK_NAME = f"CrownsSuite-ResourcePack-{VERSION}"
PACK_DIR = ROOT / "resource-pack" / PACK_NAME
BUILD_DIR = ROOT / "build" / "resource-pack"
DOWNLOADS_DIR = ROOT / "downloads"
ZIP_PATH = BUILD_DIR / f"{PACK_NAME}.zip"
SHA1_PATH = BUILD_DIR / f"{PACK_NAME}.sha1"
DOWNLOAD_ZIP_PATH = DOWNLOADS_DIR / f"{PACK_NAME}.zip"
DOWNLOAD_SHA1_PATH = DOWNLOADS_DIR / f"{PACK_NAME}.sha1"
INDEX_PATH = ROOT / "resource-pack" / "ASSET_INDEX.md"
CONTACT_SHEET_PATH = BUILD_DIR / f"{PACK_NAME}-contact-sheet.png"
MODEL_REPORT_PATH = BUILD_DIR / f"{PACK_NAME}-model-report.json"
BLOCKBENCH_SOURCE_DIR = ROOT / "resource-pack" / "source" / "blockbench"
RESOURCE_PACK_FORMAT = 84
TRANSPARENT = (0, 0, 0, 0)


def rgba(hex_value: str, alpha: int = 255) -> tuple[int, int, int, int]:
    value = hex_value.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha


PALETTES: dict[str, dict[str, tuple[int, int, int, int]]] = {
    "suite": {"shade": rgba("#08060D"), "trim": rgba("#45345F"), "gold": rgba("#D1AC58"), "paper": rgba("#D7C8AF"), "accent": rgba("#8066C7"), "glow": rgba("#73E7FF"), "wood": rgba("#4F3324"), "metal": rgba("#858094"), "cloth": rgba("#5D436E"), "glass": rgba("#58C7D8")},
    "economy": {"shade": rgba("#080604"), "trim": rgba("#5C421F"), "gold": rgba("#E2B75B"), "paper": rgba("#D5C8A9"), "accent": rgba("#427C52"), "glow": rgba("#7FE29D"), "wood": rgba("#624323"), "metal": rgba("#9B855D"), "cloth": rgba("#43644C"), "glass": rgba("#79D89A")},
    "admin": {"shade": rgba("#07080C"), "trim": rgba("#4C5564"), "gold": rgba("#BAC4CF"), "paper": rgba("#D4DAE0"), "accent": rgba("#9C3448"), "glow": rgba("#E05A70"), "wood": rgba("#3C2F2E"), "metal": rgba("#88919C"), "cloth": rgba("#5A2733"), "glass": rgba("#D24D63")},
    "nether": {"shade": rgba("#090302"), "trim": rgba("#5A2117"), "gold": rgba("#E1A656"), "paper": rgba("#B69277"), "accent": rgba("#D14425"), "glow": rgba("#FF7D2F"), "wood": rgba("#4C2018"), "metal": rgba("#7A4636"), "cloth": rgba("#6B1C17"), "glass": rgba("#FFB15F")},
    "end": {"shade": rgba("#05050C"), "trim": rgba("#382B64"), "gold": rgba("#E2CD70"), "paper": rgba("#E3DAC7"), "accent": rgba("#8065F3"), "glow": rgba("#78EAFF"), "wood": rgba("#30273E"), "metal": rgba("#8478AF"), "cloth": rgba("#4C3A7D"), "glass": rgba("#9CFAFF")},
    "drugs": {"shade": rgba("#050607"), "trim": rgba("#3E3445"), "gold": rgba("#D3AD62"), "paper": rgba("#D9E5DF"), "accent": rgba("#4FA35D"), "glow": rgba("#5AF4C0"), "wood": rgba("#46382A"), "metal": rgba("#8C8C86"), "cloth": rgba("#405A48"), "glass": rgba("#8AF5D8")},
    "mmo": {"shade": rgba("#05070D"), "trim": rgba("#2F3C72"), "gold": rgba("#D8B35D"), "paper": rgba("#D8CFBA"), "accent": rgba("#6684F5"), "glow": rgba("#8BD7FF"), "wood": rgba("#453827"), "metal": rgba("#7F8CB6"), "cloth": rgba("#354780"), "glass": rgba("#B2EAFF")},
    "mmo_f1": {"shade": rgba("#040805"), "trim": rgba("#496C42"), "gold": rgba("#CFA05A"), "paper": rgba("#C8D8B8"), "accent": rgba("#67B66C"), "glow": rgba("#9DF09A"), "wood": rgba("#4F4324"), "metal": rgba("#7A8E61"), "cloth": rgba("#4E7045"), "glass": rgba("#AEEAA8")},
    "mmo_f2": {"shade": rgba("#04070B"), "trim": rgba("#435063"), "gold": rgba("#BFC6CD"), "paper": rgba("#CBD8E2"), "accent": rgba("#567AA9"), "glow": rgba("#7FCFFF"), "wood": rgba("#33404B"), "metal": rgba("#8A98A4"), "cloth": rgba("#3D587A"), "glass": rgba("#96DCFF")},
    "mmo_f3": {"shade": rgba("#03030A"), "trim": rgba("#33264F"), "gold": rgba("#E4CB75"), "paper": rgba("#DAD0E6"), "accent": rgba("#7D58DF"), "glow": rgba("#B985FF"), "wood": rgba("#2B213A"), "metal": rgba("#8F80B6"), "cloth": rgba("#4F3382"), "glass": rgba("#C8A4FF")},
    "terrain": {"shade": rgba("#040806"), "trim": rgba("#345C42"), "gold": rgba("#D5B06A"), "paper": rgba("#D7D1B8"), "accent": rgba("#5FA36D"), "glow": rgba("#9AF2B0"), "wood": rgba("#594326"), "metal": rgba("#77866A"), "cloth": rgba("#4E6B49"), "glass": rgba("#B1E6C0")},
    "magic": {"shade": rgba("#05040A"), "trim": rgba("#3E2F65"), "gold": rgba("#D5B86A"), "paper": rgba("#DCD3EA"), "accent": rgba("#8C65F2"), "glow": rgba("#92F1FF"), "wood": rgba("#3B2A4E"), "metal": rgba("#8876B3"), "cloth": rgba("#5B3F88"), "glass": rgba("#B4F6FF")},
    "swords": {"shade": rgba("#05070A"), "trim": rgba("#26394F"), "gold": rgba("#D6B56C"), "paper": rgba("#D5DCE8"), "accent": rgba("#5B8EE8"), "glow": rgba("#9CD8FF"), "wood": rgba("#3C3328"), "metal": rgba("#A0AFC4"), "cloth": rgba("#334B69"), "glass": rgba("#B9E8FF")},
}

MATERIAL_UVS = {
    "shade": [0, 0, 16, 16],
    "trim": [16, 0, 32, 16],
    "gold": [32, 0, 48, 16],
    "paper": [48, 0, 64, 16],
    "accent": [0, 16, 16, 32],
    "glow": [16, 16, 32, 32],
    "wood": [32, 16, 48, 32],
    "metal": [48, 16, 64, 32],
    "cloth": [0, 32, 16, 48],
    "glass": [16, 32, 32, 48],
    "dark": [32, 32, 48, 48],
    "edge": [48, 32, 64, 48],
}


@dataclass(frozen=True)
class Asset:
    path: str
    key: str
    palette: str
    archetype: str


def group(prefix: str, palette: str, names: list[str]) -> list[Asset]:
    return [Asset(f"lowlight/{prefix}/{name}", name, palette, infer_archetype(name, f"lowlight/{prefix}/{name}")) for name in names]


def infer_archetype(key: str, model_path: str) -> str:
    if key in {"nav_back", "nav_close"}:
        return key
    if key in {"focus"}:
        return "crystal"
    if key in {"spellbook"}:
        return "scroll"
    if key in {"elemental", "restoration", "astral", "starlight_flicker", "arcane_ward", "gravity_snare", "starfall_spark", "moonlit_veil", "flame_wave", "stone_skin", "cleansing_light", "renewing_circle", "stellar_beacon", "void_tether"}:
        return "sigil"
    if key in {"ember_bolt", "astral_lance"}:
        return "crystal"
    if key in {"aether_step", "wind_step"}:
        return "gate"
    if key in {"verdant_mend"}:
        return "herb"
    if key in {"excalibur"}:
        return "excalibur"
    if key in {"training_blade"}:
        return "bow"
    if key in {"skillbook"}:
        return "scroll"
    if key in {"flash", "linear", "horizontal_arc", "guard_breaker", "whirling_edge", "rising_cut", "crescent_lunge", "piercing_flash", "meteor_slash", "afterimage_chain", "mirage_edge"}:
        return "sigil"
    if key in {"starburst_step", "shadowstep_cut"}:
        return "gate"
    if key in {"guard", "aegis_parry", "phantom_riposte", "iron_wall", "counter_cross"}:
        return "shield"
    if key in {"phantom"}:
        return "eye"
    if key in {"wallet", "crowns_economy", "top_balances", "gambling_coinflip", "gambling_lottery", "slots_small", "slots_standard", "slots_high"}:
        return "coin"
    if key in {"auction_house"}:
        return "gavel"
    if key in {"market_stalls", "server_trader", "storage", "upgrade_storage"}:
        return "crate"
    if key in {"demand_board", "commissions", "contracts", "jobs", "event_guide", "event_archive", "reports", "guide", "recipes", "quests", "quest", "quest_active", "quest_complete", "quest_detail", "quest_reward", "quest_turnin", "slots_rules"}:
        return "scroll"
    if key in {"resource_pack", "resource_pack_share", "resource_pack_broadcast"}:
        return "pack_crate"
    if key in {"suite_api", "suite_economy", "suite_admin", "suite_events", "suite_drugs", "suite_mmo", "suite_terrain", "status", "profile", "alerts", "inbox", "event_live"}:
        return "sigil"
    if key in {"party"}:
        return "party_token"
    if key in {"guild"}:
        return "banner"
    if key in {"floors", "floor_1", "floor_2", "floor_3", "hub"}:
        return "gate"
    if key in {"village", "market"}:
        return "town_marker"
    if key in {"arena"}:
        return "arena"
    if key in {"landmark", "waystone", "road_marker", "shrine", "watchtower", "camp"}:
        return key
    if key in {"grow", "restock_seeds", "marijuana_raw", "copperleaf"}:
        return "herb"
    if key in {"process", "use", "sell", "restock_supplies", "upgrade_lab", "upgrade_processor", "cocaine_raw", "meth_raw", "gateway_residue"}:
        return "flask"
    if key.endswith("_packaged") or key in {"storage_capacity"}:
        return "packet"
    if "boots" in key:
        return "boots"
    if "bow" in key:
        return "bow"
    if key in {"bastion_guard"} or "shield" in key:
        return "shield"
    if "compass" in key:
        return "compass"
    if "satchel" in key:
        return "satchel"
    if "lantern" in key:
        return "lantern"
    if "cloak" in key:
        return "cloak"
    if "charm" in key:
        return "charm"
    if "helmet" in key or "crown" in key or "trophy" in key or "cinders" in key:
        return "crown"
    if key.endswith("_eye"):
        return "eye"
    if key.endswith("_heart"):
        return "heart"
    if key.endswith("_core") or "crystal" in key or "lumen" in key or "flake" in key or "shard" in key or "fragment" in key or "sigil" in key:
        return "crystal"
    if "fiber" in key or "thread" in key or "silk" in key or "filament" in key or "weave" in key:
        return "strand"
    if key in {"skills", "professions", "combat", "actives", "resources", "gear", "custom_floor_drop", "operations", "case_file", "moderation", "player_inspection", "staff_mode", "true_vanish", "analytics", "playtime", "entity_tools", "roles", "puppeteering", "gambling", "gambling_slots", "slots_symbols", "lowlight_god", "nether_week", "endfall_week", "event_rewards", "event_turnin_hand", "event_turnin_all"}:
        return "sigil"
    return "crystal"


ASSETS: list[Asset] = (
    [
        Asset("lowlight/suite/economy", "suite_economy", "suite", "sigil"),
        Asset("lowlight/suite/api", "suite_api", "suite", "sigil"),
        Asset("lowlight/suite/admin", "suite_admin", "admin", "sigil"),
        Asset("lowlight/suite/events", "suite_events", "suite", "sigil"),
        Asset("lowlight/suite/drugs", "suite_drugs", "drugs", "flask"),
        Asset("lowlight/suite/mmo", "suite_mmo", "mmo", "gate"),
        Asset("lowlight/suite/terrain", "suite_terrain", "terrain", "town_marker"),
        Asset("lowlight/suite/profile", "profile", "suite", "sigil"),
        Asset("lowlight/suite/inbox", "inbox", "suite", "scroll"),
        Asset("lowlight/suite/alerts", "alerts", "suite", "sigil"),
        Asset("lowlight/suite/status", "status", "suite", "compass"),
        Asset("lowlight/suite/resource_pack", "resource_pack", "suite", "pack_crate"),
        Asset("lowlight/suite/resource_pack_share", "resource_pack_share", "suite", "pack_crate"),
        Asset("lowlight/suite/resource_pack_broadcast", "resource_pack_broadcast", "suite", "pack_crate"),
        Asset("lowlight/suite/nav_back", "nav_back", "suite", "nav_back"),
        Asset("lowlight/suite/nav_close", "nav_close", "suite", "nav_close"),
        Asset("lowlight/suite/event_live", "event_live", "suite", "sigil"),
        Asset("lowlight/suite/event_archive", "event_archive", "suite", "scroll"),
        Asset("lowlight/suite/event_rewards", "event_rewards", "suite", "crown"),
        Asset("lowlight/suite/event_guide", "event_guide", "suite", "scroll"),
        Asset("lowlight/suite/event_turnin_hand", "event_turnin_hand", "suite", "sigil"),
        Asset("lowlight/suite/event_turnin_all", "event_turnin_all", "suite", "satchel"),
        Asset("lowlight/suite/nether_week", "nether_week", "nether", "crystal"),
        Asset("lowlight/suite/endfall_week", "endfall_week", "end", "crystal"),
        Asset("lowlight/suite/lowlight_god", "lowlight_god", "end", "sigil"),
    ]
    + group("economy", "economy", [
        "wallet", "auction_house", "market_stalls", "jobs", "demand_board", "server_trader", "gambling",
        "inbox", "top_balances", "commissions", "contracts", "gambling_lottery", "gambling_coinflip", "gambling_slots",
        "slots_small", "slots_standard", "slots_high", "slots_rules", "slots_symbols",
    ])
    + group("admin", "admin", [
        "operations", "case_file", "moderation", "reports", "player_inspection", "staff_mode", "true_vanish",
        "analytics", "playtime", "entity_tools", "roles", "puppeteering",
    ])
    + group("drugs", "drugs", [
        "grow", "process", "use", "sell", "upgrades", "storage", "crowns_economy",
        "restock_seeds", "restock_supplies", "upgrade_lab", "upgrade_storage",
        "upgrade_processor", "storage_capacity", "marijuana_raw", "marijuana_packaged",
        "cocaine_raw", "cocaine_packaged", "meth_raw", "meth_packaged",
    ])
    + group("nether", "nether", ["ember_shard", "gilded_fang", "blaze_sigil", "ancient_core", "crown_fragment"])
    + group("nether/reward", "nether", ["scout_lantern", "ashwalker_boots", "blazebound_bow", "bastion_guard", "crown_of_cinders", "opening_trophy"])
    + group("end", "end", ["echo_shard", "shulker_sigil", "starchart_fragment", "void_core", "crown_of_the_void"])
    + group("end/reward", "end", ["starchart_compass", "voidwalker_boots", "gateway_lantern", "chorus_satchel", "crown_beyond_stars", "endfall_trophy"])
    + group("end/material", "end", ["void_filament", "chorus_weave", "gateway_residue"])
    + group("mmo", "mmo", [
        "floors", "resources", "gear", "recipes", "party", "guild", "skills", "professions", "combat", "actives", "guide",
        "quests", "quest", "quest_active", "quest_complete", "quest_detail", "quest_reward", "quest_turnin", "custom_floor_drop",
    ])
    + group("mmo/floor1", "mmo_f1", ["skyroot_fiber", "gate_splinter", "copperleaf", "gatekeeper_eye", "gatekeeper_trophy"])
    + group("mmo/floor2", "mmo_f2", ["ironbark_plate", "deep_crystal", "warden_thread", "gatekeeper_heart", "gatekeeper_trophy"])
    + group("mmo/floor3", "mmo_f3", ["void_silk", "ancient_lumen", "starmetal_flake", "gatekeeper_core", "gatekeeper_trophy"])
    + group("mmo/gear", "mmo", ["pathfinder_boots", "deep_miner_charm", "gatebreaker_compass", "forager_satchel", "wardenhide_cloak", "veilwalkers_lantern"])
    + group("terrain", "terrain", ["hub", "village", "arena", "landmark", "camp", "road_marker", "waystone", "shrine", "market", "watchtower", "floor_1", "floor_2", "floor_3"])
    + group("magic", "magic", ["focus", "spellbook"])
    + group("magic/schools", "magic", ["elemental", "restoration", "astral"])
    + group("magic/spells", "magic", [
        "starlight_flicker", "ember_bolt", "aether_step", "verdant_mend", "arcane_ward", "gravity_snare",
        "starfall_spark", "moonlit_veil", "astral_lance", "flame_wave", "wind_step", "stone_skin",
        "cleansing_light", "renewing_circle", "stellar_beacon", "void_tether",
    ])
    + group("swords", "swords", ["skillbook", "training_blade", "excalibur"])
    + group("swords/styles", "swords", ["flash", "guard", "phantom"])
    + group("swords/skills", "swords", [
        "linear", "horizontal_arc", "starburst_step", "guard_breaker", "aegis_parry", "whirling_edge",
        "rising_cut", "crescent_lunge", "phantom_riposte", "piercing_flash", "meteor_slash",
        "afterimage_chain", "iron_wall", "counter_cross", "shadowstep_cut", "mirage_edge",
    ])
)


class Canvas:
    def __init__(self, w: int, h: int):
        self.w = w
        self.h = h
        self.pixels = [[TRANSPARENT for _ in range(w)] for _ in range(h)]

    def blend(self, x: int, y: int, color: tuple[int, int, int, int]) -> None:
        if not (0 <= x < self.w and 0 <= y < self.h):
            return
        sr, sg, sb, sa = color
        if sa <= 0:
            return
        dr, dg, db, da = self.pixels[y][x]
        alpha = sa / 255.0
        out_a = sa + da * (1.0 - alpha)
        if out_a <= 0:
            return
        self.pixels[y][x] = (
            int((sr * sa + dr * da * (1.0 - alpha)) / out_a),
            int((sg * sa + dg * da * (1.0 - alpha)) / out_a),
            int((sb * sa + db * da * (1.0 - alpha)) / out_a),
            int(out_a),
        )

    def rect(self, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int]) -> None:
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for x in range(min(x0, x1), max(x0, x1) + 1):
                self.blend(x, y, color)

    def line(self, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int]) -> None:
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.blend(x0, y0, color)
            if x0 == x1 and y0 == y1:
                break
            e2 = err * 2
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def circle(self, cx: int, cy: int, radius: int, color: tuple[int, int, int, int]) -> None:
        rr = radius * radius
        for y in range(cy - radius, cy + radius + 1):
            for x in range(cx - radius, cx + radius + 1):
                if (x - cx) ** 2 + (y - cy) ** 2 <= rr:
                    self.blend(x, y, color)

    def paste(self, source: "Canvas", x0: int, y0: int) -> None:
        for y in range(source.h):
            for x in range(source.w):
                self.blend(x0 + x, y0 + y, source.pixels[y][x])


def chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(path: Path, canvas: Canvas) -> None:
    raw = bytearray()
    for row in canvas.pixels:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)
    payload = zlib.compress(bytes(raw), 9)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", canvas.w, canvas.h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", payload)
        + chunk(b"IEND", b"")
    )


def remove_readonly(func, path, _exc_info) -> None:
    os.chmod(path, stat.S_IWRITE)
    func(path)


def normalize(model_path: str) -> tuple[str, str]:
    namespace, key = model_path.split("/", 1)
    return namespace, key


def atlas_name(asset: Asset) -> str:
    return asset.palette


def write_atlas(palette_name: str) -> Path:
    palette = PALETTES[palette_name]
    atlas = Canvas(64, 64)
    material_order = ["shade", "trim", "gold", "paper", "accent", "glow", "wood", "metal", "cloth", "glass", "dark", "edge"]
    for index, material in enumerate(material_order):
        col = index % 4
        row = index // 4
        x0 = col * 16
        y0 = row * 16
        base = palette["shade"] if material == "dark" else palette["trim"] if material == "edge" else palette[material]
        for y in range(16):
            for x in range(16):
                light = 1.0 + ((x + y) % 5 - 2) * 0.035
                if x in {0, 15} or y in {0, 15}:
                    light *= 0.72
                if material == "glow":
                    light *= 1.12
                atlas.blend(x0 + x, y0 + y, (
                    max(0, min(255, int(base[0] * light))),
                    max(0, min(255, int(base[1] * light))),
                    max(0, min(255, int(base[2] * light))),
                    255,
                ))
    path = PACK_DIR / "assets" / "lowlight" / "textures" / "item" / "atlases" / f"{palette_name}.png"
    write_png(path, atlas)
    return path


def face(material: str) -> dict[str, object]:
    return {"uv": MATERIAL_UVS[material], "texture": "#0"}


def element(name: str, frm: list[float], to: list[float], material: str, rotation: dict[str, object] | None = None) -> dict[str, object]:
    faces = {side: face(material) for side in ("north", "south", "east", "west", "up", "down")}
    data: dict[str, object] = {"name": name, "from": frm, "to": to, "faces": faces}
    if rotation:
        data["rotation"] = rotation
    return data


def zrot(angle: float, origin: list[float] | None = None) -> dict[str, object]:
    return {"origin": origin or [8, 8, 8], "axis": "z", "angle": angle, "rescale": True}


def xrot(angle: float, origin: list[float] | None = None) -> dict[str, object]:
    return {"origin": origin or [8, 8, 8], "axis": "x", "angle": angle, "rescale": True}


def display(kind: str) -> dict[str, object]:
    handheld = kind in {"bow", "gavel", "compass", "lantern", "flask", "crystal", "charm"}
    if kind == "excalibur":
        return {
            "gui": {"rotation": [35, 225, -22], "translation": [0, 0, 0], "scale": [0.92, 0.92, 0.92]},
            "ground": {"rotation": [0, 0, -35], "translation": [0, 3, 0], "scale": [0.48, 0.48, 0.48]},
            "fixed": {"rotation": [0, 180, -35], "translation": [0, 0, 0], "scale": [0.78, 0.78, 0.78]},
            "thirdperson_righthand": {"rotation": [70, -25, 0], "translation": [0, 3.5, 1], "scale": [0.72, 0.72, 0.72]},
            "firstperson_righthand": {"rotation": [0, -90, 35], "translation": [1.13, 3.2, 1.13], "scale": [0.72, 0.72, 0.72]},
        }
    return {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.82, 0.82, 0.82]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.42, 0.42, 0.42]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.72, 0.72, 0.72]},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
        "firstperson_righthand": {"rotation": [0, -90, 25 if handheld else 0], "translation": [1.13, 3.2, 1.13], "scale": [0.58, 0.58, 0.58]},
    }


def model_elements(archetype: str) -> list[dict[str, object]]:
    e: list[dict[str, object]] = []
    if archetype == "nav_back":
        e += [
            element("back_arrow_bar", [4, 7, 7], [14, 9, 9], "paper", zrot(0)),
            element("back_arrow_head_a", [3, 7, 7], [9, 9, 9], "glow", zrot(45)),
            element("back_arrow_head_b", [3, 7, 7], [9, 9, 9], "glow", zrot(-45)),
            element("token_shadow", [5, 4, 8.4], [12, 12, 9.1], "trim"),
        ]
    elif archetype == "nav_close":
        e += [
            element("close_token", [4, 4, 7.4], [12, 12, 8.4], "trim"),
            element("slash_a", [7, 3, 6.8], [9, 13, 9.2], "glow", zrot(45)),
            element("slash_b", [7, 3, 6.9], [9, 13, 9.3], "accent", zrot(-45)),
        ]
    elif archetype == "coin":
        e += [
            element("coin_body", [4, 4, 6.8], [12, 12, 9.2], "gold"),
            element("coin_inner", [5, 5, 6.4], [11, 11, 9.6], "trim"),
            element("coin_crown", [6, 7, 5.8], [10, 9, 10.2], "glow"),
        ]
    elif archetype == "gavel":
        e += [
            element("gavel_head", [4, 9, 5.5], [13, 12, 10.5], "wood", zrot(-22.5)),
            element("gavel_band_l", [4, 9, 5.2], [5, 12, 10.8], "gold", zrot(-22.5)),
            element("gavel_band_r", [12, 9, 5.2], [13, 12, 10.8], "gold", zrot(-22.5)),
            element("gavel_handle", [7, 2, 7.2], [9, 11, 8.8], "wood", zrot(45)),
        ]
    elif archetype in {"crate", "pack_crate"}:
        e += [
            element("crate_body", [3, 4, 4.5], [13, 12, 11.5], "wood"),
            element("crate_lid", [2.5, 11, 4], [13.5, 13, 12], "trim"),
            element("crate_strap_a", [5, 3.8, 3.8], [6.5, 13.2, 12.2], "gold"),
            element("crate_strap_b", [9.5, 3.8, 3.8], [11, 13.2, 12.2], "gold"),
        ]
        if archetype == "pack_crate":
            e.append(element("pack_rune", [6, 6, 3.7], [10, 10, 4.3], "glow"))
    elif archetype == "scroll":
        e += [
            element("scroll_sheet", [4, 3, 6.6], [12, 13, 8.8], "paper", zrot(-22.5)),
            element("scroll_roll_top", [3.3, 2.6, 6], [12.7, 4.1, 9.2], "gold", zrot(-22.5)),
            element("scroll_roll_bottom", [3.3, 11.9, 6], [12.7, 13.4, 9.2], "gold", zrot(-22.5)),
            element("ink_line_a", [5, 6, 5.8], [11, 6.6, 9.5], "trim", zrot(-22.5)),
            element("ink_line_b", [5, 8.5, 5.8], [10, 9.1, 9.5], "trim", zrot(-22.5)),
        ]
    elif archetype == "sigil":
        e += [
            element("sigil_backplate", [3.5, 3.5, 6.4], [12.5, 12.5, 9.6], "trim"),
            element("sigil_core", [5, 5, 5.9], [11, 11, 10.1], "accent"),
            element("sigil_gem", [6.5, 6.5, 5.3], [9.5, 9.5, 10.7], "glow"),
            element("sigil_cap", [5.5, 2.5, 7], [10.5, 4, 9], "gold"),
        ]
    elif archetype == "crystal":
        e += [
            element("crystal_core", [6.2, 3, 6.2], [9.8, 13, 9.8], "glow", zrot(22.5)),
            element("crystal_side_a", [4.5, 6, 6.6], [7.5, 12, 9.4], "accent", zrot(-22.5)),
            element("crystal_side_b", [8.5, 7, 6.6], [11.5, 12.5, 9.4], "paper", zrot(22.5)),
            element("crystal_base", [5, 12, 6], [11, 14, 10], "trim"),
        ]
    elif archetype == "flask":
        e += [
            element("flask_neck", [6.7, 3, 6.5], [9.3, 6, 9.5], "glass"),
            element("flask_cork", [6.3, 2, 6.2], [9.7, 3.3, 9.8], "wood"),
            element("flask_body", [4, 6, 5.2], [12, 13, 10.8], "glass"),
            element("flask_liquid", [4.7, 9, 4.8], [11.3, 12.4, 11.2], "glow"),
        ]
    elif archetype == "packet":
        e += [
            element("packet_body", [4, 4, 5.5], [12, 13, 10.5], "cloth"),
            element("packet_label", [5.2, 6, 5], [10.8, 9, 11], "paper"),
            element("packet_seal", [6.5, 9.5, 4.7], [9.5, 12, 11.3], "gold"),
        ]
    elif archetype == "herb":
        e += [
            element("stem", [7.3, 5, 7.2], [8.7, 14, 8.8], "trim", zrot(-22.5)),
            element("leaf_l", [3.5, 7, 6.5], [8, 10, 9.5], "accent", zrot(-45)),
            element("leaf_r", [8, 5.5, 6.5], [12.5, 8.5, 9.5], "glow", zrot(45)),
            element("leaf_top", [5.5, 3.5, 6.5], [10.5, 6.5, 9.5], "accent", zrot(0)),
        ]
    elif archetype == "crown":
        e += [
            element("crown_band", [4, 9, 5.5], [12, 12, 10.5], "gold"),
            element("crown_spike_l", [4, 5, 6], [6, 10, 10], "gold", zrot(-22.5)),
            element("crown_spike_c", [7, 4, 5.6], [9, 10.5, 10.4], "gold"),
            element("crown_spike_r", [10, 5, 6], [12, 10, 10], "gold", zrot(22.5)),
            element("crown_gem", [6.5, 7.2, 5], [9.5, 9.5, 11], "glow"),
        ]
    elif archetype == "boots":
        e += [
            element("boot_l_leg", [4, 5, 5.5], [7, 11, 9.5], "cloth"),
            element("boot_l_foot", [3, 10, 5], [8, 13, 10], "gold"),
            element("boot_r_leg", [9, 5, 5.5], [12, 11, 9.5], "cloth"),
            element("boot_r_foot", [8, 10, 5], [13, 13, 10], "gold"),
            element("boot_rune", [5, 12.5, 4.6], [11, 13.5, 10.4], "glow"),
        ]
    elif archetype == "shield":
        e += [
            element("shield_face", [4, 3.5, 5.5], [12, 12, 10.5], "accent"),
            element("shield_boss", [6.2, 6, 5], [9.8, 9.5, 11], "gold"),
            element("shield_tip", [6, 11, 6], [10, 14, 10], "trim"),
        ]
    elif archetype == "bow":
        e += [
            element("bow_upper", [5, 3, 7], [7, 10, 9], "wood", zrot(-22.5)),
            element("bow_lower", [9, 6, 7], [11, 13, 9], "wood", zrot(-22.5)),
            element("bow_string", [7.5, 3, 7.6], [8.5, 13, 8.4], "paper", zrot(22.5)),
            element("bow_flame", [7, 7, 6.4], [10, 10, 9.6], "glow"),
        ]
    elif archetype == "excalibur":
        e += [
            element("excalibur_blade_core", [7.2, 1.2, 7.1], [8.8, 12.4, 8.9], "metal", zrot(-35)),
            element("excalibur_blade_left_bevel", [6.45, 2.0, 7.2], [7.35, 11.8, 8.8], "paper", zrot(-35)),
            element("excalibur_blade_right_bevel", [8.65, 2.0, 7.2], [9.55, 11.8, 8.8], "paper", zrot(-35)),
            element("excalibur_tip", [7.05, 0.2, 7.2], [8.95, 2.3, 8.8], "glow", zrot(-35)),
            element("excalibur_fuller", [7.75, 2.1, 6.75], [8.25, 10.6, 9.25], "glow", zrot(-35)),
            element("excalibur_guard_bar", [4.1, 11.2, 6.6], [11.9, 12.55, 9.4], "gold", zrot(-35)),
            element("excalibur_guard_left_flare", [3.2, 10.6, 6.7], [5.4, 12.2, 9.3], "gold", zrot(-57.5)),
            element("excalibur_guard_right_flare", [10.6, 11.6, 6.7], [12.8, 13.2, 9.3], "gold", zrot(-12.5)),
            element("excalibur_sapphire_cross", [6.7, 10.4, 6.1], [9.3, 12.6, 9.9], "glow", zrot(-35)),
            element("excalibur_grip", [7.0, 12.0, 7.1], [9.0, 15.4, 8.9], "shade", zrot(-35)),
            element("excalibur_grip_wrap_a", [6.75, 12.6, 6.8], [9.25, 13.05, 9.2], "trim", zrot(-35)),
            element("excalibur_grip_wrap_b", [6.75, 13.65, 6.8], [9.25, 14.1, 9.2], "trim", zrot(-35)),
            element("excalibur_pommel", [6.6, 14.9, 6.5], [9.4, 16.0, 9.5], "gold", zrot(-35)),
            element("excalibur_pommel_gem", [7.25, 14.55, 6.1], [8.75, 15.85, 9.9], "glow", zrot(-35)),
        ]
    elif archetype == "compass":
        e += [
            element("compass_ring", [3.5, 3.5, 5.8], [12.5, 12.5, 10.2], "gold"),
            element("compass_face", [5, 5, 5.3], [11, 11, 10.7], "paper"),
            element("needle_a", [7.3, 3.8, 4.7], [8.7, 11, 11.3], "glow", zrot(22.5)),
            element("needle_b", [7.3, 5, 4.8], [8.7, 12.2, 11.2], "accent", zrot(-22.5)),
        ]
    elif archetype == "satchel":
        e += [
            element("satchel_body", [4, 6, 4.8], [12, 13, 11.2], "cloth"),
            element("satchel_flap", [4.5, 4.5, 4.4], [11.5, 8, 11.6], "gold"),
            element("satchel_buckle", [7, 8, 4], [9, 10, 12], "glow"),
        ]
    elif archetype == "lantern":
        e += [
            element("lantern_frame", [5, 5, 5], [11, 13, 11], "gold"),
            element("lantern_glass", [6, 6, 4.6], [10, 12, 11.4], "glass"),
            element("lantern_flame", [7, 8, 4.2], [9, 11, 11.8], "glow"),
            element("lantern_handle", [6, 2.5, 7], [10, 5, 9], "gold"),
        ]
    elif archetype == "cloak":
        e += [
            element("cloak_shoulders", [4, 3, 5.8], [12, 5, 10.2], "gold"),
            element("cloak_body", [3.5, 5, 5.2], [12.5, 14, 10.8], "cloth", zrot(0)),
            element("cloak_rune", [6.5, 6.5, 4.8], [9.5, 10, 11.2], "glow"),
        ]
    elif archetype == "charm":
        e += [
            element("charm_loop", [5, 3, 7], [11, 5, 9], "gold"),
            element("charm_chain", [7.3, 4.5, 7.2], [8.7, 8, 8.8], "metal"),
            element("charm_gem", [5, 8, 5.5], [11, 14, 10.5], "glow", zrot(45)),
        ]
    elif archetype == "strand":
        for i in range(4):
            e.append(element(f"strand_{i}", [4 + i, 4 + i * 1.8, 7], [12 + i * 0.2, 5.2 + i * 1.8, 9], "glow" if i % 2 else "paper", zrot(-22.5)))
        e.append(element("strand_spool", [5, 11, 6], [11, 14, 10], "wood"))
    elif archetype == "eye":
        e += [
            element("eye_white", [3.5, 5.5, 5.5], [12.5, 10.5, 10.5], "paper"),
            element("eye_iris", [6, 6.5, 5], [10, 9.5, 11], "glow"),
            element("eye_pupil", [7.2, 7.2, 4.6], [8.8, 8.8, 11.4], "shade"),
        ]
    elif archetype == "heart":
        e += [
            element("heart_lobe_l", [4.5, 5, 5.5], [8, 8.5, 10.5], "accent"),
            element("heart_lobe_r", [8, 5, 5.5], [11.5, 8.5, 10.5], "accent"),
            element("heart_tip", [5.5, 8, 5.8], [10.5, 13, 10.2], "glow", zrot(45)),
        ]
    elif archetype == "gate":
        e += [
            element("gate_left", [3.5, 4, 5.5], [5.5, 14, 10.5], "trim"),
            element("gate_right", [10.5, 4, 5.5], [12.5, 14, 10.5], "trim"),
            element("gate_top", [4, 3, 5.5], [12, 5, 10.5], "gold"),
            element("gate_void", [6, 7, 5], [10, 13, 11], "glow"),
        ]
    elif archetype == "town_marker":
        e += [
            element("house_body", [4, 7, 5], [12, 13, 11], "wood"),
            element("house_roof_l", [3, 5, 5.2], [8, 8, 10.8], "trim", zrot(-22.5)),
            element("house_roof_r", [8, 5, 5.2], [13, 8, 10.8], "trim", zrot(22.5)),
            element("house_door", [7, 10, 4.5], [9, 13, 11.5], "shade"),
        ]
    elif archetype == "arena":
        e += [
            element("arena_ring", [3, 9, 5.8], [13, 12, 10.2], "trim"),
            element("arena_floor", [4.5, 6, 6.2], [11.5, 11, 9.8], "metal"),
            element("arena_core", [6.5, 6.8, 5.2], [9.5, 9.8, 10.8], "glow"),
        ]
    elif archetype == "camp":
        e += [
            element("camp_base", [4, 11, 5.8], [12, 13, 10.2], "wood"),
            element("tent_l", [4, 6, 5.5], [8.5, 12, 10.5], "cloth", zrot(-22.5)),
            element("tent_r", [7.5, 6, 5.5], [12, 12, 10.5], "cloth", zrot(22.5)),
            element("camp_fire", [7, 12, 5], [9, 14, 11], "glow"),
        ]
    elif archetype == "shrine":
        e += [
            element("shrine_base", [4, 11, 5.5], [12, 13.5, 10.5], "metal"),
            element("shrine_pillar_l", [5, 5, 6], [6.5, 12, 10], "trim"),
            element("shrine_pillar_r", [9.5, 5, 6], [11, 12, 10], "trim"),
            element("shrine_light", [6.5, 5.5, 5], [9.5, 9, 11], "glow"),
        ]
    elif archetype == "waystone":
        e += [
            element("stone_body", [5, 4, 5.5], [11, 14, 10.5], "metal"),
            element("stone_cap", [4.5, 3, 5.8], [11.5, 5, 10.2], "trim"),
            element("stone_rune", [6.5, 7, 4.9], [9.5, 11, 11.1], "glow"),
        ]
    elif archetype == "road_marker":
        e += [
            element("post", [7, 4, 7], [9, 14, 9], "wood"),
            element("sign_top", [4, 5, 6], [12, 8, 10], "paper"),
            element("sign_bottom", [5, 9, 6], [11, 11, 10], "trim"),
        ]
    elif archetype == "landmark":
        e += [
            element("obelisk_base", [4, 12, 5.5], [12, 14, 10.5], "metal"),
            element("obelisk_body", [6, 4, 6], [10, 13, 10], "trim"),
            element("obelisk_tip", [6.8, 2.5, 6.8], [9.2, 5, 9.2], "glow", zrot(45)),
        ]
    elif archetype == "watchtower":
        e += [
            element("tower_body", [5, 5, 5.5], [11, 14, 10.5], "wood"),
            element("tower_top", [3.5, 3, 5], [12.5, 6, 11], "trim"),
            element("tower_light", [7, 4, 4.5], [9, 6, 11.5], "glow"),
        ]
    else:
        e.append(element("fallback_relic", [5, 4, 5.5], [11, 13, 10.5], "glow", zrot(22.5)))
    return e


def write_item_definition(asset: Asset) -> None:
    namespace, key = normalize(asset.path)
    item_def = PACK_DIR / "assets" / namespace / "items" / f"{key}.json"
    item_def.parent.mkdir(parents=True, exist_ok=True)
    item_def.write_text(json.dumps({
        "model": {"type": "minecraft:model", "model": f"{namespace}:item/{key}"}
    }, indent=2), encoding="utf-8")


def write_model(asset: Asset) -> None:
    namespace, key = normalize(asset.path)
    model_def = PACK_DIR / "assets" / namespace / "models" / "item" / f"{key}.json"
    model_def.parent.mkdir(parents=True, exist_ok=True)
    model = {
        "credit": "Crowns Suite generated Blockbench-style dark fantasy item model",
        "ambientocclusion": True,
        "texture_size": [64, 64],
        "textures": {
            "0": f"{namespace}:item/atlases/{atlas_name(asset)}",
            "particle": f"{namespace}:item/atlases/{atlas_name(asset)}",
        },
        "elements": model_elements(asset.archetype),
        "display": display(asset.archetype),
    }
    model_def.write_text(json.dumps(model, indent=2), encoding="utf-8")


def preview(asset: Asset) -> Canvas:
    palette = PALETTES[asset.palette]
    canvas = Canvas(48, 48)
    # Small isometric-ish thumbnail for contact sheets only; runtime uses 3D model JSON.
    for radius, alpha in ((18, 20), (12, 32), (6, 46)):
        for y in range(24 - radius, 24 + radius + 1):
            for x in range(24 - radius, 24 + radius + 1):
                if (x - 24) ** 2 + (y - 24) ** 2 <= radius * radius:
                    canvas.blend(x, y, (*palette["glow"][:3], alpha))
    for item in model_elements(asset.archetype):
        frm = item["from"]
        to = item["to"]
        material = "accent"
        faces = item.get("faces", {})
        if faces:
            uv = next(iter(faces.values())).get("uv", MATERIAL_UVS["accent"])
            for name, value in MATERIAL_UVS.items():
                if value == uv:
                    material = name
                    break
        color = palette["shade"] if material == "dark" else palette["trim"] if material == "edge" else palette.get(material, palette["accent"])
        x0 = 8 + int(frm[0] * 2)
        y0 = 8 + int(frm[1] * 2)
        x1 = 8 + int(to[0] * 2)
        y1 = 8 + int(to[1] * 2)
        canvas.rect(x0, y0, x1, y1, color)
        canvas.line(x0, y0, x1, y0, palette["paper"])
        canvas.line(x0, y0, x0, y1, palette["paper"])
    return canvas


def write_contact_sheet(assets: list[Asset]) -> None:
    columns = 8
    cell = 56
    rows = math.ceil(len(assets) / columns)
    sheet = Canvas(columns * cell, rows * cell)
    for index, asset in enumerate(assets):
        col = index % columns
        row = index // columns
        x = col * cell
        y = row * cell
        sheet.rect(x, y, x + cell - 1, y + cell - 1, (4, 4, 8, 255))
        sheet.paste(preview(asset), x + 4, y + 4)
    write_png(CONTACT_SHEET_PATH, sheet)


def write_pack_files() -> None:
    for stale_pack in (ROOT / "resource-pack").glob("CrownsSuite-ResourcePack-*"):
        if stale_pack.is_dir() and stale_pack.name != PACK_NAME:
            shutil.rmtree(stale_pack, onerror=remove_readonly)
    if PACK_DIR.exists():
        shutil.rmtree(PACK_DIR, onerror=remove_readonly)
    PACK_DIR.mkdir(parents=True, exist_ok=True)
    (PACK_DIR / "pack.mcmeta").write_text(json.dumps({
        "pack": {
            "min_format": RESOURCE_PACK_FORMAT,
            "max_format": RESOURCE_PACK_FORMAT,
            "description": f"Crowns Suite Resource Pack {VERSION} - required 26.1.2 3D dark fantasy redraw."
        }
    }, indent=2), encoding="utf-8")
    lowlight = PACK_DIR / "assets" / "lowlight"
    (lowlight / "font").mkdir(parents=True, exist_ok=True)
    (lowlight / "sounds").mkdir(parents=True, exist_ok=True)
    (lowlight / "font" / "default.json").write_text(json.dumps({"providers": []}, indent=2), encoding="utf-8")
    (lowlight / "sounds.json").write_text("{}", encoding="utf-8")
    (PACK_DIR / "credits.txt").write_text(
        f"Crowns Suite Resource Pack {VERSION}\n"
        "Theme: 3D Dark Fantasy Relics\n"
        "Runtime: stable lowlight/... item model ids with generated Blockbench-style JSON models\n",
        encoding="utf-8"
    )


def write_asset_index() -> None:
    INDEX_PATH.write_text(
        "# Crowns Suite Resource Pack Asset Index\n\n"
        "| Model Path | Archetype | Palette |\n"
        "| --- | --- | --- |\n"
        + "\n".join(f"| `{asset.path}` | `{asset.archetype}` | `{asset.palette}` |" for asset in ASSETS)
        + "\n",
        encoding="utf-8"
    )


def write_blockbench_sources() -> None:
    BLOCKBENCH_SOURCE_DIR.mkdir(parents=True, exist_ok=True)
    for old_model in BLOCKBENCH_SOURCE_DIR.glob("*.bbmodel"):
        old_model.unlink()
    by_family: dict[str, list[Asset]] = {}
    for asset in ASSETS:
        by_family.setdefault(asset.palette, []).append(asset)
    manifest = []
    for family, assets in sorted(by_family.items()):
        sample = assets[0]
        model = {
            "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": f"crowns_{family}_library",
            "geometry_name": f"crowns_{family}_library",
            "visible_box": [2, 2, 2],
            "resolution": {"width": 64, "height": 64},
            "textures": [{
                "path": f"assets/lowlight/textures/item/atlases/{family}.png",
                "name": f"{family}_atlas",
                "folder": "item/atlases",
                "namespace": "lowlight",
                "id": "0",
                "particle": True,
            }],
            "elements": model_elements(sample.archetype),
            "outliner": [element_data["name"] for element_data in model_elements(sample.archetype)],
            "crowns": {
                "version": VERSION,
                "family": family,
                "style": "3d_dark_fantasy_relics",
                "intended_model_paths": [asset.path for asset in assets],
                "note": "This file is an editable Blockbench seed library. Runtime pack generation emits one model per stable lowlight/... path.",
            },
        }
        source = BLOCKBENCH_SOURCE_DIR / f"crowns_{family}_library.bbmodel"
        source.write_text(json.dumps(model, indent=2), encoding="utf-8")
        manifest.append({"source": source.name, "family": family, "count": len(assets), "model_paths": [asset.path for asset in assets]})
    excalibur_source = BLOCKBENCH_SOURCE_DIR / "crowns_swords_excalibur.bbmodel"
    excalibur_model = {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": "crowns_swords_excalibur",
        "geometry_name": "crowns_swords_excalibur",
        "visible_box": [2, 2, 2],
        "resolution": {"width": 64, "height": 64},
        "textures": [{
            "path": "assets/lowlight/textures/item/atlases/swords.png",
            "name": "swords_atlas",
            "folder": "item/atlases",
            "namespace": "lowlight",
            "id": "0",
            "particle": True,
        }],
        "elements": model_elements("excalibur"),
        "outliner": [element_data["name"] for element_data in model_elements("excalibur")],
        "display": display("excalibur"),
        "crowns": {
            "version": VERSION,
            "family": "swords",
            "style": "holy_dark_fantasy_excalibur",
            "intended_model_paths": ["lowlight/swords/excalibur"],
            "note": "Dedicated Excalibur source model. This is the quality target for future authored sword items.",
        },
    }
    excalibur_source.write_text(json.dumps(excalibur_model, indent=2), encoding="utf-8")
    manifest.append({"source": excalibur_source.name, "family": "swords", "count": 1, "model_paths": ["lowlight/swords/excalibur"], "dedicated": True})
    (BLOCKBENCH_SOURCE_DIR / "MANIFEST.json").write_text(json.dumps({
        "version": VERSION,
        "blockbench": "D:\\CrownsSuiteTools\\Apps\\Blockbench-5.1.4-portable.exe",
        "style": "3D Dark Fantasy Relics",
        "sources": manifest,
    }, indent=2), encoding="utf-8")


def validate_pack() -> None:
    pack_meta = json.loads((PACK_DIR / "pack.mcmeta").read_text(encoding="utf-8"))
    pack = pack_meta.get("pack", {})
    if pack.get("min_format") != RESOURCE_PACK_FORMAT or pack.get("max_format") != RESOURCE_PACK_FORMAT:
        raise RuntimeError("pack.mcmeta must target Minecraft 26.1.2 resource pack format 84.")
    if "pack_format" in pack or "supported_formats" in pack:
        raise RuntimeError("26.1.2 pack.mcmeta must not include pack_format or supported_formats.")

    expected = {asset.path for asset in ASSETS}
    for asset in ASSETS:
        namespace, key = normalize(asset.path)
        item_def = PACK_DIR / "assets" / namespace / "items" / f"{key}.json"
        model_def = PACK_DIR / "assets" / namespace / "models" / "item" / f"{key}.json"
        atlas = PACK_DIR / "assets" / namespace / "textures" / "item" / "atlases" / f"{atlas_name(asset)}.png"
        for required in (item_def, model_def, atlas):
            if not required.exists():
                raise RuntimeError(f"Missing resource-pack asset: {required.relative_to(PACK_DIR)}")
        model_json = json.loads(model_def.read_text(encoding="utf-8"))
        if model_json.get("parent") == "minecraft:item/generated":
            raise RuntimeError(f"Flat generated item model is forbidden in {VERSION}: {asset.path}")
        if not model_json.get("elements"):
            raise RuntimeError(f"Model has no 3D elements: {asset.path}")
        texture_ref = model_json.get("textures", {}).get("0", "")
        expected_texture = f"{namespace}:item/atlases/{atlas_name(asset)}"
        if texture_ref != expected_texture:
            raise RuntimeError(f"Invalid texture atlas for {asset.path}: {texture_ref}")

    pattern = re.compile(r"lowlight/[a-z0-9_./-]+")
    referenced: set[str] = set()
    for module in ("api", "magic", "swords", "economy", "admin", "events", "drugs", "mmo", "terrain"):
        search_root = ROOT / module / "src" / "main"
        if not search_root.exists():
            continue
        for file in search_root.rglob("*"):
            if file.suffix.lower() not in {".java", ".yml", ".json"}:
                continue
            referenced.update(pattern.findall(file.read_text(encoding="utf-8", errors="ignore")))
    missing = sorted(path for path in referenced if path not in expected and not path.endswith("/") and not path.endswith("_"))
    if missing:
        raise RuntimeError("Missing generated assets for plugin model paths:\n" + "\n".join(missing))
    if not CONTACT_SHEET_PATH.exists():
        raise RuntimeError("Missing contact sheet.")
    MODEL_REPORT_PATH.write_text(json.dumps({
        "version": VERSION,
        "asset_count": len(ASSETS),
        "families": {palette: sum(1 for asset in ASSETS if asset.palette == palette) for palette in sorted(PALETTES)},
        "archetypes": {archetype: sum(1 for asset in ASSETS if asset.archetype == archetype) for archetype in sorted({asset.archetype for asset in ASSETS})},
        "forbidden_flat_models": 0,
    }, indent=2), encoding="utf-8")


def export_zip() -> str:
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    with ZipFile(ZIP_PATH, "w", ZIP_DEFLATED) as zf:
        for file in PACK_DIR.rglob("*"):
            if file.is_file():
                zf.write(file, file.relative_to(PACK_DIR))
    sha1 = hashlib.sha1(ZIP_PATH.read_bytes()).hexdigest()
    SHA1_PATH.write_text(sha1, encoding="utf-8")
    DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
    for stale in DOWNLOADS_DIR.glob("CrownsSuite-ResourcePack-*"):
        stale.unlink()
    shutil.copy2(ZIP_PATH, DOWNLOAD_ZIP_PATH)
    shutil.copy2(SHA1_PATH, DOWNLOAD_SHA1_PATH)
    return sha1


def update_checksums() -> None:
    checksum_path = DOWNLOADS_DIR / "CHECKSUMS.txt"
    if not checksum_path.exists():
        return
    files = sorted(path for path in DOWNLOADS_DIR.iterdir() if path.is_file() and path.name != "CHECKSUMS.txt")
    lines = []
    for file in files:
        lines.append(f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}")
    checksum_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    write_pack_files()
    for palette_name in sorted({asset.palette for asset in ASSETS}):
        write_atlas(palette_name)
    for asset in ASSETS:
        write_item_definition(asset)
        write_model(asset)
    write_asset_index()
    write_blockbench_sources()
    write_contact_sheet(ASSETS)
    validate_pack()
    sha1 = export_zip()
    update_checksums()
    print(f"Built {ZIP_PATH}")
    print(f"Mirrored {DOWNLOAD_ZIP_PATH}")
    print(f"SHA1 {sha1}")


if __name__ == "__main__":
    main()
