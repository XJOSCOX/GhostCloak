# TLS ingress

Use maintained nginx from the supported Linux distribution security channel. It offers explicit buffering, body/header limits and logging controls with a small systemd deployment. See [nginx TLS directives](https://nginx.org/en/docs/http/ngx_http_ssl_module.html) and [request limits](https://nginx.org/en/docs/http/ngx_http_core_module.html).

The template exposes 443 only. The owner must supply a DNS hostname and a standard publicly trusted certificate, then replace the placeholder hostname and certificate paths. Certificate private keys remain root-readable outside Git. Do not run certificate issuance during local package preparation. An owner-selected ACME DNS challenge can avoid port 80; if using HTTP challenge later, add only its challenge/redirect listener and review firewall changes separately.

Run `nginx -t` before any reload. Check hostname, chain, expiry, TLS 1.2/1.3 and rejection of older versions from a separate host. Schedule renewal with a successful syntax check before reload and alert before expiration. Never disable hostname verification to repair a failing client.

The backend binds 127.0.0.1:8787, PostgreSQL binds 127.0.0.1:5432. Only the trusted local nginx sets X-Forwarded-Proto. Forwarded client IP headers are removed. This reduces application exposure; it does not hide source addresses from nginx, the kernel or hosting provider. See `protocol/TLS_POLICY.md`.
