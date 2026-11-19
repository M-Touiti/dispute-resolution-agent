"""Reads the policy corpus (format: data/policy-corpus/README.md)."""

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

ALL_CATEGORIES = "ALL"

_METADATA = re.compile(r"^Section `([a-z0-9]+(?:-[a-z0-9]+)*)`; version ([1-9][0-9]*); applies to: (.+)$")


@dataclass(frozen=True, slots=True)
class Section:
    section_id: str
    title: str
    version: int
    applies_to: tuple[str, ...]
    """Category names, or (``ALL``,) for a section that applies to every category."""
    text: str
    source_file: str


def corpus_files(corpus_dir: Path) -> list[Path]:
    files = sorted(p for p in corpus_dir.glob("*.md") if p.name != "README.md")
    if not files:
        raise FileNotFoundError(f"no policy files in {corpus_dir}")
    return files


def read_corpus(corpus_dir: Path) -> list[Section]:
    sections = [s for f in corpus_files(corpus_dir) for s in _parse(f)]
    ids = [s.section_id for s in sections]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise ValueError(f"duplicate section ids: {duplicates}")
    return sections


def fingerprint(corpus_dir: Path) -> str:
    """SHA-256 over every corpus file's name and bytes; changes whenever the corpus does."""
    digest = hashlib.sha256()
    for f in corpus_files(corpus_dir):
        digest.update(f.name.encode())
        digest.update(b"\0")
        digest.update(f.read_bytes())
    return digest.hexdigest()


def _parse(path: Path) -> list[Section]:
    lines = path.read_text(encoding="utf-8").splitlines()
    starts = [i for i, line in enumerate(lines) if line.startswith("## ")]
    sections = []
    for n, start in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        meta = _METADATA.match(lines[start + 2]) if start + 2 < len(lines) else None
        if meta is None:
            raise ValueError(f"{path.name}:{start + 3}: expected the section metadata line")
        applies = meta.group(3).strip()
        sections.append(
            Section(
                section_id=meta.group(1),
                title=lines[start][3:].strip(),
                version=int(meta.group(2)),
                applies_to=(ALL_CATEGORIES,)
                if applies == "all categories"
                else tuple(c.strip() for c in applies.split(",")),
                text="\n".join(lines[start + 3 : end]).strip(),
                source_file=path.name,
            )
        )
    return sections
