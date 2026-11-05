# Synthetic data

- `generate.py` - seeded generator (cardholders, cards, merchants, transactions, authorisations, disputes with
  ground truth). Same seed, same bytes. Output goes to `data/generated/` (git-ignored) as CSV and a SQL seed.
- `policy-corpus/` - self-written plain-English dispute policy, one Markdown file per reason category plus
  general sections. Modelled on the public structure of card-network rules; no network text or codes.

Nothing here is derived from any employer.
