# Monica Key security v2

## Security claim

No software can honestly promise 100% security. Monica Key therefore uses a fail-closed release policy: production live-sharing stays blocked until the security-v2 protocol, automated abuse tests, cryptographic test vectors, and an independent review are complete.

## Verified findings

1. The current v1 server receives `claimSecret` and only stores its SHA-256 hash. This is not server-blind claim authorization.
2. The current v1 WebSocket route authenticates only `channelId` and the claimed role string.
3. The current v1 payload encryption uses one long-lived AES-256-GCM key and has no Double Ratchet.
4. The current v1 revocation endpoint validates a signed timestamp, but accepts a ten-minute window and has no one-time server challenge.
5. Current encrypted packets are variable size and expose timing and volume metadata.
6. Current API routes have no explicit application-level rate limiter.

## Corrections to the supplied audit

- Sending `hash(secret + channelId)` at creation and later sending the secret does not keep the secret from the server.
- Durable Object storage calls in v1 do not interpolate `channelId` into SQL statements. The alleged SQL injection is not present in the reviewed code.
- Android apps targeting modern Android versions do not automatically trust user-installed certificate authorities unless explicitly configured. Blindly pinning a shared, rotating `workers.dev` edge certificate can create outages and is not accepted as a standalone security control.
- Revocation already binds the channel and timestamp and checks server time. Security v2 shortens the window and adds a consumed challenge.

## Security-v2 protocol requirements

### Server-blind invitation claim

Pippi generates a one-time P-256 claim key pair. The server receives only the claim public key. Monica receives the private claim capability in the invitation, signs a fresh server nonce, and immediately destroys the capability after claiming.

### Authenticated devices

Pippi and Monica each generate a hardware-backed Android Keystore signing key when supported. Their public keys are registered through the signed create/claim flow. Private keys are non-exportable.

### Authenticated WebSockets

Each connection requires a fresh server nonce, a timestamp, the device public key identity, and an ECDSA signature over the complete canonical request. Challenges are single-use and expire after 30 seconds.

### Message security

Production messaging must use the maintained Signal `libsignal-android` and `libsignal-client` packages. Hand-written ratchet code is prohibited. The protocol must provide unique message keys, forward secrecy, break-in recovery, bounded skipped-key storage, replay detection, and authenticated headers.

### Recovery and burn codes

The only exportable recovery material is a 256-bit random recovery code with checksum, displayed once in grouped Base32 form and optionally as a QR code. It is never uploaded. Users may write it on paper and destroy it after device enrollment. Device authentication private keys remain non-exportable.

### Transport and metadata

- HTTPS/WSS only; cleartext traffic is disabled.
- Application signatures remain authoritative even if TLS trust is compromised.
- Fixed-size encrypted envelopes are used for location/chat/control classes.
- Cover traffic cadence is configurable; the UI must disclose that IP address and connection timing cannot be made perfectly invisible without an anonymity network.

### Abuse resistance

- Per-IP and per-channel rate limits.
- Strict body and packet size limits.
- Single-use nonces.
- Maximum concurrent sockets per role.
- No secrets, payloads, precise IPs, or full channel identifiers in logs.

## Release gate

A production APK must not be published unless all of the following pass:

- server-blind claim tests
- stolen-channel-ID WebSocket rejection tests
- replayed nonce and replayed message rejection tests
- ratchet test vectors and out-of-order message tests
- rate-limit tests
- cleartext/network-security tests
- dependency and secret scans
- independent security review
