#!/usr/bin/env python3
"""Read-only FE8J evidence probe; ROM/state bytes are never written or modified.

Usage: python scripts/research_fe8j.py --registry .local/targets.json
       --state .local/m1/thor/fe8-cn-main.state --output .local/m1/fe8j-evidence.json

This is research tooling, not a runtime adapter or a compatibility claim.
Pointers/structures are documented in docs/FE8J_EVIDENCE.md.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct


TARGETS = {
    "44fd343625ab9e6b90f63a80758c15066d526e6873fae91474006314a5ead464": "R03",
    "4853c904c1c180f837678f3d0032dbbb86049bc710fb1993fb51bad8c49b1076": "R04",
}
LITERALS = {
    "character_id1": 0x10650, "character_id1_second_reference": 0x10268,
    "class_id0": 0x17860, "item_id0": 0x17568,
    "item_id0_second_reference": 0x17554, "text_table": 0xA000,
    "huffman_base": 0x6E0, "huffman_root_pointer": 0x6DC,
    "convoy_ram": 0x31470, "maptask_proc": 0x30F24,
    "prep_trade_proc": 0x9E120,
    "player_phase_proc": 0x1549C, "player_phase_proc_second_reference": 0x154E8,
    "map_event_proc": 0xD470, "battle_event_proc": 0xD488,
}
CODE_REGIONS = {
    "prep_trade_apply_swap": (0x9D800, 0x2C),
    "prep_trade_start": (0x9E100, 0x24),
    "prep_trade_script": (0xA94D60, 0x48),
    "unit_remove_blank_items": (0x1772C, 0x54),
    "has_convoy_access": (0x31568, 0x44),
    "convoy_get_clear_compact_count_add_find": (0x3144C, 0x11C),
    "game_lock_increment_decrement_get": (0x15384, 0x2C),
    "player_phase_main_idle": (0x1C5A8, 0x200),
    "player_phase_script_through_main_idle": (0x5C2F58, 0x80),
    "battle_map_main_script_prefix": (0x5C2670, 0x100),
}


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def rom_offset(data: bytes, pointer: int, size: int = 1) -> int:
    if not 0x08000000 <= pointer < 0x0A000000:
        raise ValueError(f"not a ROM pointer: {pointer:#x}")
    offset = pointer - 0x08000000
    if offset + size > len(data):
        raise ValueError(f"ROM read outside file: {pointer:#x}+{size}")
    return offset


def text_bytes(data: bytes, text_id: int) -> bytes:
    """Bounded raw Huffman decode. Does not invent a Chinese glyph mapping."""
    table = rom_offset(data, u32(data, 0xA000), 4)
    text_pointer = u32(data, table + text_id * 4)
    if text_pointer & 0x80000000:
        raise ValueError("anti-Huffman text is outside this probe's proven scope")
    cursor = rom_offset(data, text_pointer)
    base = rom_offset(data, u32(data, 0x6E0), 4)
    root_pointer = rom_offset(data, u32(data, 0x6DC), 4)
    root = rom_offset(data, u32(data, root_pointer), 4)
    node = root
    output = bytearray()
    for value in data[cursor:cursor + 4096]:
        for bit in range(8):
            edge = node + ((value >> bit) & 1) * 2
            if not 0 <= edge <= len(data) - 2:
                raise ValueError("Huffman edge outside ROM")
            node = base + u16(data, edge) * 4
            if not 0 <= node <= len(data) - 4:
                raise ValueError("Huffman node outside ROM")
            leaf = u32(data, node)
            if not leaf & 0x80000000:
                continue
            node = root
            code = leaf & 0xFFFF
            if code & 0xFF == 0:
                return bytes(output)
            output.append(code & 0xFF)
            if code >> 8:
                output.append(code >> 8)
            if len(output) > 1024:
                raise ValueError("Huffman output limit exceeded")
    raise ValueError("Huffman input limit exceeded")


def inspect_rom(path: Path) -> dict:
    data = path.read_bytes()
    digest = sha(data)
    if digest not in TARGETS:
        raise ValueError(f"unsupported exact ROM SHA256: {digest}")
    result = {
        "target": TARGETS[digest], "sha256": digest, "size": len(data),
        "literal_values": {key: f"0x{u32(data, offset):08X}" for key, offset in LITERALS.items()},
        "code_region_sha256": {key: sha(data[start:start + length]) for key, (start, length) in CODE_REGIONS.items()},
    }
    root_pointer = rom_offset(data, u32(data, 0x6DC), 4)
    result["huffman_root"] = f"0x{u32(data, root_pointer):08X}"
    samples = {}
    for label, literal, index, stride in (
        ("character_1", 0x10650, 0, 0x34),
        ("class_1", 0x17860, 1, 0x54),
        ("item_1", 0x17568, 1, 0x24),
        ("item_2", 0x17568, 2, 0x24),
    ):
        address = rom_offset(data, u32(data, literal)) + index * stride
        name_id = u16(data, address)
        raw = text_bytes(data, name_id)
        samples[label] = {
            "address": f"0x{address + 0x08000000:08X}",
            "name_id": f"0x{name_id:04X}", "name_bytes": raw.hex(),
            "name": raw.decode("cp932", errors="strict") if result["target"] == "R03" else None,
            "text_status": "original_cp932" if result["target"] == "R03" else "custom_glyph_mapping_unverified",
            "record_sha256": sha(data[address:address + stride]),
        }
    result["sample_records"] = samples
    # A few exact pointer-literal hits tie the RAM layout to this particular ROM.
    result["ram_literal_reference_samples"] = {}
    for value in (0x0202BCAC, 0x0202BCEC, 0x0202BE48, 0x0202E4D0,
                  0x0202E4D4, 0x0202E4D8, 0x03004DF0, 0x02024E68, 0x02026A70):
        needle = struct.pack("<I", value)
        hits = []
        start = 0
        while len(hits) < 5:
            start = data.find(needle, start, 0xE0000)
            if start < 0:
                break
            if start % 4 == 0:
                hits.append(f"0x{start:06X}")
            start += 1
        result["ram_literal_reference_samples"][f"0x{value:08X}"] = hits
    return result


def inspect_state(path: Path) -> dict:
    data = path.read_bytes()
    if data[:8] != b"ELSTATE2" or len(data) != 128 + 397312 + 32768 + 16:
        raise ValueError("expected the documented ELSTATE2/mGBA state envelope")
    wram = data[128 + 0x21000:128 + 0x61000]
    iwram = data[128 + 0x19000:128 + 0x21000]
    pool, roots, stride, count = 0x02024E68, 0x02026A70, 0x6C, 64
    seen = set()
    procs = []

    def visit(pointer: int) -> None:
        if not pointer:
            return
        if pointer in seen or len(seen) >= count:
            raise ValueError("cycle or repeated node in Proc forest")
        if not pool <= pointer < pool + stride * count or (pointer - pool) % stride:
            raise ValueError(f"Proc pointer outside aligned pool: {pointer:#x}")
        seen.add(pointer)
        offset = pointer - 0x02000000
        procs.append({"address": f"0x{pointer:08X}",
                      "script": f"0x{u32(wram, offset):08X}",
                      "cursor": f"0x{u32(wram, offset + 4):08X}",
                      "idle": f"0x{u32(wram, offset + 0xC):08X}",
                      "parent": f"0x{u32(wram, offset + 0x14):08X}",
                      "child": f"0x{u32(wram, offset + 0x18):08X}",
                      "sleep": struct.unpack_from("<h", wram, offset + 0x24)[0],
                      "flags": wram[offset + 0x27],
                      "lock": wram[offset + 0x28]})
        visit(u32(wram, offset + 0x18))
        visit(u32(wram, offset + 0x20))

    root_values = [u32(wram, roots - 0x02000000 + i * 4) for i in range(8)]
    for root in root_values:
        visit(root)
    units = []
    for faction, base, slots in (("player", 0x2BE48, 62), ("enemy", 0x2CFB8, 50), ("npc", 0x2DDC8, 20)):
        for index in range(slots):
            offset = base + index * 0x48
            char_pointer = u32(wram, offset)
            if char_pointer:
                units.append({"faction": faction, "slot": index,
                              "pointer": f"0x{0x02000000 + offset:08X}",
                              "character": f"0x{char_pointer:08X}",
                              "class": f"0x{u32(wram, offset + 4):08X}",
                              "index": wram[offset + 0xB], "level": wram[offset + 8],
                              "hp": wram[offset + 0x13], "max_hp": wram[offset + 0x12],
                              "state": f"0x{u32(wram, offset + 0xC):08X}",
                              "items": [u16(wram, offset + 0x1E + i * 2) for i in range(5)]})
    return {"sha256": sha(data), "size": len(data),
            "bm_state": {"main_loop_ended": wram[0x2BCAC],
                         "game_lock": wram[0x2BCAD], "gfx_semaphore": wram[0x2BCAE],
                         "flags": wram[0x2BCB0]},
            "phase": wram[0x2BCFB], "chapter": wram[0x2BCFA], "turn": u16(wram, 0x2BCFC),
            "cursor": list(struct.unpack_from("<hh", wram, 0x2BCC0)),
            "map_size": list(struct.unpack_from("<hh", wram, 0x2E4D0)),
            "active_unit": f"0x{u32(iwram, 0x4DF0):08X}",
            "roots": [f"0x{value:08X}" for value in root_values],
            "reachable_procs": procs, "units": units,
            "interpretation": "raw evidence only; scene validity must be checked separately"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=Path(".local/targets.json"))
    parser.add_argument("--state", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    registry = json.loads(args.registry.read_text(encoding="utf-8"))
    report = {"schema_version": 1, "roms": [inspect_rom(Path(t["path"])) for t in registry["targets"] if t["sha256"] in TARGETS]}
    if not report["roms"]:
        raise ValueError("registry contains neither exact FE8J target")
    if args.state:
        report["state"] = inspect_state(args.state)
    encoded = json.dumps(report, ensure_ascii=True, indent=2) + "\n"
    if args.output:
        resolved = args.output.resolve()
        allowed = Path(".local").resolve()
        protected = {args.registry.resolve(), *(Path(t["path"]).resolve() for t in registry["targets"])}
        if args.state:
            protected.add(args.state.resolve())
        if not resolved.is_relative_to(allowed) or resolved.suffix.lower() != ".json":
            raise ValueError("research reports must be .json files inside ignored .local/")
        if resolved in protected:
            raise ValueError("refusing to overwrite a research input")
        resolved.parent.mkdir(parents=True, exist_ok=True)
        resolved.write_text(encoded, encoding="utf-8")
        print(f"Wrote read-only evidence report: {args.output}")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
