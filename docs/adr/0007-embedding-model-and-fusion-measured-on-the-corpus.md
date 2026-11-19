# ADR 0007: Embedding model and fusion weights measured on the policy corpus

- Status: accepted
- Date: 2026-11-19
- Deciders: repository owner
- Supersedes: the model choice in ADR 0004 (`all-MiniLM-L6-v2`). The rest of ADR 0004 stands: the `Embedder` port,
  `fastembed` on ONNX Runtime, the model baked into the image, the deterministic test embedder, exact search.

## Context

ADR 0004 chose `sentence-transformers/all-MiniLM-L6-v2` on `fastembed` from a 16-question probe over invented
passages, and left two questions to task 1.6, on the real corpus: whether `model2vec` could replace it, and whether
hybrid retrieval earns its place.

Task 1.6 measured retrieval on the 24-section policy corpus with 45 questions in two sets
(`mcp-servers/policy-mcp/tests/retrieval_questions.json`):

- **paraphrase** (30): phrased the way a cardholder would put it, avoiding the policy's words ("my parcel never
  came").
- **agent** (15): phrased the way the investigator queries after reading the case data, in ledger and policy
  vocabulary ("card not present, 3-D Secure not performed, fraud flag").

The first runs showed hybrid retrieval, with every query word OR-ed into the full-text query and both lists equal
in the fusion, doing worse than vector search alone on the paraphrase set (MiniLM recall@1 0.33 against 0.47):
common words match almost every section. Measured configurations, mean reciprocal rank (MRR) per set:

| Model | Retrieval | Paraphrase R@1 / R@3 / MRR | Agent R@1 / R@3 / MRR |
|---|---|---|---|
| `all-MiniLM-L6-v2` | vector only | 0.47 / 0.80 / 0.64 | 0.80 / 0.93 / 0.88 |
| `all-MiniLM-L6-v2` | hybrid, full text weight 1.0 | 0.33 / 0.70 / 0.55 | 0.87 / 1.00 / 0.92 |
| `all-MiniLM-L6-v2` | hybrid, full text weight 0.5 | 0.40 / 0.73 / 0.59 | 0.80 / 1.00 / 0.89 |
| `bge-small-en-v1.5` | vector only | 0.50 / 0.83 / 0.66 | 0.93 / 0.93 / 0.95 |
| `bge-small-en-v1.5` | hybrid, full text weight 1.0 | 0.37 / 0.77 / 0.58 | 0.87 / 1.00 / 0.92 |
| **`bge-small-en-v1.5`** | **hybrid, full text weight 0.5** | **0.50 / 0.80 / 0.65** | **0.93 / 1.00 / 0.96** |
| `model2vec` `potion-base-8M` | hybrid, full text weight 0.5 | 0.23 / 0.70 / 0.49 | 0.80 / 1.00 / 0.87 |
| (none) | full text only | 0.33 / 0.60 / 0.47 | 0.87 / 1.00 / 0.93 |

Requiring every word in the full-text query before falling back to any word changed nothing measurable. The
decision rule was fixed before the agent set was run: best mean MRR over the two sets, treating differences smaller
than one question as ties.

## Decision

- **Model: `BAAI/bge-small-en-v1.5`** (384 dimensions) through `fastembed`, which serves the quantised ONNX export
  `Qdrant/bge-small-en-v1.5-onnx-Q` (65 MB, smaller than MiniLM's 87 MB). Queries go through `query_embed`, which
  adds the retrieval instruction bge was trained with; corpus sections are embedded as passages.
- **Hybrid retrieval with full text at half weight** in reciprocal-rank fusion (`k = 60`, 20 candidates per list,
  ties broken by section id). It ties vector-only search overall (mean MRR 0.801 against 0.804, less than one
  question) and places every agent query in the top three, and full text keeps exact terms such as day counts and
  "3-D Secure" findable whatever the model does with them.
- **`model2vec` is rejected** as a fallback: a quarter of the paraphrase questions at rank one against half.
- **Pinning by digest.** `fastembed` cannot pin a model revision. The Docker build downloads the model and fails
  unless `model_optimized.onnx` has the SHA-256 recorded in `policy_mcp/embedding.py`
  (`51f1bd0a…`, Hugging Face snapshot `aa8f8b06…` on 19 Nov 2026); the container then runs with
  `HF_HUB_OFFLINE=1`.
- **A quality floor in CI.** `test_retrieval_quality.py` runs the production model over both sets and fails below
  a floor one or two questions under the measured values (paraphrase R@1 0.43, R@3 0.73; agent R@1 0.86, R@3
  0.93). The model is cached between CI runs.

## Consequences

- Retrieval for the queries the agent actually sends is near the ceiling of this corpus (agent MRR 0.96); the
  paraphrase set remains hard (R@1 0.50), which matters only if the agent searches with the cardholder's words.
  The investigator's prompt should tell it to search in the case's terms.
- The embedding model id is stored with the ingested corpus; a model change re-ingests at start-up and, through
  the search results in the prompt, re-records fixtures (ADR 0001).
- A change to the corpus or to the questions can move the measured values; the floors are revised in the same
  pull request, with the new numbers, never silently lowered.
- 45 questions is a small sample. The model difference (about 0.05 MRR in every configuration) is consistent; the
  fusion-weight differences are within noise and were decided by the tie rule.
- The image grows to about 760 MB: ONNX Runtime, tokenizers and NumPy, plus the model.

## Alternatives considered

- **Keep `all-MiniLM-L6-v2` (ADR 0004 unchanged).** No new ADR, but measurably weaker on both sets.
- **Vector search only with `bge-small-en-v1.5`.** Highest mean MRR by 0.003 (a tie); drops exact-term matching
  and the full-text half of the design for no measured gain.
- **Tune the fusion weight or `k` further on these questions.** Rejected: with 45 questions, finer tuning would fit
  the test set rather than the corpus.
