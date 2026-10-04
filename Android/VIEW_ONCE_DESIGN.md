# Phase 1P — View Once text and photos

View Once is a **device-local presentation policy**, carried only in the authenticated Signal message. It does not claim deletion from the server, sender device, a modified recipient, a rooted OS, or a separate physical camera. It sends no viewed/opened/consumed receipt. Existing mailbox ACK and Delivered status still mean transport delivery and durable recipient processing only.

## Wire format and legacy behavior

Version 1 `ConversationPayload` uses type 5 for View Once text and type 6 for View Once IMAGE descriptors. Type, effective disappearing duration, body/descriptor and optional profile update are all inside the authenticated E2EE payload. Type 6 validates the descriptor kind and duration. The existing legacy decoder rejects types outside 1–4, so a client without View Once support fails closed rather than displaying persistent plaintext. No backend blob flag, endpoint, metadata or migration is added. Malformed, conflicting or oversized payloads are rejected before acceptance/ACK.

## Local state and reveal boundary

Incoming messages start at `AVAILABLE`. Only an explicit Tap to view can transactionally change `AVAILABLE → REVEALING` in encrypted local storage. The transaction checks accepted relationship, block state, incoming direction, unexpired deadline and, for photos, a present descriptor. It commits before returning the text to the UI or starting photo decryption. A failed commit presents nothing. Close, leaving the conversation, Activity stop, app lock and photo cancellation change `REVEALING → CONSUMED`; text body and photo descriptor are erased from the message store, notification/unread presentation is cleared, and unreferenced cached ciphertext is reconciled away. Any `REVEALING` state found when the service is reconstructed is consumed before normal UI access. `CONSUMED` cannot transition back. If cleanup is interrupted, durable `REVEALING` remains fail-closed and startup finishes it.

The normal message list, chat preview and request UI receive placeholders only. They never receive View Once body or attachment summary. The text reveal uses a non-selectable, secure full-screen dialog with no copy/share/forward command. The photo uses the existing authenticated decrypt/verify and internal secure viewer; the ordinary inline thumbnail path is bypassed. The viewer offers no gallery export or external handoff. `FLAG_SECURE` and a secure dialog flag remain in effect where Android enforces them.

The photo transfer still may download ciphertext only after Tap to view in this implementation. The opening state is committed before the existing download/decrypt path begins; a failed download therefore consumes the one opportunity. This favors privacy over availability. No automatic background body download or inline decode occurs for View Once photos.

## Sender and outbox

The composer and photo preview default to View Once off for each new item. Pending offline text remains in the encrypted local message store until the durable outbox reaches server acceptance, so retry is possible. Normal conversation rendering masks it from the moment of send. On server acceptance the sender's stored text body is cleared; for a photo, the message's descriptor is removed and its upload cache is reconciled after outbox completion. The sender retains a placeholder and ordinary Queued/Delivered status. It cannot reopen the content from normal history.

## Other policies

- A request hides all content. Accepting a surviving request does not reveal or consume it. The existing 72-hour request expiry removes unaccepted content; Accept cannot revive it.
- Disappearing expiry and View Once consumption are independent terminal conditions. Whichever happens first removes presentation capability. The sender's disappearing countdown still begins only after delivery ACK.
- Blocked envelopes retain the existing authenticate/ratchet/replay/ACK path and discard content without creating an `AVAILABLE` message. Unblock does not restore it.
- Remove contact hides retained available items behind request access; it does not reset consumed state. Re-add, replay and restart cannot restore a consumed item. Delete conversation removes local message/descriptor state while preserving existing replay evidence.
- Generic notifications remain unchanged and never disclose the View Once policy.

## Limits and tests

This is once-only access in the unmodified Ghost Cloak UI on one device, not account-wide enforcement across hypothetical future devices. Android can limit screenshots and screen recording, but cannot prevent an external camera or a compromised recipient OS. Encrypted server blobs follow normal retention and may still exist after local consumption; neither server nor sender receives view state. Tests cover payload types/malformed headers, offline sender retry/cleanup, request/Accept, durable reveal/restart, replay, expiry, block/remove, photo descriptor gating and UI placeholder/close behavior. Physical two-phone validation is manual; automated instrumentation uses only disposable AVDs.
