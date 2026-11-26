**Decisions.** Both recordings decided all 160 cases correctly, with no unsafe proposal, no over-generous credit
and no obeyed injection; every deterministic check passed on every case. On this data the decision is not where the
agent fails. The 17 scenarios each have one rule that decides them, stated in the policy the agent reads, and the
skills walk the agent to it: read the 160/160 with the caveats above, as evidence that the loop, the tools, the
guard and the skills work together, not as an accuracy to expect on real disputes.

**The prose is where it failed, and the rubric found it.** The first recording's rubric run found three faults, each
traced to its cause:

- *Unauthorised transactions, evidence check (24 of 47 failed).* The rationale rarely said what the cardholder's
  earlier transactions at the merchant showed, which the policy (`unauthorised-evidence`) lists for every case. The
  cause was the `unauthorised-transaction-checklist` skill, which asked for them only on the chip-and-PIN branch.
  **Fixed** in the change that adds this report (task 3.10): the skill now fetches them in every case and asks the rationale to say what they show.
  The reported run fails no case on this criterion.
- *Goods not received, faithful (9 of 29 failed).* The rationale said "this category does not allow a provisional
  credit", a line from the `goods-not-received-checklist` skill that no policy section the agent reads contains.
  **Fixed** in the same change: the line is gone, replaced by "state only what the policy sections you read say". The
  reported run fails no case of the category on this criterion.
- *Cancelled recurring payments, merchant request (8 of 8 failed, in both runs).* The note asks for cancellation
  records and terms; the tool's evidence list has one item, `CANCELLATION_POLICY`, for both. **Left**: the fix is a
  new item in actions-mcp's tool contract, deferred past M3.

The fixes cost a second recording ($9.04) and a second rubric run ($3.73). They brought two new faithfulness
failures (DSP-000055, DSP-000089): told to say what the earlier transactions show, the agent added that they were
"not disputed", which nothing it read says. They are left, with one unrelated slip (DSP-000189); the table below
gives each.

**The rubric's own limits.** The calibration labels were made by Claude, the same model as the grader, at the user's
request, not by a person (ADR 0009 asks for a person). Only `evidence_check_complete` passed the publish rule (agreement
0.967, kappa 0.839), and on its only disagreement the grader was the stricter; `faithful` and
`merchant_request_clear` had kappa 0 because every label was a pass, so their numbers above are the grader's verdicts,
not calibrated metrics. In all four disagreements the grader read the rubric more strictly than the labels did; on review
three of its readings hold and the fourth (DSP-000106, whether a 3-D Secure decline must still mention earlier
purchases) is a fair reading of an ambiguous rule. The calibration was made on the first recording's decisions; the reported run's
are graded by the same grader and rubric.

**Cost and speed.** The reported run cost $9.04 for 160 cases, $0.055 a case at the median, 7% more than the first
recording, from the extra `search_transactions` call on unauthorised cases. Medium effort was kept after the pilot,
where low, medium and high decided the 44 cases alike: low was 15% cheaper and wrote rationales 15% shorter, the part
the rubric grades.
