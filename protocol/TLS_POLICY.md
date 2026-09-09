# TLS policy — Phase 1C.2

Android uses platform HTTPS certificate-chain and hostname validation. Cleartext remains disabled. The compiled `ghostcloakApiOrigin` must be an HTTPS DNS origin; an unset origin disables network controls. No trust-all manager, user-entered release URL, self-signed production CA, or custom cryptography is provided.

Ingress terminates TLS 1.3 or TLS 1.2 with forward-secret AEAD suites. Use a publicly trusted CA and automated renewal operated by the owner. Validate the complete chain and hostname before reloading nginx; monitor expiry independently. Renewal must not require an Android update.

Hard certificate pinning is intentionally absent. It can reduce exposure to a compromised CA but can also strand installed clients after rotation or emergency replacement. Any future pinning design requires independently stored backup public-key pins, an overlap period, expiration/recovery policy and rehearsed rotation. Review those tradeoffs before enabling it.

TLS protects the hop to ingress. It does not hide IP addresses, timing, packet sizes, account/routing metadata from the operator, or authenticate a correspondent's Signal identity. Libsignal encryption and out-of-band safety-number verification remain the message security boundary. A compromised CA/proxy may steal bearer tokens or manipulate directory responses; existing pins detect replacement identities, while first-contact verification remains essential.

Local integration certificates are generated only for an isolated test listener and trusted only inside that test JVM. They are not production CA material or installed into Android/system trust.
