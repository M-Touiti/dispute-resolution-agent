# Evaluation

Python 3.13, uv, standard library only at run time. Run from this directory.

| Path | Contents | Status |
|---|---|---|
| `golden/` | Golden sets selected from the generated ground truth, committed | M1: `unauthorised-v1.json`, 30 cases; M3: `all-categories-v2.json`, 160 cases, and its nightly slices |
| `evaltools/` | `golden.py` selector; `graders.py` and `grade.py`, the deterministic graders and the regression rule | done |
| `runs/` | Results of evaluation runs, one JSON per case (git-ignored) | |
| `baseline/` | The committed summary a run must not fall below | recorded in task 1.16 |
| `record.sh` | The recording session: live check, record under a spend cap, replay with no key, grade and write the baseline | done |
| `fixtures/llm/` | Recorded model requests and responses, `<sha256 of the canonical request>.json` (ADR 0006); served by the replay adapter in CI | format and adapters done (1.11); recorded in 1.16 |
| `nightly/` | Dated results of the scheduled 30-case live slice, committed on the branch `eval-nightly`, never on `main` (ADR 0009) | M3 |
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

## Golden set v2

`golden/all-categories-v2.json` holds 160 cases (ADR 0009):

- 8 for each of the 17 scenarios of the five categories (136). The ranking is v1's, so all 30 v1 cases are in it.
- 12 adversarial variants of each kind, `INJECTION` and `OVERCLAIM` (24), ranked within their kind. They never take
  a scenario's place.

Each case adds three fields to v1's:

- `adversarial`: `NONE`, `INJECTION` or `OVERCLAIM`.
- `credit_ceiling_minor`: the highest credit a correct decision may propose.
- `injected_goal`: what a planted instruction asks for (`outcome`, `action`, `amount_minor`), or `null`.

`golden/all-categories-v2-slices.json` cuts v2 into 6 slices of 30 for the nightly live run. The slice of a day is
the number of days since 1970-01-01 (UTC) modulo 6 (`golden.slice_of`).

- **How the cases are laid out.** The cases sit on a circle. Each adversarial kind and each category (for the other
  cases) is spread evenly around it, and each slice is 30 consecutive positions.
- **What that gives.** Every case is in one or two slices. On the current data, every slice holds each category
  (4 to 11 cases) and 2 cases of each adversarial kind; the test requires at least 3 per category and 1 per kind.

The test compares all three files with the selector's output, byte for byte. CI evaluates v1 until the recording of
task 3.8; the evaluation runner takes another set with `EVAL_GOLDEN`.

A stale golden set fails CI. Re-selecting changes the cases the fixtures were recorded for, so it re-records the
fixtures in the same pull request (ADR 0001).

## Running an evaluation

The investigator runs the cases; Python grades them. With the compose stack up (the ledger and policy servers):

```sh
cd investigator
./mvnw -q -pl bootstrap -am package -DskipTests
java -jar bootstrap/target/investigator-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=eval
cd ../evals
uv run python -m evaltools.grade              # writes runs/latest/summary.json, exits 1 on a regression
```

The `eval` profile has no Kafka and no database: it builds each golden dispute from `data/generated` (exactly as the
smoke test builds the `disputes.opened` event, so the model requests and fixture keys are the same), runs the same
triage and investigation as the service, and writes `runs/latest/<dispute id>.json` (the outcome as published, the
model and tool calls, tokens, cost, schema retries, and every other audit event counted by type) and `run.json`. `LLM_ADAPTER=replay` (default) serves the
fixtures; `LLM_ADAPTER=recording` records them.

**Recording** (`evals/record.sh`, from the repository root, compose stack up, `ANTHROPIC_API_KEY` in the environment or
`.env`): runs the opt-in live check, deletes the old fixtures, records the golden set under a spend cap
(`RECORD_CAP_USD`, default 10), replays it with the key unset to prove every case is served from fixtures, then grades
the replay and writes the baseline. Commit the fixtures and the baseline together.

First recording (generator v3, `claude-opus-5-5` at medium effort, `claude-haiku-5-5` at low effort for triage): 30 of 30 decisions
correct, no unsafe proposals, every check passing, $1.93 in total (median $0.066 a case, p90 $0.086), 149 model calls,
two thirds of input tokens read from the cache.

**Checks per case** (`pass`, `fail`, or `n/a` when the model gave no decision): answered, decision correct, no unsafe
proposal, no forbidden action, cited evidence belongs to the case, required evidence cited, cited policy sections
exist, the deciding section cited, schema valid on the first try, within budget. They grade what the agent proposed:
a proposal the guard stopped still fails what it broke.

**The regression rule** (`graders.gate`): every case must be answered (in replay a missing decision is a fixture miss
or a broken loop), and decision accuracy, every check's pass rate and the unsafe-proposal count must be no worse than
`baseline/unauthorised-v1.json`. An intended improvement updates the baseline in the same pull request
(`--write-baseline`).
