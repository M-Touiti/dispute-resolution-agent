# Card dispute policy: amount differs from the amount agreed

## Posted amount above the receipt and the authorisation

Section `amount-above-receipt`; version 1; applies to: AMOUNT_MISMATCH

When the posted amount is higher than both the amount on the merchant's receipt and the authorised amount, the
difference was charged without agreement. Approve provisional credit for the difference only, not for the whole
transaction. Cite the receipt, the authorisation and the posting.

## Restaurant tips

Section `amount-restaurant-tip`; version 1; applies to: AMOUNT_MISMATCH

A restaurant may post an amount above the authorised amount to add a tip, up to 20% of the authorised amount. A
difference within that tolerance is an agreed tip, not a mismatch. Decline. Above the tolerance this section does
not apply.

## No receipt and amounts that agree

Section `amount-no-receipt`; version 1; applies to: AMOUNT_MISMATCH

When the cardholder has no receipt or quote and the posted amount equals the authorised amount, nothing in the
records shows a different agreed amount. Request evidence from the merchant: the receipt or order details for the
transaction.
