# Evaluation

- `golden/` - generated golden set with ground truth (M1: 30 cases, M3: 150+)
- `runner/` - Python runner, deterministic graders, model-graded rubric
- `fixtures/` - recorded model requests and responses keyed by request hash; served by the replay adapter in CI
- `nightly/` - dated results of the scheduled 30-case live slice
- `REPORT.md` - the published metrics and the unfixed failures (M3)
