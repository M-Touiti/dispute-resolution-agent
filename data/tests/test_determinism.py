import hashlib
import os
import subprocess
import sys
from pathlib import Path

from datagen.generator import Config, generate
from datagen.writers import write_dataset

DATA_DIR = Path(__file__).resolve().parent.parent


def _files(directory: Path) -> dict[str, bytes]:
    return {p.name: p.read_bytes() for p in sorted(directory.iterdir())}


def test_same_seed_writes_the_same_bytes(tmp_path, config):
    write_dataset(generate(config), config, tmp_path / "a")
    write_dataset(generate(config), config, tmp_path / "b")

    a, b = _files(tmp_path / "a"), _files(tmp_path / "b")
    assert a.keys() == b.keys()
    for name in a:
        assert a[name] == b[name], name


def test_different_seed_writes_different_bytes(tmp_path, config):
    other = Config(seed=config.seed + 1)
    m1 = write_dataset(generate(config), config, tmp_path / "a")
    m2 = write_dataset(generate(other), other, tmp_path / "b")

    assert m1["sha256"]["transactions.csv"] != m2["sha256"]["transactions.csv"]


def test_output_does_not_depend_on_hash_randomisation(tmp_path):
    """String hashing is randomised per process; generation must not depend on it (no set iteration order)."""
    manifests = []
    for hash_seed in ("1", "2"):
        out = tmp_path / hash_seed
        env = {**os.environ, "PYTHONHASHSEED": hash_seed}
        subprocess.run(
            [
                sys.executable,
                str(DATA_DIR / "generate.py"),
                "--seed",
                "7",
                "--cardholders",
                "40",
                "--disputes",
                "34",
                "--out",
                str(out),
            ],
            check=True,
            env=env,
            cwd=DATA_DIR,
            capture_output=True,
        )
        manifests.append((out / "manifest.json").read_bytes())

    assert manifests[0] == manifests[1]


def test_manifest_hashes_match_the_files(written):
    out, manifest = written
    for name, digest in manifest["sha256"].items():
        assert hashlib.sha256((out / name).read_bytes()).hexdigest() == digest


def test_output_is_ascii_with_lf_line_endings(written):
    out, _ = written
    for path in out.iterdir():
        content = path.read_bytes()
        assert content.isascii(), path.name
        assert b"\r" not in content, path.name
