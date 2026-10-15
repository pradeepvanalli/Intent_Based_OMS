# Operations questions, explanations, customer replies

### Ops Q&A

> Which orders at DC-TX are at risk of missing their promise today, and why?

Expected: a list from `GET /api/v1/control-tower/at-risk` filtered to `DC-TX`, each with risk score, reasons from the
`sla_breach_risk` trace and a link to the order; a one-line summary ("7 orders, 5 because the node backlog is 2,600
units against a 16:30 cutoff").

> How many returns did we refund instantly last week, and how many of those were later flagged?

Expected: counts from decision records (`return_risk` with value 0 / `instant` timing) and labels; a caveat when the
data window is shorter than asked.

### Explain a decision

> Explain decision record dec_01J... to the ops team.

Expected: a plain-language rendering of `GET /api/v1/decision-records/{id}/why`: inputs (tokenised PII shown as
tokens), rules fired, the question, the chosen option with its probability, the band and what action followed, who
overrode it if anyone.

> Explain the same decision to the customer.

Expected: the customer-safe variant (`?audience=customer`): no risk scores, no history counts, typed slots only
("Your refund will be issued once the carrier scans the package"), per GR-15.

### Customer-service reply drafts

> Draft a reply to the customer of order ord_01J... who asks where their parcel is.

Expected: a reply built from the order view (status, last carrier scan, promise date, tracking) with no internal
notes, no risk data, and placeholders for anything unknown; the copilot states which fields it used.

> Draft a reply declining a return that is outside the 30-day window but offer store credit as a goodwill gesture
> within my limit.

Expected: text that cites the window and the delivery date, offers store credit up to the actor's
`appeasement.role_limits` ceiling, and a note that issuing the credit is a separate human action through the
appeasement flow (the copilot does not move money).
