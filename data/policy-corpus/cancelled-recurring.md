# Card dispute policy: recurring payments after cancellation

## Cancellation confirmed before the charge

Section `recurring-cancelled-before-charge`; version 1; applies to: CANCELLED_RECURRING

When the merchant confirmed in writing a cancellation that took effect before the disputed charge, the charge
should not have been made. Approve provisional credit for the charge, subject to the time limit and the
provisional credit limit. Cite the cancellation confirmation.

## Cancellation effective after the charge

Section `recurring-cancelled-after-charge`; version 1; applies to: CANCELLED_RECURRING

When the cancellation took effect after the disputed charge, the charge belongs to a period the cardholder had
not yet cancelled. Decline.

## No proof of cancellation

Section `recurring-no-proof`; version 1; applies to: CANCELLED_RECURRING

When the cardholder has no written confirmation of the cancellation and the records do not show one, request
evidence from the merchant: its cancellation records and terms for the subscription.
