# Phase 1N: QR and text sharing of Ghost Cloak IDs

The connected profile shows its server-assigned public Ghost Cloak ID in `4-4-4` groups. Copy puts only that formatted ID on the Android clipboard and marks the clip sensitive. Share invokes the Android Sharesheet with only `Add me on Ghost Cloak:\n<grouped ID>`. These explicit actions work offline. A local/demo public contact card is a different mechanism and does not acquire a registered directory ID or QR.

## Payload and threat model

The QR is exactly `ghostcloak://contact/v1/<12-character canonical ID>`, for example `ghostcloak://contact/v1/7K4M9Q2FX8DR`. Version 1 carries no name, alias, phone, email, account/device/routing identifier, key, recovery or authentication material, or safety number. The ID is public pseudonymous lookup metadata, not proof of ownership. QR or text sharing does not verify a person or change their pinned cryptographic identity. The normal Contact Security screen and safety-number comparison remain the verification path.

This URI-looking text is internal QR content. Ghost Cloak registers no Android deep-link intent filter, does not open it as a URL, and does not handle shares arriving from other apps. Clickable links and any future in-person verified QR need separate security review. The parser uses an exact ASCII prefix and exact length rather than generic URI resolution, then calls the same `GhostCloakIds.normalize` function used by manual Add Contact and network lookup. Only uppercase canonical payload IDs are accepted. Wrong version or scheme, whitespace, Unicode lookalikes, userinfo, query, fragment, traversal, extra data, multiple IDs, and oversized payloads are rejected with one generic UI message. No ID or QR text is logged.

## Camera, contacts, and privacy

The Scan QR button requests Android CAMERA permission only when tapped; denial leaves manual entry usable. JourneyApps ZXing Android Embedded 4.3.0 provides the on-device camera scanner through `ScanContract`; ZXing Core 3.5.4 encodes and decodes QR locally. Both are Apache-2.0 licensed and have no network scanning SDK. Only QR symbology is enabled, beep/image-return are disabled, and camera frames and scan images are not persisted or uploaded. The separate capture Activity sets `FLAG_SECURE`; the main window already does so. A scan fills the existing Add Contact ID field and needs the user's Find and add contact action. Cancellation leaves the draft unchanged. If offline, normal lookup errors apply and the draft remains on screen.

The local ID comparison rejects a self-scan before lookup. An already present contact opens its existing conversation without a new lookup or row, including a pending relationship that keeps its existing privacy state. A blocked ID remains blocked and offers only the established explicit Unblock confirmation. Other IDs go through the existing exact-ID directory lookup, encrypted request and E2EE profile exchange. No plaintext display name is fetched from the server, request content is not revealed, and acceptance or verification is never implied by scanning.

The 36-byte payload uses QR error correction level M with a two-module quiet zone, displayed as a 512-pixel monochrome bitmap with extra white UI padding. QR rendering happens only on the secure profile QR screen. MainActivity's app-lock gate still protects both profile and Add Contact. The scanner is a separate Activity, so the lock is rechecked when returning to Ghost Cloak.

## Validation and future work

JVM tests cover round-trip normalization and malformed payload rejection. AVD tests decode the production QR bitmap and cover self/existing contact UI and permission-at-entry behavior; blocked-contact tests continue to cover the explicit unblock gate. Physical two-phone validation must confirm camera permission denial/retry, offline scan, duplicate/block behavior, request acceptance, encrypted profile-name exchange, and safety-number independence. No backend deployment, server DB migration, or Android DB migration is required.

Future verified QR would require explicit binding to identity-key material, a separate version, and a reviewed UX; this v1 format must never be interpreted as verification. Clickable deep links and QR import from the photo library are deferred.
