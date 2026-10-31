#!/usr/bin/env python3
"""Register a local GBA file without copying it or claiming game compatibility.

The declared role is supplied by the user. Header validation establishes only
that the file has a structurally valid GBA header, not that an adapter supports it.
The default registry is local-only and must not be committed.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile


MAX_ROM_SIZE = 32 * 1024 * 1024
ROLES = ("fe8", "fe7", "fe6", "sword-of-heaven-and-earth", "other")


def inspect_rom(rom_path):
    path = Path(rom_path).expanduser().resolve(strict=True)
    if not path.is_file():
        raise ValueError("ROM must be a single regular file")
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        if not 0xC0 <= before.st_size <= MAX_ROM_SIZE:
            raise ValueError("ROM size must be between 192 bytes and 32 MiB")
        header = stream.read(0xC0)
        if len(header) != 0xC0:
            raise ValueError("ROM header is incomplete")
        if header[0xB2] != 0x96:
            raise ValueError("GBA fixed byte at 0xB2 must be 0x96")
        checksum = (-sum(header[0xA0:0xBD]) - 0x19) & 0xFF
        if header[0xBD] != checksum:
            raise ValueError("GBA header checksum does not match")

        sha256 = hashlib.sha256()
        sha1 = hashlib.sha1()
        sha256.update(header)
        sha1.update(header)
        size = len(header)
        while chunk := stream.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_ROM_SIZE:
                raise ValueError("ROM grew beyond 32 MiB while being read")
            sha256.update(chunk)
            sha1.update(chunk)
        after = os.fstat(stream.fileno())
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or size != before.st_size:
            raise ValueError("ROM changed while being read; register it again")

    return {
        "path": str(path),
        "sha256": sha256.hexdigest(),
        "sha1": sha1.hexdigest(),
        "size": size,
        "title": header[0xA0:0xAC].split(b"\0", 1)[0].decode("ascii", errors="replace").rstrip(),
        "game_code": header[0xAC:0xB0].decode("ascii", errors="replace"),
        "revision": header[0xBC],
    }


def _write_registry(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent,
                                         prefix=f".{path.name}.", suffix=".tmp", delete=False) as stream:
            pending = Path(stream.name)
            json.dump(document, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(pending, path)
    finally:
        if pending is not None and pending.exists():
            pending.unlink()


def register_rom(rom_path, role, label=None, registry_path=".local/targets.json"):
    if role not in ROLES:
        raise ValueError("Unknown declared role")
    record = inspect_rom(rom_path)
    source = Path(record["path"])
    registry = Path(registry_path).expanduser().resolve()
    if registry == source or (registry.exists() and os.path.samefile(registry, source)):
        raise ValueError("Registry must not overwrite the ROM")
    if registry.exists():
        with registry.open(encoding="utf-8") as stream:
            document = json.load(stream)
        if (not isinstance(document, dict) or document.get("schema_version") != 1
                or not isinstance(document.get("targets"), list)
                or any(not isinstance(item, dict) for item in document["targets"])):
            raise ValueError("Unsupported or invalid registry format")
    else:
        document = {"schema_version": 1, "targets": []}

    record.update(role=role, label=label if label is not None else record["title"], compatibility_verified=False)
    entries = document["targets"]
    for index, existing in enumerate(entries):
        if (existing.get("sha256"), existing.get("role")) == (record["sha256"], role):
            entries[index] = record
            break
    else:
        entries.append(record)
    _write_registry(registry, document)
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path, help="Existing local GBA ROM; never copied")
    parser.add_argument("--role", required=True, choices=ROLES, help="User-declared role, not compatibility evidence")
    parser.add_argument("--label", help="Optional display label")
    parser.add_argument("--registry", type=Path, default=Path(".local/targets.json"), help="Local-only registry path")
    args = parser.parse_args(argv)
    try:
        record = register_rom(args.rom, args.role, args.label, args.registry)
    except (OSError, ValueError) as error:
        print(f"Registration failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(record, ensure_ascii=False, indent=2))
    print("Role is user-declared; game compatibility has NOT been verified.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
