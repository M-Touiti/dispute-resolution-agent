"""The embedding port (ADR 0004) and its two adapters; the production model is chosen in ADR 0007."""

import hashlib
import math
import re
from collections.abc import Sequence
from typing import Protocol

FASTEMBED_MODEL = "BAAI/bge-small-en-v1.5"
# fastembed serves it from Hugging Face as Qdrant/bge-small-en-v1.5-onnx-Q (quantised ONNX). fastembed cannot pin a
# revision, so the Docker build checks this digest of the downloaded model file instead (ADR 0007).
FASTEMBED_MODEL_SHA256 = "51f1bd0addd6e859e42c2c8021a5e5461385bb676a649f4b269aa445449f2431"


class Embedder(Protocol):
    @property
    def model_id(self) -> str:
        """Identifies the model and its version; stored with the ingested corpus."""
        ...

    @property
    def dimension(self) -> int: ...

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        """Unit-length vectors for passages (the corpus), one per text, in order."""
        ...

    def embed_query(self, text: str) -> list[float]:
        """Unit-length vector for a search query; models trained with a query instruction add it here."""
        ...


class FastEmbedEmbedder:
    """``bge-small-en-v1.5`` on ONNX Runtime through fastembed. The image bakes the model in (see the Dockerfile).

    Queries go through ``query_embed``, which adds the instruction bge was trained with for retrieval queries.
    """

    def __init__(self, cache_dir: str | None = None) -> None:
        from fastembed import TextEmbedding  # imported here so tests with the hash embedder do not load ONNX

        self._model = TextEmbedding(FASTEMBED_MODEL, cache_dir=cache_dir)

    @property
    def model_id(self) -> str:
        return f"fastembed/{FASTEMBED_MODEL}"

    @property
    def dimension(self) -> int:
        return 384

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        return [_unit([float(x) for x in v]) for v in self._model.embed(list(texts), batch_size=64)]

    def embed_query(self, text: str) -> list[float]:
        return _unit([float(x) for x in next(iter(self._model.query_embed(text)))])


class HashEmbedder:
    """Deterministic test double: a hashed bag of words. Similar texts share words, so they share dimensions.

    Not a language model: it cannot match "parcel" to "goods". Unit tests of ingestion, fusion and the tools use it;
    retrieval quality is tested with the real model.
    """

    def __init__(self, dimension: int = 256) -> None:
        self._dimension = dimension

    @property
    def model_id(self) -> str:
        return f"hash-bag-of-words/{self._dimension}"

    @property
    def dimension(self) -> int:
        return self._dimension

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        return [self._one(t) for t in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._one(text)

    def _one(self, text: str) -> list[float]:
        vector = [0.0] * self._dimension
        for word in re.findall(r"[a-z0-9]+", text.lower()):
            h = hashlib.sha256(word.encode()).digest()
            index = int.from_bytes(h[:4], "big") % self._dimension
            vector[index] += 1.0 if h[4] % 2 == 0 else -1.0
        return _unit(vector)


def _unit(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in vector))
    return vector if norm == 0 else [x / norm for x in vector]


def bake(cache_dir: str) -> None:
    """Downloads the production model into ``cache_dir`` and checks its digest; the Docker build runs this."""
    from pathlib import Path

    FastEmbedEmbedder(cache_dir=cache_dir).embed(["warm up"])
    files = list(Path(cache_dir).rglob("model_optimized.onnx"))
    if len(files) != 1:
        raise SystemExit(f"expected one model_optimized.onnx under {cache_dir}, found {files}")
    digest = hashlib.sha256(files[0].read_bytes()).hexdigest()
    if digest != FASTEMBED_MODEL_SHA256:
        raise SystemExit(f"model digest {digest} != pinned {FASTEMBED_MODEL_SHA256}: the upstream model changed")
    print(f"baked {FASTEMBED_MODEL} into {cache_dir} (sha256 {digest[:12]}...)")


if __name__ == "__main__":
    import sys

    if sys.argv[1:2] != ["--bake"] or len(sys.argv) != 3:
        raise SystemExit("usage: python -m policy_mcp.embedding --bake <cache dir>")
    bake(sys.argv[2])
