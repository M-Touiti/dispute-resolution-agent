# ADR 0004: Local sentence-embedding model behind a port

- Status: accepted
- Date: 2026-11-19
- Deciders: repository owner

## Context

`policy-mcp` answers `search_policy` with hybrid retrieval: PostgreSQL full-text search and pgvector similarity,
merged by reciprocal-rank fusion. The vector half needs an embedding model for the corpus (at ingestion) and for
each query.

Constraints:

1. **One API key.** The repository runs with an Anthropic key only (ADR 0001), and nothing in M0 or in CI needs
   a key at all. The Messages API does not produce embeddings, so a hosted embedding model would be a second
   provider, a second secret and a second bill.
2. **CI and compose run offline from model providers.** Replay mode must not depend on a network call to embed a
   query.
3. **Small corpus.** A few dozen policy sections; a few hundred chunks at most. Model quality matters less than
   for open-domain search, and full text carries exact terms (amounts, day counts).
4. **Image size and start-up time** count: `policy-mcp` builds in CI on every pull request and starts in the
   compose smoke test.

Three local runtimes were measured on 16 Nov 2026 in the `policy-mcp` base image (`docs/ROADMAP.md`, task 1.1):

| Runtime | Model | venv | Cold load | 512 passages | Recall@1 / @3 on a 16-question probe |
|---|---|---|---|---|---|
| `sentence-transformers` 6.3.0 + CPU `torch` | `all-MiniLM-L6-v2`, 384 dimensions | 1,112 MB | 4.5 s | 0.96 s | 0.56 / 0.88 |
| `fastembed` 0.9.1 on ONNX Runtime | `all-MiniLM-L6-v2`, 384 dimensions | 187 MB | 0.6 s | 1.00 s | 0.56 / 0.88 |
| `model2vec` 0.10.0, static | `potion-base-8M`, 256 dimensions | 92 MB | 0.8 s | 0.01 s | 0.50 / 0.88 |

The probe is a sanity check (16 self-written passages and paraphrased questions), not a quality benchmark.

## Decision

- **An `Embedder` port in `policy-mcp`** with one operation: embed a list of texts into unit-length vectors of a
  fixed dimension. Retrieval code depends on the port only.
- **Production adapter: `fastembed` with `sentence-transformers/all-MiniLM-L6-v2`** (384 dimensions). Same model
  as the `sentence-transformers` reference with identical rankings on the probe, at a sixth of the install size
  and a seventh of the cold load, without torch.
- **The model is baked into the image at build time**, its revision pinned, so containers never download at
  start-up and CI never reaches Hugging Face after the build.
- **Test adapter: a deterministic embedder** (hash-seeded vectors) for unit tests of fusion and ranking logic;
  the real model runs only in the retrieval-quality test and in the image.
- **Exact search, no vector index.** At this corpus size a sequential scan with cosine distance is exact and
  fast; an HNSW index is added only if the corpus grows by orders of magnitude.
- **Fallback, measured in task 1.6 on the real corpus:** `model2vec` with `potion-base-8M` if its retrieval
  quality on the golden questions matches `fastembed`'s; otherwise the recorded scope cut, full text only.

## Consequences

- The repository still needs a single API key, and CI none.
- The embedding model and the corpus become inputs of every investigation: a different ranking changes the
  `search_policy` results in the prompt, which changes the request hash and fails replay. A change of model,
  model revision, chunking or corpus therefore falls under ADR 0001's re-baseline rule, and the embedding model
  id and revision are recorded with the ingested corpus.
- ONNX Runtime on different CPUs may differ in the last bits of a float; for a ranking of a few dozen sections
  that can swap near-ties. Retrieval returns ties in a stable order (by section id), and the replay fixtures are
  recorded and replayed with the same image.
- The image grows by about 190 MB of runtime and 90 MB of model, against about 1.2 GB with torch.

## Alternatives considered

- **A hosted embedding API from another provider.** Stronger models, no runtime in the image. Rejected by constraints 1 and 2: a second key and a network dependency in replay.
- **`sentence-transformers` with CPU torch.** The reference implementation. Rejected on size and start-up time
  for no measured gain.
- **`model2vec` as the primary.** Smallest and fastest. Kept as the measured fallback: its probe score is one
  question lower, which proves nothing either way on 16 questions; 1.6 decides on the real corpus.
- **Full-text search only.** Simplest, and the recorded scope cut. Rejected as the default because paraphrased
  questions ("the parcel never came") miss sections written in policy language ("goods not received").
