from __future__ import annotations

import hashlib
import json
import struct
import zlib
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "1.1.0"
PACK_NAME = f"CrownsSuite-ResourcePack-{VERSION}"
PACK_DIR = ROOT / "resource-pack" / PACK_NAME
BUILD_DIR = ROOT / "build" / "resource-pack"
ZIP_PATH = BUILD_DIR / f"{PACK_NAME}.zip"
SHA1_PATH = BUILD_DIR / f"{PACK_NAME}.sha1"
INDEX_PATH = ROOT / "resource-pack" / "ASSET_INDEX.md"
TRANSPARENT = (0, 0, 0, 0)


def rgba(hex_value: str, alpha: int = 255):
    value = hex_value.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha


PALETTES = {
    "suite": {"trim": rgba("#A67CFF"), "glow": rgba("#6DE4FF", 170), "gold": rgba("#D0A85E"), "paper": rgba("#D9D0BD"), "shade": rgba("#0B0911"), "accent": rgba("#7A56D9")},
    "economy": {"trim": rgba("#7C5C2D"), "glow": rgba("#87C59B", 170), "gold": rgba("#D8B15E"), "paper": rgba("#DAD0BF"), "shade": rgba("#090806"), "accent": rgba("#4C8F5A")},
    "admin": {"trim": rgba("#5E6678"), "glow": rgba("#B4485E", 170), "gold": rgba("#A8B0BE"), "paper": rgba("#CCD3DF"), "shade": rgba("#09090C"), "accent": rgba("#7E8CA3")},
    "nether": {"trim": rgba("#6F2B1A"), "glow": rgba("#FF7C38", 175), "gold": rgba("#D9A454"), "paper": rgba("#B19A88"), "shade": rgba("#0A0505"), "accent": rgba("#C9462B")},
    "end": {"trim": rgba("#43356B"), "glow": rgba("#7BE8FF", 175), "gold": rgba("#DFC86B"), "paper": rgba("#E2D6C4"), "shade": rgba("#06060C"), "accent": rgba("#7F60FF")},
    "drugs": {"trim": rgba("#45364D"), "glow": rgba("#5CF7C8", 170), "gold": rgba("#D7AD65"), "paper": rgba("#D8E4E8"), "shade": rgba("#060507"), "accent": rgba("#58A66B")},
}


def suite_assets():
    return [
        ("lowlight/suite/economy", "suite_economy", "suite"),
        ("lowlight/suite/admin", "suite_admin", "suite"),
        ("lowlight/suite/events", "suite_events", "suite"),
        ("lowlight/suite/drugs", "suite_drugs", "suite"),
        ("lowlight/suite/nav_back", "nav_back", "suite"),
        ("lowlight/suite/nav_close", "nav_close", "suite"),
        ("lowlight/suite/event_live", "event_live", "suite"),
        ("lowlight/suite/event_archive", "event_archive", "suite"),
        ("lowlight/suite/event_rewards", "event_rewards", "suite"),
        ("lowlight/suite/event_guide", "event_guide", "suite"),
        ("lowlight/suite/event_turnin_hand", "event_turnin_hand", "suite"),
        ("lowlight/suite/event_turnin_all", "event_turnin_all", "suite"),
        ("lowlight/suite/nether_week", "nether_week", "nether"),
        ("lowlight/suite/endfall_week", "endfall_week", "end"),
        ("lowlight/suite/lowlight_god", "lowlight_god", "end"),
    ]


def group_assets(prefix: str, palette: str, names: list[str]):
    return [(f"lowlight/{prefix}/{name}", name, palette) for name in names]


ASSETS = (
    suite_assets()
    + group_assets("economy", "economy", [
        "wallet", "auction_house", "market_stalls", "jobs", "demand_board", "server_trader", "gambling",
        "inbox", "top_balances", "gambling_lottery", "gambling_coinflip", "gambling_slots",
        "slots_small", "slots_standard", "slots_high", "slots_rules", "slots_symbols",
    ])
    + group_assets("admin", "admin", [
        "moderation", "reports", "player_inspection", "staff_mode", "true_vanish",
        "analytics", "playtime", "entity_tools", "roles", "puppeteering",
    ])
    + group_assets("drugs", "drugs", [
        "grow", "process", "use", "sell", "upgrades", "storage", "crowns_economy",
        "restock_seeds", "restock_supplies", "upgrade_lab", "upgrade_storage",
        "upgrade_processor", "storage_capacity", "marijuana_raw", "marijuana_packaged",
        "cocaine_raw", "cocaine_packaged", "meth_raw", "meth_packaged",
    ])
    + group_assets("nether", "nether", ["ember_shard", "gilded_fang", "blaze_sigil", "ancient_core", "crown_fragment"])
    + [(f"lowlight/nether/reward/{name}", name, "nether") for name in [
        "scout_lantern", "ashwalker_boots", "blazebound_bow", "bastion_guard", "crown_of_cinders", "opening_trophy"
    ]]
    + group_assets("end", "end", ["echo_shard", "shulker_sigil", "starchart_fragment", "void_core", "crown_of_the_void"])
    + [(f"lowlight/end/reward/{name}", name, "end") for name in [
        "starchart_compass", "voidwalker_boots", "gateway_lantern", "chorus_satchel", "crown_beyond_stars", "endfall_trophy"
    ]]
    + [(f"lowlight/end/material/{name}", name, "end") for name in ["void_filament", "chorus_weave", "gateway_residue"]]
)


class Canvas:
    def __init__(self, w=32, h=32):
        self.w, self.h = w, h
        self.pixels = [[TRANSPARENT for _ in range(w)] for _ in range(h)]

    def blend(self, x, y, color):
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

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.blend(x, y, color)

    def line(self, x0, y0, x1, y1, color):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.blend(x0, y0, color)
            if x0 == x1 and y0 == y1:
                return
            e2 = err * 2
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def circle(self, cx, cy, radius, color):
        rr = radius * radius
        for y in range(cy - radius, cy + radius + 1):
            for x in range(cx - radius, cx + radius + 1):
                if (x - cx) ** 2 + (y - cy) ** 2 <= rr:
                    self.blend(x, y, color)

    def diamond(self, cx, cy, radius, color):
        for dy in range(-radius, radius + 1):
            span = radius - abs(dy)
            for dx in range(-span, span + 1):
                self.blend(cx + dx, cy + dy, color)


def chunk(tag: bytes, data: bytes):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(path: Path, canvas: Canvas):
    raw = bytearray()
    for row in canvas.pixels:
        raw.append(0)
        for rgba_value in row:
            raw.extend(rgba_value)
    payload = zlib.compress(bytes(raw), 9)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", canvas.w, canvas.h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", payload)
        + chunk(b"IEND", b"")
    )


def normalize(model_path: str):
    namespace, key = model_path.split("/", 1)
    return namespace, key


def write_model_files(model_path: str):
    namespace, key = normalize(model_path)
    item_def = PACK_DIR / "assets" / namespace / "items" / f"{key}.json"
    model_def = PACK_DIR / "assets" / namespace / "models" / "item" / f"{key}.json"
    texture_path = PACK_DIR / "assets" / namespace / "textures" / "item" / f"{key}.png"
    item_def.parent.mkdir(parents=True, exist_ok=True)
    model_def.parent.mkdir(parents=True, exist_ok=True)
    item_def.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"{namespace}:item/{key}"}}, indent=2), encoding="utf-8")
    model_def.write_text(json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": f"{namespace}:item/{key}"}}, indent=2), encoding="utf-8")
    return texture_path


def base_canvas(palette_name: str):
    palette = PALETTES[palette_name]
    canvas = Canvas()
    for radius, alpha in ((13, 18), (10, 24), (7, 32)):
        glow = palette["glow"]
        canvas.circle(16, 16, radius, (glow[0], glow[1], glow[2], alpha))
    for ox, oy in ((4, 4), (27, 4), (4, 27), (27, 27)):
        canvas.rect(ox - 1, oy - 1, ox + 1, oy + 1, (palette["trim"][0], palette["trim"][1], palette["trim"][2], 90))
    return canvas, palette


def draw_book(c, p):
    c.rect(8, 9, 14, 23, p["paper"]); c.rect(17, 9, 23, 23, p["paper"])
    c.line(15, 9, 15, 23, p["trim"]); c.line(16, 9, 16, 23, p["trim"])


def draw_coin(c, p):
    c.circle(16, 17, 8, p["gold"]); c.circle(16, 17, 6, (p["trim"][0], p["trim"][1], p["trim"][2], 180))


def draw_crown(c, p, y=12):
    c.rect(9, y + 5, 22, y + 7, p["gold"])
    for a, b, mid in ((9, 12, 12), (12, 15, 16), (17, 20, 20), (20, 22, 20)):
        c.line(a, y + 5, b, y, p["gold"]); c.line(b, y, mid, y + 5, p["gold"])
    c.line(15, y + 5, 16, y - 1, p["gold"]); c.line(16, y - 1, 17, y + 5, p["gold"])


def draw_shard(c, p, accent=None):
    body = accent or p["accent"]
    c.line(16, 7, 10, 15, body); c.line(10, 15, 13, 25, body); c.line(13, 25, 20, 22, body); c.line(20, 22, 23, 12, body); c.line(23, 12, 16, 7, body)
    c.diamond(16, 16, 5, (body[0], body[1], body[2], 130))


def draw_flask(c, p, liquid):
    c.rect(14, 8, 18, 10, p["paper"]); c.line(11, 12, 14, 10, p["paper"]); c.line(21, 12, 18, 10, p["paper"])
    c.line(9, 24, 11, 12, p["paper"]); c.line(23, 24, 21, 12, p["paper"]); c.line(9, 24, 23, 24, p["paper"]); c.rect(11, 18, 21, 23, liquid)


def draw_satchel(c, p):
    c.rect(9, 11, 22, 22, p["gold"]); c.line(11, 11, 14, 7, p["trim"]); c.line(20, 11, 17, 7, p["trim"]); c.rect(13, 15, 18, 17, p["shade"])


def draw_boots(c, p):
    c.rect(9, 10, 14, 19, p["gold"]); c.rect(17, 10, 22, 19, p["gold"]); c.rect(8, 19, 15, 23, p["gold"]); c.rect(16, 19, 23, 23, p["gold"])


def draw_shield(c, p):
    c.rect(10, 8, 22, 14, p["accent"]); c.line(10, 14, 16, 24, p["accent"]); c.line(22, 14, 16, 24, p["accent"]); c.line(16, 8, 16, 24, p["gold"])


def draw_compass(c, p):
    c.circle(16, 16, 9, p["paper"]); c.circle(16, 16, 7, p["shade"])
    c.line(16, 8, 19, 16, p["accent"]); c.line(19, 16, 16, 24, p["accent"]); c.line(16, 24, 13, 16, p["glow"]); c.line(13, 16, 16, 8, p["glow"])


def draw_scroll(c, p):
    c.rect(10, 9, 21, 22, p["paper"]); c.rect(9, 10, 10, 21, p["gold"]); c.rect(21, 10, 22, 21, p["gold"]); c.line(13, 13, 19, 13, p["trim"]); c.line(13, 16, 18, 16, p["trim"]); c.line(13, 19, 17, 19, p["trim"])


def draw_icon(kind: str, palette_name: str):
    c, p = base_canvas(palette_name)
    if kind.endswith("_week"):
        draw_shard(c, p, p["glow"])
    elif kind in {"suite_economy", "wallet", "crowns_economy"}:
        draw_coin(c, PALETTES["economy"]); draw_crown(c, PALETTES["economy"], 10)
    elif kind in {"suite_admin", "moderation"}:
        c.line(11, 22, 21, 10, PALETTES["admin"]["gold"]); c.rect(18, 9, 22, 13, PALETTES["admin"]["accent"])
    elif kind in {"suite_events", "event_live"}:
        draw_crown(c, PALETTES["suite"], 11); draw_shard(c, PALETTES["end"], PALETTES["end"]["glow"])
    elif kind in {"suite_drugs", "process", "use"}:
        draw_flask(c, PALETTES["drugs"], PALETTES["drugs"]["accent"] if kind == "process" else PALETTES["end"]["accent"])
    elif kind in {"nav_back"}:
        c.line(22, 10, 10, 16, p["paper"]); c.line(10, 16, 22, 22, p["paper"]); c.line(10, 16, 24, 16, p["glow"])
    elif kind in {"nav_close"}:
        c.line(9, 9, 23, 23, p["trim"]); c.line(23, 9, 9, 23, p["glow"])
    elif kind in {"event_archive", "event_guide", "slots_rules", "reports", "jobs"}:
        draw_book(c, p)
    elif kind in {"event_rewards", "market_stalls", "storage", "upgrade_storage", "chorus_satchel"}:
        draw_satchel(c, p)
    elif kind in {"event_turnin_hand"}:
        c.rect(11, 16, 22, 22, p["paper"]); draw_shard(c, PALETTES["end"], PALETTES["end"]["glow"])
    elif kind in {"event_turnin_all"}:
        draw_satchel(c, p); draw_shard(c, PALETTES["nether"], PALETTES["nether"]["glow"])
    elif kind in {"auction_house", "gambling_coinflip"}:
        draw_coin(c, p); c.line(11, 12, 21, 22, p["glow"]); c.line(21, 12, 11, 22, p["trim"])
    elif kind in {"demand_board", "inbox"}:
        draw_scroll(c, p)
    elif kind in {"server_trader", "gambling", "slots_standard", "slots_high"}:
        draw_coin(c, p); draw_shard(c, p, p["glow"])
    elif kind in {"gambling_lottery", "slots_small"}:
        draw_coin(c, p)
    elif kind == "top_balances":
        draw_crown(c, p, 8); c.rect(9, 19, 12, 24, p["accent"]); c.rect(14, 15, 17, 24, p["gold"]); c.rect(19, 11, 22, 24, p["glow"])
    elif kind == "slots_symbols":
        c.circle(10, 17, 3, p["accent"]); c.circle(16, 15, 3, p["gold"]); c.circle(22, 17, 3, p["glow"])
    elif kind in {"player_inspection", "playtime", "analytics", "entity_tools", "roles", "puppeteering", "staff_mode", "true_vanish"}:
        draw_compass(c, p)
    elif kind in {"grow", "restock_seeds", "upgrade_lab", "marijuana_raw"}:
        c.line(11, 20, 16, 9, p["accent"]); c.line(16, 9, 21, 14, p["accent"]); c.line(21, 14, 19, 24, p["accent"]); c.line(19, 24, 11, 20, p["accent"])
    elif kind in {"sell", "restock_supplies", "cocaine_raw"}:
        c.circle(12, 18, 2, p["paper"]); c.circle(16, 16, 2, p["paper"]); c.circle(20, 19, 2, p["paper"])
    elif kind in {"upgrades", "upgrade_processor", "storage_capacity", "meth_raw"}:
        draw_shard(c, p, rgba("#6CC9FF"))
    elif kind.endswith("_packaged") or kind in {"cocaine_packaged", "meth_packaged", "marijuana_packaged"}:
        c.rect(10, 8, 21, 23, p["accent"]); c.rect(12, 11, 19, 19, p["paper"])
    elif "boots" in kind:
        draw_boots(c, p)
    elif "shield" in kind or kind == "bastion_guard":
        draw_shield(c, p)
    elif "bow" in kind:
        c.line(10, 8, 10, 24, p["gold"]); c.line(10, 8, 16, 12, p["gold"]); c.line(10, 24, 16, 20, p["gold"]); c.line(16, 8, 16, 24, p["glow"])
    elif "helmet" in kind or "crown" in kind or "trophy" in kind or "cinders" in kind:
        draw_crown(c, p, 10); c.rect(9, 16, 22, 22, p["gold"])
    elif "lantern" in kind:
        c.rect(11, 11, 20, 22, p["gold"]); c.rect(13, 13, 18, 19, p["glow"]); c.line(13, 9, 18, 9, p["gold"])
    elif "compass" in kind:
        draw_compass(c, p)
    elif "filament" in kind:
        [c.line(9, 12 + i * 2, 22, 10 + i * 2, p["glow"]) for i in range(5)]
    elif "weave" in kind:
        c.rect(10, 11, 22, 22, p["paper"]); c.line(10, 14, 22, 20, p["gold"]); c.line(10, 20, 22, 14, p["trim"])
    elif "residue" in kind:
        draw_flask(c, p, p["accent"]); c.line(13, 19, 18, 22, p["glow"]); c.line(18, 19, 13, 22, p["glow"])
    else:
        draw_shard(c, p, p["accent"])
    return c


def write_pack_files():
    PACK_DIR.mkdir(parents=True, exist_ok=True)
    (PACK_DIR / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 46, "supported_formats": {"min_inclusive": 34, "max_inclusive": 46}, "description": "Crowns Suite 1.1.0 - Dark Arcane branding pack."}}, indent=2), encoding="utf-8")
    lowlight = PACK_DIR / "assets" / "lowlight"
    (lowlight / "font").mkdir(parents=True, exist_ok=True)
    (lowlight / "sounds").mkdir(parents=True, exist_ok=True)
    (lowlight / "font" / "default.json").write_text(json.dumps({"providers": []}, indent=2), encoding="utf-8")
    (lowlight / "sounds.json").write_text("{}", encoding="utf-8")
    (PACK_DIR / "credits.txt").write_text("Crowns Suite Resource Pack 1.1.0\nTheme: Dark Arcane\n", encoding="utf-8")


def write_asset_index():
    INDEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    INDEX_PATH.write_text("# Crowns Suite Resource Pack Asset Index\n\n| Model Path | Icon | Palette |\n| --- | --- | --- |\n" + "\n".join(f"| `{p}` | `{k}` | `{pal}` |" for p, k, pal in ASSETS) + "\n", encoding="utf-8")


def export_zip():
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    with ZipFile(ZIP_PATH, "w", ZIP_DEFLATED) as zf:
        for file in PACK_DIR.rglob("*"):
            if file.is_file():
                zf.write(file, file.relative_to(PACK_DIR))
    SHA1_PATH.write_text(hashlib.sha1(ZIP_PATH.read_bytes()).hexdigest(), encoding="utf-8")


def main():
    write_pack_files()
    for model_path, kind, palette_name in ASSETS:
        texture = write_model_files(model_path)
        write_png(texture, draw_icon(kind, palette_name))
    write_asset_index()
    export_zip()
    print(f"Built {ZIP_PATH}")


if __name__ == "__main__":
    main()
