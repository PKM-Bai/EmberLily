import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "register_rom.py"
SPEC = importlib.util.spec_from_file_location("register_rom", SCRIPT)
register = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(register)


def synthetic_rom():
    header = bytearray(0xC0)
    header[0xA0:0xAC] = b"SYNTHETIC FE"
    header[0xAC:0xB0] = b"TEST"
    header[0xB2] = 0x96
    header[0xBC] = 2
    header[0xBD] = (-sum(header[0xA0:0xBD]) - 0x19) & 0xFF
    return bytes(header) + b"synthetic test data; no game content"


class RegisterRomTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.rom = self.root / "sample.gba"
        self.registry = self.root / ".local" / "targets.json"
        self.data = synthetic_rom()
        self.rom.write_bytes(self.data)

    def test_registers_original_path_and_hashes_without_changing_or_copying_rom(self):
        record = register.register_rom(self.rom, "fe8", "测试样本", self.registry)
        self.assertEqual(self.data, self.rom.read_bytes())
        self.assertEqual([self.rom], list(self.root.rglob("*.gba")))
        self.assertEqual(str(self.rom.resolve()), record["path"])
        self.assertEqual(hashlib.sha256(self.data).hexdigest(), record["sha256"])
        self.assertEqual(hashlib.sha1(self.data).hexdigest(), record["sha1"])
        self.assertEqual(len(self.data), record["size"])
        self.assertEqual(("SYNTHETIC FE", "TEST", 2), (record["title"], record["game_code"], record["revision"]))
        self.assertFalse(record["compatibility_verified"])
        self.assertEqual(record, json.loads(self.registry.read_text(encoding="utf-8"))["targets"][0])

    def test_reregister_updates_same_role_and_fingerprint_without_duplicates(self):
        register.register_rom(self.rom, "fe8", "old", self.registry)
        register.register_rom(self.rom, "fe8", "new", self.registry)
        records = json.loads(self.registry.read_text(encoding="utf-8"))["targets"]
        self.assertEqual(1, len(records))
        self.assertEqual("new", records[0]["label"])

    def test_rejects_invalid_header_without_creating_registry(self):
        for kind in ("short", "fixed_byte", "checksum"):
            with self.subTest(kind=kind):
                data = bytearray(self.data)
                if kind == "short":
                    data = data[:0xBF]
                elif kind == "fixed_byte":
                    data[0xB2] = 0
                    data[0xBD] = (-sum(data[0xA0:0xBD]) - 0x19) & 0xFF
                else:
                    data[0xBD] ^= 1
                self.rom.write_bytes(data)
                with self.assertRaises(ValueError):
                    register.register_rom(self.rom, "fe8", registry_path=self.registry)
                self.assertFalse(self.registry.exists())

    def test_rejects_oversized_file_and_directory(self):
        with self.rom.open("r+b") as stream:
            stream.truncate(register.MAX_ROM_SIZE + 1)
        with self.assertRaises(ValueError):
            register.inspect_rom(self.rom)
        with self.assertRaises(ValueError):
            register.inspect_rom(self.root)

    def test_registry_cannot_overwrite_original_rom(self):
        with self.assertRaises(ValueError):
            register.register_rom(self.rom, "fe8", registry_path=self.rom)
        self.assertEqual(self.data, self.rom.read_bytes())

    def test_failed_publish_preserves_old_registry_and_removes_pending_file(self):
        register.register_rom(self.rom, "fe8", "old", self.registry)
        previous = self.registry.read_bytes()
        with patch.object(register.os, "replace", side_effect=OSError("simulated publish failure")):
            with self.assertRaises(OSError):
                register.register_rom(self.rom, "fe8", "new", self.registry)
        self.assertEqual(previous, self.registry.read_bytes())
        self.assertEqual([self.registry], list(self.registry.parent.iterdir()))

    def test_invalid_existing_registry_is_not_replaced(self):
        self.registry.parent.mkdir()
        self.registry.write_text('{"schema_version": 999, "targets": []}', encoding="utf-8")
        previous = self.registry.read_bytes()
        with self.assertRaises(ValueError):
            register.register_rom(self.rom, "other", registry_path=self.registry)
        self.assertEqual(previous, self.registry.read_bytes())

    def test_cli_uses_local_default_and_does_not_claim_support(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--rom", str(self.rom), "--role", "fe7"],
                                cwd=self.root, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.registry.is_file())
        self.assertIn("NOT been verified", result.stderr)
        self.assertFalse(json.loads(result.stdout)["compatibility_verified"])

    def test_fe6_role_is_accepted_as_declaration_without_claiming_header_identity(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--rom", str(self.rom), "--role", "fe6"],
                                cwd=self.root, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        record = json.loads(result.stdout)
        self.assertEqual("fe6", record["role"])
        self.assertEqual("TEST", record["game_code"])
        self.assertFalse(record["compatibility_verified"])
        self.assertIn("NOT been verified", result.stderr)
        self.assertEqual(self.data, self.rom.read_bytes())


if __name__ == "__main__":
    unittest.main()
