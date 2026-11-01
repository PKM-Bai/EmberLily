#!/usr/bin/env python3
"""Read-only FE7J evidence probe; ROM/state bytes are never written or modified.

Usage: python scripts/research_fe7j.py --registry .local/targets.json
       --state .local/m2/probes/r06-idle.state --output .local/m2/fe7j-evidence.json

This is research tooling, not a runtime adapter or a compatibility claim.
Pointers/structures are documented in docs/FE7J_EVIDENCE.md.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct


TARGETS = {
    "7ec691a7411864ab51765028156c5b8a3c23a317d23c95fbf07ac201212e25c0": "R02",
    "828b8bb1bff54a66b89cb1e43901d88bb783a4da6e543ac31aa6edc17e162d25": "R06",
}
LITERALS = {
    "character_id1": 0x9AC50, "class_id0": 0x17CE0,
    "item_id0": 0x164DC, "item_id0_second_reference": 0x176E0,
    "text_table": 0x13370, "huffman_base": 0x6E0, "huffman_root_pointer": 0x6DC,
    "maptask_proc": 0x2E644, "bmap_main_proc": 0x2E874,
    "player_phase_proc": 0x21A08, "player_idle": 0xC0269C,
    "player_move": 0xC026DC, "fade_proc": 0x1DA34,
    "event_proc": 0xAED8, "dialogue_proc": 0xAD1C,
    "prep_trade_proc": 0x951F8,
    "convoy": 0x2EBF0, "convoy_second_reference": 0x2EC58,
}
CODE_REGIONS = {
    "prep_trade_apply_swap": (0x948A0, 0x2C),
    "prep_trade_start": (0x951D8, 0x24),
    "prep_trade_script": (0xD8CF74, 0x48),
    "unit_remove_blank_items": (0x17A78, 0x54),
    "player_phase_main_idle": (0x1C624, 0x200),
    "player_phase_script_through_main_idle": (0xC02630, 0x70),
    "battle_map_main_script_prefix": (0xC01DBC, 0x100),
    "event_script": (0xBFFF78, 0xA0),
    "convoy_count": (0x2EC3C, 0x20),
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
    table = rom_offset(data, u32(data, 0x13370), 4)
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
        ("character_1", 0x9AC50, 0, 0x34),
        ("class_1", 0x17CE0, 1, 0x54),
        ("item_1", 0x164DC, 1, 0x24),
        ("item_2", 0x164DC, 2, 0x24),
    ):
        address = rom_offset(data, u32(data, literal)) + index * stride
        name_id = u16(data, address)
        raw = text_bytes(data, name_id)
        samples[label] = {
            "address": f"0x{address + 0x08000000:08X}",
            "name_id": f"0x{name_id:04X}", "name_bytes": raw.hex(),
            "name": raw.decode("cp932", errors="strict") if result["target"] == "R02" else None,
            "text_status": "original_cp932" if result["target"] == "R02" else "custom_glyph_mapping_unverified",
            "record_sha256": sha(data[address:address + stride]),
        }
    result["sample_records"] = samples
    # A few exact pointer-literal hits tie the RAM layout to this particular ROM.
    result["ram_literal_reference_samples"] = {}
    for value in (0x0202BBB4, 0x0202BBF4, 0x0202BD4C, 0x0202E3D4,
                  0x0202E3D8, 0x0202E3DC, 0x030045B0, 0x02024E20, 0x02026A28):
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
    if data[32:64].hex() not in TARGETS:
        raise ValueError("state ROM fingerprint is not a registered FE7 target")
    wram = data[128 + 0x21000:128 + 0x61000]
    iwram = data[128 + 0x19000:128 + 0x21000]
    pool, roots, stride, count = 0x02024E20, 0x02026A28, 0x6C, 64
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
    for faction, base, slots in (("player", 0x2BD4C, 62), ("enemy", 0x2CEBC, 50), ("npc", 0x2DCCC, 20)):
        for index in range(slots):
            offset = base + index * 0x48
            char_pointer = u32(wram, offset)
            if char_pointer:
                units.append({"faction": faction, "slot": index,
                              "pointer": f"0x{0x02000000 + offset:08X}",
                              "character": f"0x{char_pointer:08X}",
                              "class": f"0x{u32(wram, offset + 4):08X}",
                              "index": wram[offset + 0xB], "level": wram[offset + 8],
                              "x": struct.unpack_from("<b", wram, offset + 0x10)[0],
                              "y": struct.unpack_from("<b", wram, offset + 0x11)[0],
                              "hp": wram[offset + 0x13], "max_hp": wram[offset + 0x12],
                              "state": f"0x{u32(wram, offset + 0xC):08X}",
                              "items": [u16(wram, offset + 0x1E + i * 2) for i in range(5)]})
    return {"sha256": sha(data), "size": len(data), "rom_sha256": data[32:64].hex(),
            "bm_state": {"main_loop_ended": wram[0x2BBB4],
                         "game_lock": wram[0x2BBB5], "gfx_semaphore": wram[0x2BBB6],
                         "flags": wram[0x2BBB8]},
            "phase": wram[0x2BC03], "chapter": wram[0x2BC02], "turn": u16(wram, 0x2BC04),
            "cursor": list(struct.unpack_from("<hh", wram, 0x2BBC8)),
            "map_size": list(struct.unpack_from("<hh", wram, 0x2E3D4)),
            "map_rows": {name: f"0x{u32(wram, address):08X}" for name, address in
                         (("unit", 0x2E3D8), ("terrain", 0x2E3DC), ("movement", 0x2E3E0),
                          ("range", 0x2E3E4), ("fog", 0x2E3E8), ("hidden", 0x2E3EC))},
            "active_unit": f"0x{u32(iwram, 0x45B0):08X}",
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
        raise ValueError("registry contains neither exact FE7J target")
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
