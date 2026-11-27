# The evaluation (M3)

How the numbers in [`evals/REPORT.md`](../../evals/REPORT.md) are made, and how the same golden set gates every pull
request without calling the API (ADR 0006, ADR 0009). The ground truth comes from the data generator's rules and is
never shown to the agent or to the rubric's grader.

```mermaid
flowchart TB
    GEN[data generator<br/>seeded · ground_truth.csv] --> SEL[evaltools.golden<br/>160 cases · 6 nightly slices]
    SEL --> GS[(golden set v2)]

    subgraph live [live, each run with its own go-ahead]
        REC[record.sh<br/>recording adapter] --> FIX[(fixtures<br/>727 model calls)]
        REC --> LR[(evals/live/recording<br/>run + thresholds)]
        NIGHT[nightly-eval.yml<br/>anthropic adapter · $5 cap] --> NB[(branch eval-nightly)]
    end

    subgraph ci [every pull request, no key]
        REP[eval runner<br/>replay adapter] --> GR[evaltools.grade<br/>12 deterministic checks]
        GR --> BASE{baseline<br/>per category and kind}
    end

    GS --> REC
    GS --> REP
    GS --> NIGHT
    FIX --> REP
    LR --> TH{live thresholds}
    NIGHT --> TH
    LR --> RUB[evaltools.rubric<br/>claude-opus-5-5 grades the prose]
    RUB --> CAL[calibration<br/>30 labelled cases · agreement, kappa]
    LR --> RPT[evaltools.report]
    RUB --> RPT
    CAL --> RPT
    RPT --> MD[evals/REPORT.md<br/>every failure explained]
```

- **Replay** (CI): a request whose hash has no fixture fails the run; it never falls through to the live API. A
  change to anything the model sees misses every fixture and is re-recorded in the same pull request.
- **Grading**: decisions, citations, actions, amounts and injections are checked deterministically; only the
  rationale and the merchant request go to the model-graded rubric, published per criterion only where its agreement
  with the calibration labels reaches 0.8 and kappa 0.6.
- **Live runs** are not byte-stable, so they are graded on per-case thresholds written from the full recording, not
  on the replay baseline. Nightly results go to `eval-nightly`, never to `main`.
