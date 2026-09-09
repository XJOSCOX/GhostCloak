# Storage boundary

OpaqueMailbox is bounded, in-memory and local-only. It stores encoded encrypted envelopes, not endpoint records. A future persistent implementation must preserve this boundary and define expiration.
