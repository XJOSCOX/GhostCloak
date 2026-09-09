# Device authentication v1

Account control uses a separate P-256/secp256r1 key pair with the platform JCA `SHA256withECDSA` signature implementation. Signatures use provider DER encoding; public keys use canonical X.509 SPKI. The verifier checks curve parameters rather than merely accepting any EC key of the same size. Key generation uses JCA KeyPairGenerator and SecureRandom. No custom signature, JWT or password scheme is used.

References: [Java Signature](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/security/Signature.html), [JDK provider algorithms](https://docs.oracle.com/en/java/javase/21/security/oracle-providers.html), [Android Keystore](https://developer.android.com/privacy-and-security/keystore). Android supports platform EC signing; the implementation here stores the PKCS#8 auth private key inside the existing SQLCipher EndpointRecords, whose random database key is Keystore-wrapped. It does **not** claim the auth private key is a non-exportable hardware key. Temporary PKCS#8 byte arrays are wiped; provider/GC copies cannot be guaranteed erased.

Create the encryption identity first. EndpointNetworkState then generates independent account and routing UUIDs and the authentication key pair; the device ID is the existing random endpoint ID. Credential records, routes and raw access token are scoped by SHA-256 of the configured audience inside encrypted storage. Missing private material in an existing credential fails closed, rather than silently generating a replacement. Android instrumentation checks signing before/after encrypted reopen and ensures fixture token/plaintext are absent from the database file.

## Challenge proof

The server generates 32 random bytes plus a random challenge UUID. Lifetime defaults to 60 seconds (configurable up to 120 seconds). A login challenge does not disclose whether an account exists. Issuance is globally limited for anonymous requests and limited to 1,000 live challenges. All verification attempts atomically claim/remove the challenge in a separate committed transaction before signature verification. Thus the failure threshold is **one**, and failed registration/signature validation does not restore the challenge. Expired rows are removed. Claiming the wrong random ID cannot authenticate.

The exact signed statement is constructed with big-endian DataOutputStream:

1. int32 protocol version = 1;
2. six fields, each int32 UTF-8 byte length followed by its bytes: `GhostCloak.DeviceAuth`, purpose, challenge ID, account ID, device ID, configured service audience;
3. int32 length and challenge random bytes;
4. int64 expiresAt in epoch milliseconds;
5. int32 length and registrationHash bytes.

Register purpose includes SHA-256(canonical Registration); login uses empty registrationHash. Client verifies audience, account/device, purpose, random length, expiry and the registration hash before signing. Server reconstructs the statement from its own claimed challenge, checks account/device ownership and verifies against the registered credential. An intercepted registration signature cannot authorize changed username, routing ID, auth key, encryption material or another service. Network TLS remains necessary; challenge signatures do not prevent token theft or an active authentication relay through a compromised endpoint.

## Access sessions

Successful login generates 32 random bytes, returns their 43-character unpadded base64url representation and stores only SHA-256(token UTF-8) plus device ID/expiry. Token values contain no metadata. Default lifetime: five minutes; maximum configurable: 15 minutes. Only one active session per device: login revokes previous sessions. Explicit revoke removes the current session. Authenticated requests resolve owner from the server session, not caller-supplied account/mailbox parameters.

Hashed-token lookup uses a hash map in the prototype, not a raw token comparison. There is no meaningful raw token prefix comparison to make constant-time. Registration/dedupe digest comparisons use MessageDigest.isEqual; cryptographic signature verification uses JCA. Raw tokens appear transiently in requests/responses/client memory and encrypted endpoint storage, never backend repository state or logs. Session expiry is enforced by the backend even if the client retains an expired token.

## Identity, rotation and compromise

Auth key and Signal identity are separate boundaries. Username is a public discovery label and can be renamed without changing either key, device ID or routing ID. Username normalization is Locale.ROOT lowercase; 3–24 characters matching `[a-z0-9][a-z0-9_.]{2,23}`, with explicit reserved Ghost Cloak/system/admin/support names. Non-ASCII is rejected, avoiding Unicode normalization/invisible-character ambiguities. ASCII lookalikes such as `l` and `1`, impersonation and similar spellings remain possible. Verify Signal safety numbers out of band.

Auth-key rotation/account recovery is deliberately unavailable in v1. Registration cannot replace an existing credential. A future rotation endpoint must require fresh old-key authentication, new-key proof, an atomic credential update and revocation of every session/challenge, while leaving Signal trust independent. Losing this prototype auth key means losing account control; do not reuse the same account with a new key. This phase makes no recovery promise.

Auth-key theft permits account login, queue access/removal, public-prekey updates and username changes. It does not by itself decrypt past/future Signal ciphertext without endpoint encryption state, but permits metadata abuse and denial of service. Endpoint database compromise may expose both auth and encryption state. Server compromise exposes hashed sessions/public keys and permits directory substitution/traffic suppression. Existing identity pins and explicit safety-number verification remain essential; first-contact TOFU is vulnerable to a malicious directory. No key transparency is implemented.
