# Card dispute policy: unauthorised transactions

## Evidence to check for an unauthorised transaction

Section `unauthorised-evidence`; version 1; applies to: UNAUTHORISED_TRANSACTION

The cardholder says they did not make or allow the transaction. Before deciding, check:

- the disputed transaction and its posting date (see the time limit);
- its authorisation: the entry mode (chip, contactless, magnetic stripe or card not present), the 3-D Secure
  result, the card verification value result, whether a PIN was verified, and the fraud flag;
- the cardholder's earlier transactions with the same merchant.

## Card-not-present transaction without strong authentication

Section `unauthorised-card-not-present`; version 1; applies to: UNAUTHORISED_TRANSACTION

A transaction counts as unauthorised when all of the following hold: the card was not present, the payment was
not authenticated with 3-D Secure, and the authorisation carries the fraud flag. Approve provisional credit for
the transaction amount, subject to the time limit and the provisional credit limit.

## Chip and PIN at the point of sale

Section `unauthorised-chip-and-pin`; version 1; applies to: UNAUTHORISED_TRANSACTION

A transaction made with the chip, with the PIN verified and without the fraud flag, is treated as made by the
cardholder: the card and the PIN were both present. Earlier purchases by the cardholder at the same merchant
support this. Decline.

## Payment authenticated with 3-D Secure

Section `unauthorised-3ds-authenticated`; version 1; applies to: UNAUTHORISED_TRANSACTION

An online payment that the cardholder authenticated with 3-D Secure, without the fraud flag, is treated as made by
the cardholder. A matching card verification value supports this. Decline.
