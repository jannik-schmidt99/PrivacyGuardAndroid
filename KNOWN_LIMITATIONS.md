# Known limitations and next steps

## Current MVP
The firewall is a deliberate black-hole VPN for blocked apps. It does not forward packets, which is why only blocked apps are routed into it.

## What Android does not expose directly
- Decrypted HTTPS payloads from other apps.
- Exact semantic meaning of encrypted uploads.
- A perfect "background-only" byte counter for every app/device combination.

## Recommended V2
- Add a production-grade user-space packet forwarder (e.g. a maintained tun2socks-style engine) so all selected monitored traffic can be forwarded and logged rather than only dropped.
- Parse DNS requests locally and build a domain/endpoint log.
- Keep IP/domain logs local; provide explicit retention controls.
- Add separate Wi-Fi/mobile policies and optional screen-off/background policies.
- Add per-app history charts and CSV export.
- Add Android permission-revocation shortcuts via system Settings.
