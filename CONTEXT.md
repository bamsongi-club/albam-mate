# AI Usage and Cost Context

This context defines the user, usage, and cost terms used by the P2 AI assistant so request limits are not confused with provider charges.

## Identity and usage

**Authenticated assistant user**:
The application user whose identity has been established by the server for an assistant request. Per-user call limits belong to this user, not to an identifier supplied by the client.
_Avoid_: session as the user, client user ID

**AI request quota**:
A limit on the number of external AI provider calls an authenticated assistant user may make in a period. It is separate from a monetary cost limit.
_Avoid_: AI cost quota

## Cost

**App estimated-cost ledger**:
The application's price-based estimate of provider usage, including reserved cost for calls whose final usage is not yet known. It is an application guardrail, not a provider invoice.
_Avoid_: OpenAI bill, actual charge

**Provider project spend limit**:
A provider-enforced spending threshold shared by usage in one provider project. It is not a per-user allowance and is distinct from the application's estimated-cost ledger.
_Avoid_: user budget, app cost ledger

**Usage-uncertain request**:
An external provider request whose processing or token usage cannot yet be confirmed. Its estimated-cost reservation continues to count against the app's KST-month limit through month-end; concurrency capacity has a separate expiry.
_Avoid_: free retry, zero-cost failure
