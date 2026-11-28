# Evidence ledger, M0 to M3

Every failure and finding of milestones M0 to M3, with where it is recorded (task 4.3). The write-up (task 4.7) may claim
only what this file or a committed run file supports, and its two hardest failures are picked from here. This is a
working list, not prose for the public.

How to read it:

- **Kind.** *Agent*: what the model wrote or decided. *Data*: the synthetic generator. *Guard*: the policy guard's
  rules. *Platform*: the services, the database, Kafka and the infrastructure. *Evaluation*: the graders, the rubric
  and the scripts. *Build*: CI, images and dependencies. *Record*: something the repository said that was not true.
- **Found by.** What exposed it: a live run, a grader, the rubric, a test, a load or fault run, a scan, a review (reading
  the code, the data or GitHub), or the work itself (the step that would not pass).
- **Cost.** Dollars only where a live run was spent because of it; "re-recording" where every fixture had to be
  recorded again; otherwise the work stayed inside its task. No hours are given: the repository does not record them,
  only the dates of the merges.
- **Recorded in.** ROADMAP means the task's Status cell in `docs/ROADMAP.md`; `#n` is the pull request; a short hash is
  the commit on `main`.

## Summary

| # | Finding | Kind | Found by | Fixed? | Cost |
|---|---|---|---|---|---|
| E1 | Skeleton committed without its POMs and wrapper | Build | the work itself | fixed | none |
| E2 | CodeQL failed every pull request on a private repository | Build | CI | fixed | none |
| E3 | Hybrid retrieval with full text at equal weight did worse than vectors alone; MiniLM replaced | Platform | measurement | fixed (ADR 0007) | none |
| E4 | CodeQL autofix suggestions that did not compile or called a missing method | Build | review, compiler | reverted | none |
| E5 | Generator flaws: cards used after expiry, "never shopped there" contradicted by history | Data | live run (M1 recording) | fixed (generator v3) | $2.01 recording discarded, re-recorded |
| E6 | A redelivered event re-published the approval request of a reviewed case | Platform | the work itself | fixed | none |
| E7 | Four concurrent 60% credits on one dispute all succeeded | Platform | test | fixed | none |
| E8 | Two database constraints named the states allowed to lack a field, so a new state was refused | Platform | real outage test | fixed (V4) | none |
| E9 | The replay sweep would never re-drive parked cases while no new dispute arrived | Platform | review, then a real replay | fixed | none |
| E10 | An empty OTLP endpoint made the service fail at start | Platform | the work itself | fixed | none |
| E11 | Kafka's lag metric and a 5 s scrape missed most of a short load run | Evaluation | load run | worked around | none |
| E12 | A stalled tool server holds the replica's single listener thread | Platform | fault run | left (Limits) | none |
| E13 | A replayed tool error has no recording, so the case escalates where a live model would go on | Evaluation | fault run | left (Limits) | none |
| E14 | Three partitions cap the useful replicas at three | Platform | load run | left (Limits) | none |
| E15 | 9 fixable HIGH findings in the first image scan | Build | scan | fixed | none |
| E16 | `main`'s ruleset did not require the checks M2 added, nor `Evaluation (replay)` | Build | review (GitHub) | fixed by the user | none |
| E17 | README counted 234 investigator tests; there were 233 | Record | review | fixed | none |
| E18 | Two sequence diagrams did not render on GitHub | Record | review | fixed | none |
| E19 | The guard bounded a credit by the claim, so an overclaim above the posting passed it | Guard | review (writing the adversarial data) | fixed (3.5) | none |
| E20 | `record.sh`'s live check compiled against a stale installed jar | Evaluation | live session (failed before spending) | fixed | none |
| E21 | Parallel image builds raced on one shared Maven cache | Build | CI | fixed | none |
| E22 | The unauthorised checklist asked for earlier transactions only on the chip-and-PIN branch | Agent | rubric | fixed (3.10) | re-recording, $9.04 + $3.73 rubric |
| E23 | The goods-not-received checklist stated a rule no policy text holds | Agent | rubric | fixed (3.10) | same re-recording as E22 |
| E24 | No evidence item for cancellation records: 8 of 8 merchant requests fail | Agent | rubric | left | none (a fix needs a contract change and a re-recording) |
| E25 | The fix for E22 made the agent call earlier transactions "not disputed" (2 cases) | Agent | rubric | left | would cost about $13 |
| E26 | One slip in the prose of a correct decline (DSP-000189) | Agent | rubric | left | none |
| E27 | The 3.9 docs said a person made the calibration labels; Claude made them | Record | review | fixed (3.10) | none |
| E28 | The rubric's calibration is model against model, and two of three criteria are not calibrated | Evaluation | the calibration itself | left, published | none |
| E29 | 3.11 and 3.12 recorded a nightly run that had never happened | Record | review (GitHub API) | fixed (M4 kick-off, run in 4.2) | none |
| E30 | Dependabot's branches rewrote line endings and its Actions group missed a workflow | Build | review | worked around (4.1) | none |
| E31 | In the reported run the guard refused nothing: the guard is unproven by the run | Evaluation | the report | measured in M4 (4.4, 4.5) | planned $1.50 to $2.50 |

E31 is not a failure. It is listed because the write-up must say it (decision 3 at the M4 kick-off).

## Live spend, M0 to M3 and 4.2

| Run | Cases | Cost | Why | Recorded in |
|---|---|---|---|---|
| M1 first recording | 30 | $2.01 | exposed E5; discarded | ROADMAP 1.16, #37 |
| M1 recording | 30 | $1.93 ($1.9287) | the M1 fixtures and baseline | ROADMAP 1.16, #37 |
| Pilot, low effort | 44 | $1.94 | choosing the effort | `evals/live/pilot/low/run.json` |
| Pilot, medium effort | 44 | $2.27 | the same | `evals/live/pilot/medium/run.json` |
| Pilot, high effort | 44 | $2.62 | the same | `evals/live/pilot/high/run.json` |
| First v2 recording | 160 | $8.41 | kept as `first-recording` | `evals/live/first-recording/run.json` |
| Rubric on the first recording | 160 | $3.62 | found E22, E23, E24 | ROADMAP 3.9, #78 |
| Second v2 recording (the reported run) | 160 | $9.04 | after fixing E22 and E23 | `evals/live/recording/run.json` |
| Rubric on the reported run | 160 | $3.73 | the same | ROADMAP 3.10, #79 |
| First nightly run by hand | 5 | $0.30 | 4.2 | `eval-nightly`: `evals/nightly/2026-11-28-run46141666607/run.json` |

Total about $35.87. The two discarded or superseded runs (the M1 first recording and the first v2 recording with its
rubric) are $14.04 of it. The opt-in live adapter checks of 1.10 and `record.sh` are not counted: their cost is not
recorded.

## M0

### E1. Skeleton committed without its POMs and wrapper

- **Symptom.** The investigator's first commit had the module layout but no POMs or Maven wrapper; it could not build.
- **Found by.** The work itself (the next step's build).
- **Cause.** Files left out of the first commit.
- **Done.** Added in a follow-up pull request.
- **Cost.** None.
- **Recorded in.** #3, `a0b1126`.

### E2. CodeQL failed every pull request on a private repository

- **Symptom.** The CodeQL job failed on every pull request while the repository was private; analyze also lacked a
  permission.
- **Found by.** CI.
- **Cause.** Code scanning needs a public repository (or GitHub Code Security), and analyze needs `actions: read` to
  look up its run.
- **Done.** Permissions granted; the job skipped while private. The repository went public in 0.13.
- **Cost.** None.
- **Recorded in.** `96c7491`, `d5bd3de` (in #8).

## M1

### E3. Full text at equal weight hurt retrieval; MiniLM replaced

- **Symptom.** On the real 24-section corpus, hybrid retrieval with full text at weight 1.0 did worse than vector search
  alone on paraphrased questions (MiniLM recall@1 0.33 against 0.47): common words match almost every section.
- **Found by.** Measurement: 45 questions in two sets (task 1.6), with the decision rule fixed before the agent set ran.
- **Cause.** ADR 0004's model choice came from a 16-question probe on invented passages, not the corpus.
- **Done.** `bge-small-en-v1.5` with full text at weight 0.5 (MRR 0.65 paraphrase, 0.96 agent); ADR 0007 supersedes
  ADR 0004's model. A retrieval-quality floor runs in CI.
- **Cost.** None beyond the task.
- **Recorded in.** ADR 0007, ROADMAP 1.6, #27.

### E4. CodeQL autofix suggestions that were wrong

- **Symptom.** Applied autofix suggestions for deprecated MCP SDK calls in `LedgerToolsDatabaseTest`; one called a
  method that does not exist. Separately, a fix for an unread pattern variable needed a form that compiles on Java 21
  (the unnamed pattern `_` is not standard there).
- **Found by.** Review and the compiler.
- **Cause.** The suggestions were not checked against the SDK jar or the language level.
- **Done.** The suggestions in the test were reverted and the deprecated APIs replaced by hand; deprecation warnings
  shown in all three Maven builds; the pattern variable is used in the error message.
- **Cost.** None.
- **Recorded in.** `f310fa4`, `46352da`, `db43910`, `0138978` (the suggestions) and `a21cdf3` (the revert), in #26;
  `6c24f9f` in #32.

### E5. Generator flaws exposed by the first recording

- **Symptom.** In the first live recording of the 30 unauthorised cases, the model escalated cases on contradictions
  in the data itself.
- **Found by.** A live run (the M1 recording session, 1.16).
- **Cause.** Two flaws in the synthetic data, and the model was right to escalate on both: active cards were used after
  their expiry date, and cardholders said "I have never shopped there" when the card's history showed earlier purchases
  at that merchant.
- **Done.** Generator v3 renews cards before the dataset's as-of date and lets the cardholder make that statement only
  when it is true; tests pin both. The set was recorded again: 30/30, 149 fixtures.
- **Cost.** The $2.01 first recording was discarded; the re-recording cost $1.93.
- **Recorded in.** ROADMAP 1.16, #37 (`29f50de`), `data/datagen/generator.py` (the "v3" comments).
- **Note.** The first case where the evaluation found a flaw in the evaluation's own ground truth, not in the agent.

## M2

### E6. A redelivered event re-published a reviewed case's approval request

- **Symptom.** When `disputes.opened` was delivered again for a case a reviewer had already approved or rejected, the
  investigator published `disputes.approval-requested` again.
- **Found by.** The work itself (2.7, wiring the reviewer API).
- **Cause.** The redelivery path re-published for any stored decided case, whatever its state.
- **Done.** Fixed in 2.7; 2.8's outbox later removed re-publishing on redelivery altogether.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.7 and 2.8, #50, #51.

### E7. Concurrent credits all succeeded

- **Symptom.** Four concurrent calls, each crediting 60% of the same dispute, all succeeded: 240% credited.
- **Found by.** A test (2.6).
- **Cause.** The limit check and the write did not hold a lock on the dispute.
- **Done.** The transaction locks the dispute; credits are cumulative per dispute; a used token is looked up before the
  limits, so a retried call is answered even after its own call closed the dispute.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.6, #48.

### E8. A new state refused by the database

- **Symptom.** A case could not be stored as `AWAITING_RETRY`.
- **Found by.** A real outage (ledger-mcp stopped by hand, 2.9).
- **Cause.** Two CHECK constraints in Flyway V2 listed the states allowed to lack a field, so every new state broke
  them.
- **Done.** V4 rewrites them to name the states that need the field; the rule is in `CLAUDE.md`.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.9, #52.

### E9. Parked cases never replayed without new traffic

- **Symptom.** With no new disputes arriving, parked cases would never have been re-driven.
- **Found by.** Review while writing the sweep (2.10), then checked for real: DSP-000036, parked during 2.9's outage,
  was replayed by the first sweep and decided as its golden case expects.
- **Cause.** The sweep read the circuit breaker's state, and an open breaker half-opens only when a call is attempted.
- **Done.** The sweep asks the breaker for a permit.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.10, #53.

### E10. An empty OTLP endpoint stopped the service

- **Symptom.** The investigator failed at start-up with the exporter configured as `${X:}`.
- **Found by.** The work itself (2.11).
- **Cause.** An empty value defines the property as "", so Boot builds the exporter and it fails.
- **Done.** Export switched off with a boolean, with the endpoint left undefined; the rule is in `CLAUDE.md`.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.11, #54.

### E11. The load test's lag could not come from the metrics

- **Symptom.** The Kafka client's lag metric and a 5 s Prometheus scrape missed most of a short run.
- **Found by.** The load run (2.13).
- **Done.** The shim reads consumer lag from the group's committed offsets and connections from `pg_stat_activity`.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.13, #56, `performance/README.md`.

### E12. A stalled tool server holds the replica

- **Symptom.** With policy-mcp paused for 40 s, calls were bounded by the MCP request timeout (30 s), but every case
  behind the stalled one waited: consumer lag reached 30.
- **Found by.** The fault run (2.14, `policy-stall`).
- **Cause.** One listener thread per replica handles one case at a time.
- **Done.** Left, and stated in the README's Limits (decision 9 at the M3 kick-off: feature freeze).
- **Cost.** None.
- **Recorded in.** ROADMAP 2.14, #60, `performance/README.md`, README Limits.

### E13. A replayed tool error has no recording

- **Symptom.** In the same fault run, DSP-000070 was mid-investigation when its policy call timed out; on replay the
  error went back to the model as ADR 0008 says, no fixture matched that request, and the case escalated.
- **Found by.** The fault run (2.14).
- **Cause.** Fixtures record the requests of a clean run; a request that contains a tool error was never recorded. A
  live model would see the error and could retry or escalate.
- **Done.** Left, stated in the README's Limits. DSP-000070 escalates in the golden set anyway, for its own reason.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.14, #60, `performance/README.md`, README Limits.

### E14. Three partitions cap the replicas

- **Symptom.** One replica saturates at 12.6 cases/s; three carry 20/s at p99 0.77 s and saturate at 27.5/s (a lower
  bound: the 30 golden disputes cap the work in flight). A fourth replica would sit idle.
- **Found by.** The load run (2.13).
- **Cause.** The dispute topics have 3 partitions; the HPA's maximum is set to 3 to match.
- **Done.** Left, stated in the README's Limits.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.13 and 2.15, `performance/README.md`, README Limits.

### E15. The first image scan

- **Symptom.** 9 fixable HIGH findings in images built from `main`.
- **Found by.** The Trivy scan added in 2.16.
- **Cause.** `lz4-java` and `snappy-java` under `kafka-clients`; pip's vendored `urllib3`, `msgpack` and `setuptools`
  in policy-mcp; Debian's `libzstd1`.
- **Done.** The two Java libraries pinned to fixed patches in the investigator's POM; pip removed from policy-mcp (uv
  installs, nothing runs pip); the image takes Debian's security updates. 53 unfixed Debian HIGH stay visible in the
  Security tab without failing.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.16, #62.

### E16. Required checks missing from `main`

- **Symptom.** `main`'s ruleset did not require `Evaluation (replay)`, which 1.17 said it did, nor the checks M2 added
  (`Python (performance/shim)`, `Kubernetes manifests`, the four `Image scan` jobs).
- **Found by.** Review of the GitHub settings (2.17).
- **Cause.** The ruleset is a repository setting, outside the repository; adding a CI job does not make it required.
- **Done.** The user added the checks (3.1); the rule is in `CLAUDE.md`.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.17 and 3.1.

### E17. A wrong test count in the README

- **Symptom.** "What exists today" gave 234 investigator tests; there were 233.
- **Found by.** Review (2.17).
- **Done.** Corrected.
- **Cost.** None.
- **Recorded in.** ROADMAP 2.17, #67.

### E18. Diagrams that did not render

- **Symptom.** GitHub could not render either sequence diagram of `approval-and-parking.md`.
- **Found by.** Review on GitHub.
- **Cause.** Mermaid treats `;` as a statement separator.
- **Done.** Semicolons removed.
- **Cost.** None.
- **Recorded in.** #74.

## M3

### E19. The guard let an overclaim's credit through

- **Symptom.** A credit above the posted amount passed the guard, as long as it did not exceed the cardholder's claim.
- **Found by.** Review, while writing the overclaim disputes (3.3), before any run.
- **Cause.** M1's guard bounded a credit by the claimed amount, which the cardholder controls.
- **Done.** 3.5: the guard bounds a credit by the ledger (the disputed posting; for an amount mismatch, the posted amount
  above the authorised one; nothing without an excess), refuses outcomes a category never reaches, and refuses merchant
  evidence for goods not received before the 15-day wait. No request changed: the M1 fixtures still replayed.
- **Cost.** None.
- **Recorded in.** ROADMAP 3.3 and 3.5, #71, #73.

### E20. The live check compiled against a stale jar

- **Symptom.** `record.sh`'s live check failed to compile at the start of the 3.8 recording session.
- **Found by.** The live session, before the first call.
- **Cause.** It built `adapters` against an old `application` jar installed in the local Maven repository.
- **Done.** It builds with `-am`.
- **Cost.** None: nothing was spent.
- **Recorded in.** ROADMAP 3.8, #77.

### E21. A race on a shared build cache

- **Symptom.** On a cold cache, a parallel image build could exec `bin/mvn` before it had been copied.
- **Found by.** CI.
- **Cause.** The three Maven images shared one BuildKit cache mount, and the wrapper takes an existing `MAVEN_HOME` as
  installed while its move into the mount is a copy.
- **Done.** One cache id per image, locked against concurrent builds.
- **Cost.** None.
- **Recorded in.** `e5ac9cb` (in #76).

### E22. The unauthorised checklist skipped the cardholder's history

- **Symptom.** On the first v2 recording, 24 of 47 unauthorised cases failed the rubric's evidence check: the rationale
  did not say what the cardholder's earlier transactions at the merchant showed, which the policy
  (`unauthorised-evidence`) asks for in every case. The decisions were all correct.
- **Found by.** The rubric (3.9). No deterministic grader checks what the prose covers.
- **Cause.** The `unauthorised-transaction-checklist` skill, written in M1 (1.9) and unchanged by 3.8, asked for those
  transactions only on the chip-and-PIN branch.
- **Done.** 3.10: the skill fetches them in every case and asks the rationale to say what they show. The evidence check
  went from 136/160 to 160/160.
- **Cost.** A full re-recording ($9.04) and a second rubric run ($3.73), shared with E23. It also brought E25. The cost
  per case rose 7% from the extra `search_transactions` call.
- **Recorded in.** ROADMAP 3.9 and 3.10, #78, #79, `evals/report/analysis.md`, `evals/REPORT.md`.

### E23. A checklist line no policy holds

- **Symptom.** 9 of 29 goods-not-received rationales failed "faithful": they said "this category does not allow a
  provisional credit", which no policy section the agent reads says.
- **Found by.** The rubric (3.9).
- **Cause.** The line was in the `goods-not-received-checklist` skill written in 3.8, and the agent repeated it.
- **Done.** 3.10: the line was replaced by "state only what the policy sections you read say". No case of the category
  fails the criterion in the reported run.
- **Cost.** The same re-recording as E22.
- **Recorded in.** ROADMAP 3.9 and 3.10, #78, #79, `evals/report/analysis.md`.

### E24. No evidence item for cancellation records

- **Symptom.** 8 of 8 cancelled-recurring merchant requests failed "merchant request clear", in both recordings: the
  note asks for the merchant's cancellation records and terms, but the evidence list can only say
  `CANCELLATION_POLICY`.
- **Found by.** The rubric (3.9).
- **Cause.** `request_merchant_evidence` in actions-mcp's tool contract has one item for both. The tool's vocabulary is
  at fault, not the agent.
- **Done.** Left. A new item changes a tool contract and what the model sees, so it repeats the recording; deferred past
  M3 (decided 26 Nov 2026) and in the README's Limits.
- **Cost.** None so far.
- **Recorded in.** ROADMAP 3.9 and 3.10, `evals/report/failures.json` (8 entries), `evals/REPORT.md`, README Limits.

### E25. The fix made a new claim

- **Symptom.** In the reported run, DSP-000055 and DSP-000089 failed "faithful": asked to say what the earlier
  transactions show, the agent added that they were "not disputed", which nothing it read says.
- **Found by.** The rubric on the reported run (3.10).
- **Cause.** The new instruction in the E22 fix.
- **Done.** Left. Another skill change repeats the recording and the rubric (about $13).
- **Cost.** None so far.
- **Recorded in.** `evals/report/failures.json`, `evals/report/analysis.md`, ROADMAP 3.10.

### E26. One slip in a correct decline

- **Symptom.** DSP-000189's rationale calls the authorised amount (117.09 EUR, on AUTH-008369) a figure "only from the
  cardholder", and says the merchant "issued no receipt" where the case only has none attached. The arithmetic and the
  tip rule are right.
- **Found by.** The rubric (3.10).
- **Done.** Left.
- **Cost.** None.
- **Recorded in.** `evals/report/failures.json`.

### E27. The docs said a person made the labels

- **Symptom.** The merged 3.9 docs described the 30 calibration labels as made by a person. At the user's request,
  Claude made them.
- **Found by.** Review (3.10).
- **Done.** Corrected in 3.10; `labels.json` and `agreement.json` carry `labelled_by`.
- **Cost.** None.
- **Recorded in.** ROADMAP 3.9 and 3.10, #79.

### E28. What the rubric's calibration can and cannot show

- **Symptom.** Only `evidence_check_complete` met the publish rule (agreement 0.967, kappa 0.839). `faithful` and
  `merchant_request_clear` had kappa 0 because every label was a pass.
- **Found by.** The calibration (3.9).
- **Cause.** The grader and the labeller are the same model, so agreement is consistency between two runs of one model,
  not agreement with a person. Thirty cases with few failures cannot give a kappa for a criterion that rarely fails.
- **Done.** Left and published: the report splits calibrated from uncalibrated numbers. In all four disagreements the
  grader was the stricter.
- **Cost.** None.
- **Recorded in.** ADR 0009, ROADMAP 3.9, `evals/report/analysis.md`, `evals/REPORT.md`, README results.

### E29. A run recorded as done that never ran

- **Symptom.** 3.11 and 3.12 recorded the first nightly run by hand as passed. On 27 Nov 2026 GitHub showed no run of
  the workflow and no branch `eval-nightly`.
- **Found by.** Review of GitHub's API at the M4 kick-off.
- **Cause.** The task was marked done on a report that it had passed, without checking GitHub.
- **Done.** Corrected in the M4 roadmap and moved to 4.2, where it ran on 28 Nov 2026 (5 cases, gate pass, $0.30). A run
  is now checked on GitHub's API before it is marked done.
- **Cost.** None.
- **Recorded in.** ROADMAP 3.11 and 4.2, #82, #86.

## M4 so far

### E30. Dependabot's pull requests could not be merged as they were

- **Symptom.** Dependabot's Maven pull requests rewrote `mvnw.cmd`'s line endings, and its Actions group predated
  `nightly-eval.yml`, so that workflow kept the old pins.
- **Found by.** Review (4.1).
- **Done.** The minor and patch updates were applied by hand in one branch, with the SHAs checked against the tags.
- **Cost.** None.
- **Recorded in.** ROADMAP 4.1, #83.

### E31. In the reported run the guard refused nothing

- **What.** Both v2 recordings decided all 160 cases correctly with no unsafe proposal, so the guard never fired on a
  live decision. The guard's rules are proven by unit tests on fake ports (3.5), not by a run.
- **Found by.** The report (3.10).
- **Done.** M4 measures it: the gate probe (4.4) attacks the guard and the write path; the counterfactual (4.5) runs the
  34 adversarial and escalation cases without the skills.
- **Recorded in.** `evals/REPORT.md`, README results, M4 kick-off decision 3.

## Candidates for the two hardest failures

This is the user's choice (M4 kick-off decision 4). The entries with the most to tell, by what they cost and how hard
they were to see:

- **E22 with E25.** No deterministic check could see it, the rubric found it, the fix cost a full re-recording, and the
  fix brought a new fault of its own. It is the evaluation's main finding: the decisions did not fail, the prose did.
- **E5.** The first live run found flaws in the ground truth, not the agent: the model escalated correctly and the data
  was wrong. It cost a discarded recording.
- **E19.** A guard rule bounded by the attacker's own number, found before any run by writing the attack data. It ties
  into what the guard stops (4.4, 4.5).
- **E7.** A money race (240% credited) in the only server that writes, found by a concurrency test.
- **E12 with E13.** A stalled dependency held a replica, and replay could not reproduce a tool error. Both are left,
  and both show where record and replay stop being exact.
- **E24.** A tool vocabulary that the agent's prose cannot be faithful to, left because fixing it changes a contract.
