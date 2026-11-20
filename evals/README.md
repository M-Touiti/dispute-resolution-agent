# Evaluation

Python 3.13, uv, standard library only at run time. Run from this directory.

| Path | Contents | Status |
|---|---|---|
| `golden/` | Golden sets selected from the generated ground truth, committed | M1: `unauthorised-v1.json`, 30 cases |
| `evaltools/` | `golden.py` selector; runner and deterministic graders | selector done; graders in task 1.15 |
| `fixtures/llm/` | Recorded model requests and responses, `<sha256 of the canonical request>.json` (ADR 0006); served by the replay adapter in CI | format and adapters done (1.11); recorded in 1.16 |
| `nightly/` | Dated results of the scheduled 30-case live slice | M3 |
| `REPORT.md` | Published metrics and the unfixed failures | M3 |

## Golden set v1

`golden/unauthorised-v1.json` holds 30 unauthorised-transaction disputes, 6 for each of the five scenarios:
6 approve provisional credit, 6 escalate (above the credit limit), 18 decline (chip and PIN, 3-D Secure, out of
time). Each case carries the expected decision, the evidence ids a correct decision relies on, the allowed and
forbidden actions, and the policy section that decides it (`deciding_section`).

Selection is deterministic and independent of file order: within each scenario, cases are ranked by SHA-256 of
`golden-v1:<dispute id>` and the first six are kept. Growing `per_scenario` keeps every earlier pick, so M3 can
extend the set without changing the cases already recorded. The file records the dataset it came from (seed,
generator version, hashes of `ground_truth.csv` and `seed.sql`).

The golden set contains the answers. The agent never reads this directory; it reaches the data only through the
MCP servers, and the ground truth is never in `seed.sql`.

```sh
(cd ../data && uv run python generate.py)    # the tests need data/generated
uv run pytest                                # checks the committed set is exactly what the selector produces
uv run python -m evaltools.golden            # re-select after a generator change, then review the diff
```

A stale golden set fails CI. Re-selecting changes the cases the fixtures were recorded for, so it re-records the
fixtures in the same pull request (ADR 0001).
