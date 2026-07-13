# Production release gate

The live Pippi Key and Monica Key applications are not to be described as production-secure until security-v2 is complete and independently reviewed.

The offline Showcase APK is non-production and contains no real relay credentials or live tracking transport.

Required before a production release:

- server-blind one-time claim capability
- mutually authenticated WebSockets
- maintained Signal protocol implementation for message encryption
- replay and downgrade protection
- strict HTTPS-only networking
- rate limits and bounded resource use
- fixed-size encrypted envelopes and documented metadata limits
- dependency, secret, static-analysis, and protocol tests
- external review
