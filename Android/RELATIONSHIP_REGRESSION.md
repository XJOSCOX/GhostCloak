# Reject / expired request relationship correction

## Confirmed local causes

`ConversationService.importCard` treated every retained contact row as a duplicate, including rejected/expired request rows invisible in the address book. When no stored card existed it processed a prekey bundle before rejecting, unnecessarily modifying session state. Retained security records are not contact membership.

`LocalRepository.restartRequestIfEligible` required an envelope accepted more than one hour after the rejection watermark. A fresh envelope within that window was authenticated/committed/deduplicated then cleared under REJECTED. Normal ACK followed the successful commit: sender Delivered, recipient no request. Subsequent attempts refreshed the watermark. This was local presentation suppression, not failed backend delivery.

## Authoritative relationship model

`relationshipState` derives UNKNOWN, REQUEST_PENDING, ACCEPTED_CONTACT, DORMANT_UNACCEPTED and BLOCKED from existing encrypted records. `isActiveContact` means exactly ACCEPTED_CONTACT: an existing contact with request=false and blocked=false. Pending requests are visible separately. Phase 1O moved all blocked contacts to Settings → Blocked contacts and makes Unblock return to DORMANT_UNACCEPTED, including older accepted-and-blocked rows; they cannot send/download or bypass Block through Add.

Raw rows remain appropriate for identity binding, ambiguity checks, retained-row capacity limits, receipt/outbox handling and cleanup. Their existence is not a duplicate Add decision. No serialized schema change is needed.

## Retained and removed records

Reject/expiry retain app/contact, terminal app/request, app/accepted envelope hashes, Signal replay evidence, identity pins/trust/verification state, session/lifecycle/admission records, existing public/pending cards, capability evidence and network directory/routing state. Local account/device credentials and outbox semantics remain unchanged.

They remove app/message, app/attachment descriptors, read/unread markers and notification ledger entries for that request. Existing AppRuntime reference reconciliation removes unreferenced encrypted attachment caches; existing access invalidation protects previews/scratch. Delivery/outbox references retain material still required for delivery. Unaccepted attachments remain unavailable to download/render.

## Transitions

- Reject/72-hour expiry makes an unaccepted relationship dormant. A new authenticated, dedup-eligible envelope immediately establishes a new bounded request using the current privacy preference. Expiry cleanup occurs in the same decrypt/commit transaction before starting it. Old envelopes cannot restore content or extend the new window.
- Explicit Add of pending/dormant relationships checks the retained pinned identity through existing Signal trust checks without replacing the ratchet. Card/contact/request activation is atomic and reuses the same contact ID. Surviving pending content can be revealed by this explicit acceptance action; deleted/expired content stays deleted. Identity mismatch preserves CHANGED/pending-card approval and does not activate the contact.
- Block silently authenticates/discards new envelopes and ACKs after secure commit (see BLOCKED_ENVELOPE_PRIVACY.md). Add returns BLOCKED and cannot silently unblock; the separate explicit unblock action is unchanged.
- Accepted conversation delete/clear remains Phase 1H local deletion, not rejection or unsend.

No backend change/deployment, V007 or database migration. V006 is unchanged. No server connection is needed.

## User-run physical retest (upgrade in place)

1. Fresh peers: A sends text; B gets a generic request and Deletes. A sends new text immediately. B gets a new generic hidden request. Old replay/duplicates must not restore the first text.
2. Repeat initial request/Delete. B searches/adds A and initiates a message. No duplicate-contact error, extra row, regenerated identity or lost verification; deleted content stays absent.
3. Repeat with Block. A sends again; B sees nothing. B search/Add cannot silently unblock A.
4. Repeat test 1 with two different photos. First descriptor/cache disappears locally; second creates a generic hidden request. No preview/download before acceptance; after acceptance the second photo downloads normally.

Automated connected tests use only the owned disposable AVD, synthetic identities and local fixtures. Never clear/uninstall/run instrumentation on account-bearing physical phones.

## Validation

Passed 120 core JVM tests (including eight relationship regressions), four attachment-format tests, 33 debug and 26 release unit tests, debug/release APK builds and strict dependency verification including IDE sources. Full app/storage connected suites passed on GhostCloak_Phase1J1_Disposable (API 37, emulator-5566). No physical phone or VPS was used.

Validation used the workspace's existing uncommitted AGP/Gradle/verification-metadata updates; those and launcher artwork are outside this relationship-fix commit. No tooling or backend behavior was changed by this correction.
