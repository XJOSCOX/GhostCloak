# Isolated local validator, not a production deployment image. No ports are published.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends nginx openssl python3 nftables iproute2 curl ca-certificates systemd && rm -rf /var/lib/apt/lists/*
RUN groupadd ghostcloak-tunnel && useradd --system --no-create-home --shell /usr/sbin/nologin ghostcloak-origin && useradd --system --no-create-home --shell /usr/sbin/nologin --groups ghostcloak-tunnel cloudflared
ENTRYPOINT ["python3", "/fixtures/tests/validate_origin.py"]
