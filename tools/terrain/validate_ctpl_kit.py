#!/usr/bin/env python3
"""Validate and report a CrownsTerrain .ctpl structure kit."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_ctpl(path: Path) -> dict:
    key = path.stem
    palette: dict[str, str] = {}
    blocks = 0
    max_x = 0
    max_y = 0
    max_z = 0
    in_palette = False
    in_layers = False
    y = 0
    z = 0
    warnings: list[str] = []
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.rstrip("\n")
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("key:"):
            key = stripped.split(":", 1)[1].strip()
            continue
        if stripped == "palette:":
            in_palette = True
            in_layers = False
            continue
        if stripped == "layers:":
            in_palette = False
            in_layers = True
            continue
        if in_palette:
            if "=" not in stripped:
                warnings.append(f"Malformed palette line: {stripped}")
                continue
            symbol, value = stripped.split("=", 1)
            if len(symbol) != 1:
                warnings.append(f"Palette symbol should be one character: {symbol}")
            if not value.strip():
                warnings.append(f"Empty palette value for symbol {symbol}")
            palette[symbol] = value.strip()
            continue
        if in_layers:
            if stripped.startswith("y="):
                y = int(stripped[2:].strip())
                z = 0
                max_y = max(max_y, y)
                continue
            for x, symbol in enumerate(line):
                if symbol == "." or symbol == " ":
                    continue
                if symbol not in palette:
                    warnings.append(f"Layer references missing palette symbol {symbol!r} at y={y} x={x} z={z}")
                blocks += 1
                max_x = max(max_x, x)
                max_z = max(max_z, z)
            z += 1
    if not palette:
        warnings.append("Missing palette")
    if blocks == 0:
        warnings.append("Template contains no non-air blocks")
    if max_x > 96 or max_z > 96 or max_y > 64:
        warnings.append(f"Large bounds {max_x + 1}x{max_y + 1}x{max_z + 1}; check placement cost")
    return {
        "key": key,
        "file": str(path),
        "blocks": blocks,
        "palette": len(palette),
        "size": [max_x + 1, max_y + 1, max_z + 1],
        "warnings": warnings,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate CrownsTerrain .ctpl templates and write a kit report.")
    parser.add_argument("folder", type=Path, help="Folder containing .ctpl files.")
    parser.add_argument("--report", type=Path, help="Optional JSON report path.")
    args = parser.parse_args()

    templates = [parse_ctpl(path) for path in sorted(args.folder.glob("*.ctpl"))]
    errors = [warning for template in templates for warning in template["warnings"]]
    report = {
        "folder": str(args.folder),
        "template_count": len(templates),
        "block_count": sum(template["blocks"] for template in templates),
        "templates": templates,
        "warning_count": len(errors),
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    for template in templates:
        print(f"{template['key']}: {template['blocks']} blocks, size {template['size']}, palette {template['palette']}")
        for warning in template["warnings"]:
            print(f"  warning: {warning}")
    if errors:
        raise SystemExit(f"Validation failed with {len(errors)} warning(s).")
    print(f"Validated {len(templates)} CrownsTerrain template(s).")


if __name__ == "__main__":
    main()
