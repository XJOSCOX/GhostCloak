# Phase 1C.1 local API v1

Status: local prototype; no deployment. The Android UI remains the Phase 1B local experience. JVM integration clients exercise the real HTTP adapter. Production UI/server configuration is not part of this phase.

## Encoding and transport

All endpoints use POST with `Content-Type: application/vnd.ghostcloak.v1+cbor`. This deliberately avoids body-bearing GETs and putting usernames in URL/access-log paths. No query parameters, Java serialization, JSON, redirects or content negotiation. Request/response types are the explicit `@Serializable` schemas in `Android/protocol/.../NetworkV1.kt`. Fields below use those exact names. Bytes use CBOR major type 2 byte strings (`alwaysUseByteString=true`); arrays/maps use definite lengths (`useDefiniteLengthEncoding=true`); integers are CBOR integers and strings are UTF-8. The existing encrypted EnvelopeCodec framing is unchanged and is carried opaquely inside a byte string. Every request has `version: 1` (encoded even when defaulted). The sealed request discriminator is its `@SerialName`, encoded by kotlinx.serialization CBOR as the two-element polymorphic array `[type, fields]`. Canonical here means byte-for-byte equality with re-encoding using the pinned codec, **not a claim of RFC deterministic CBOR**. Unknown fields, duplicate/noncanonical encodings and trailing bytes are rejected by decode/re-encode validation.

Maximum request: 196,608 bytes before decode. Maximum client response: 1,100,000 bytes. Envelopes have the existing 131,072-byte encoded limit and existing outer v1 validation. Prekey uploads: 1–16 complete public bundles, total pool 32. Fetch/ACK batch: 8. Authentication signature: 8–80 DER bytes, public credential: canonical P-256 X.509 SPKI (80–128 bytes). Public bundle structural bounds are checked after bounded decoding. No request compression. Decode failures return fixed errors. Public-key cryptographic validity/signatures for session creation are ultimately checked by libsignal at the receiving endpoint.

Production clients require HTTPS with platform certificate/hostname checks. `HttpGhostClient(..., allowLoopbackForTests=true)` allows HTTP only to literal loopback/localhost. Android release cleartext policy remains false, including loopback. The backend launcher binds only `127.0.0.1:8787`; it has no public listener/TLS/deployment mode. The JDK HTTP server is a small local test adapter, not the production ingress. Its bounded worker pool does not solve slow clients, aggregate input pressure or Internet DoS.

## Requests

Authorization uses `Authorization: Bearer <43-character base64url token>` only on authenticated endpoints. No token in a URL or body. No request may specify a mailbox owner for fetch or ACK.

| Path | Type | Fields besides version | Auth | Success response field |
|---|---|---|---|---|
| `/v1/auth/challenge` | challenge | accountId:string, deviceId:string, purpose:`register`/`login`, registrationHash:bytes | none | challenge |
| `/v1/accounts` | register | registration:Registration, challengeId:string, signature:bytes | proof | none |
| `/v1/auth/verify` | verify | accountId, deviceId, challengeId, signature:bytes | proof | session |
| `/v1/auth/revoke` | revoke | none | required | none |
| `/v1/accounts/username` | rename | username:string | required | none |
| `/v1/directory/lookup` | lookup | username:string | required | directory |
| `/v1/devices/prekeys` | prekeys | deviceId:string, bundles:PublicBundle[] | owner | none |
| `/v1/messages` | send | submissionId:string, recipientRoutingId:string, encryptedEnvelope:bytes | required | serverMessageId |
| `/v1/messages/fetch` | fetch | none | required | deliveries |
| `/v1/messages/ack` | ack | serverMessageIds:string[] | required | none |

Login challenge registrationHash is empty; registration uses SHA-256 of the canonical Registration encoding. All IDs are canonical lowercase UUIDs generated with secure UUID randomness. Account, device and routing IDs are independently generated. They must be different at registration.

`Registration` fields: accountId, deviceId, routingId, username, authPublicKey:bytes, bundles:PublicBundle[]. Username must already be normalized. Auth key possession is proven over the full registration hash, not simply the challenge ID. Account ID, device ID, routing ID, username and auth public key must be unused; existing rows are never replaced by registration. All public bundles have one device and identity.

`PublicBundle`: deviceId:string; registrationId:int (1..16383); identity:bytes (33); preKeyId:int and preKey:bytes (33); signedId:int and signedKey:bytes (33); signature:bytes (64); kyberId:int and kyberKey:bytes (1569); kyberSignature:bytes (64). IDs are positive Ints. The EC serializations have libsignal's `0x05` prefix; KYBER_1024 has its `0x08` prefix. These are the existing `RemoteKeyBundle` fields, not a new key-agreement protocol. Samples/tests use libsignal-generated values.

## Responses and errors

Every `ApiResponse` encodes version=1, challenge=null, session=null, directory=null, deliveries=[], serverMessageId=null, error=null, overriding only the applicable fields. Success status is 200, including ACK/revoke/registration. Responses have `Cache-Control: no-store`. No raw exception messages are returned.

* Challenge: id, random:32 bytes, expiresAt:epoch milliseconds, audience, accountId, deviceId, purpose, registrationHash.
* SessionGrant: token:string, expiresAt:epoch milliseconds. Returned once; no token retrieval endpoint.
* DirectoryEntry: accountId, deviceId, routingId, normalized username, bundle:PublicBundle. Fetch atomically consumes the EC/PQ bundle. Exhaustion is 409; caller must wait for replenishment. No list/prefix search API.
* Delivery: serverMessageId, encryptedEnvelope:bytes, receivedAt, expiresAt (epoch milliseconds).

Errors: 400 invalid request/schema/version/route/key material; 401 missing/expired/revoked session or invalid proof/challenge; 403 ownership failure; 404 absent destination/username/path; 409 username/ID conflict, prekey exhaustion/duplicate, signed-key conflict or differing submission retry; 413 oversized body; 415 unsupported media type; 429 rate/quota limits; 500 generic internal error. Client network errors are locally reported as 503; malformed server responses as 502. An oversized local request may encounter a closed/reset socket instead of receiving 413 because it is rejected without draining an arbitrarily large body.

## Run locally

From `Android/`: `./gradlew :backend:run --dependency-verification strict` (PowerShell: `.\gradlew.bat`). This starts ephemeral loopback storage on port 8787. Stopping it destroys account/session/mailbox state. Do not use real accounts or distribute this server.

Integration: `.\gradlew.bat :test-support:test --tests org.ghostcloak.testing.NetworkTest --dependency-verification strict`. The fixture starts its own server on an ephemeral loopback port and uses actual HttpURLConnection requests and libsignal sessions. No external account, host or database is needed.
