# Privacy model

> **HISTORICAL PHASE SNAPSHOT.** The implementation and rollout statements below record their phase and may predate v2 Ghost Cloak IDs, V007/V008, seven-day mailbox expiry, and later privacy controls. Use the [current audit](../SECURITY_AUDIT_CURRENT.md) and [deployment runbook](../infrastructure/DEPLOYMENT.md) for current status; do not execute old route or migration instructions against a live target.

Phase 1A is a local prototype, without production networking, analytics or crash reporting. Identifiers are cryptographically random; no phone number or hardware identifier is an identity.

Do not collect IMEI, advertising IDs, serial numbers, MAC addresses, carrier, GPS, contact books, device model/manufacturer, Wi-Fi SSIDs, Bluetooth identifiers or analytics profiles. No Firebase Analytics, Facebook SDK, AppsFlyer, Adjust, Mixpanel or crash SDK is allowed.

Future transport should use TLS and a generic GhostCloak/1 identifier. Routing IDs, packet sizes, packet types, frequency, IP addresses and public key associations remain metadata; this is not anonymity. Retention, authentication, abuse controls and traffic analysis defenses require a later design. No directory is deployed here.

Clipboard, screenshots, screen sharing, accessibility services and endpoint compromise can expose plaintext. No universal screenshot-prevention promise is made. Backups must exclude all endpoint state. Future crash reporting requires privacy review.
