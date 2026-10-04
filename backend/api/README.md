# API boundary

Phase 1L.1 documents a future versioned account/directory contract in
[the anonymous identity audit](../../Android/ANONYMOUS_IDENTITY_DESIGN.md).
The current v1 API still accepts and returns plaintext usernames in registration,
exact directory lookup, capability refresh and unknown-sender profiles. A public
Ghost Cloak ID must not authorize login/recovery; existing device-key proof remains
authoritative. This is a design reference, not an implemented endpoint.

Only the OpaqueEnvelopeStorage contract is implemented. No plaintext send API or decryption capability. Production TLS, authorization, quotas and retention are blocked pending review.
