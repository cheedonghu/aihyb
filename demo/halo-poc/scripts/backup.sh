#!/bin/sh
set -eu
umask 077
cd /opt/aihyb-halo-poc
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
TARGET="/opt/aihyb-halo-poc/backups/$STAMP"
mkdir -p "$TARGET"
docker compose stop halo
trap 'docker compose start halo >/dev/null' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker compose exec -T db pg_dump -U halo -d halo -Fc > "$TARGET/halo.dump"
tar -czf "$TARGET/halo-files.tar.gz" data/halo
cp .env "$TARGET/runtime.env"
cp -a secrets "$TARGET/secrets"
cp compose.yaml "$TARGET/compose.yaml"
if [ -f compose.media.yaml ]; then cp compose.media.yaml "$TARGET/compose.media.yaml"; fi
if [ -f compose.public.yaml ]; then cp compose.public.yaml "$TARGET/compose.public.yaml"; fi
if [ -d public ]; then cp -a public "$TARGET/public"; fi
if [ -d data/caddy ]; then tar -czf "$TARGET/caddy-files.tar.gz" data/caddy; fi
if [ -f /usr/local/lib/aihyb-halo-poc/s3-egress-proxy.py ]; then
  mkdir -p "$TARGET/media-egress"
  cp /usr/local/lib/aihyb-halo-poc/s3-egress-proxy.py "$TARGET/media-egress/"
  cp /etc/systemd/system/aihyb-poc-s3-egress.service "$TARGET/media-egress/"
fi
cp evidence/images.txt "$TARGET/images.txt"
sha256sum "$TARGET/halo.dump" "$TARGET/halo-files.tar.gz" > "$TARGET/checksums.txt"
if [ -f "$TARGET/caddy-files.tar.gz" ]; then sha256sum "$TARGET/caddy-files.tar.gz" >> "$TARGET/checksums.txt"; fi
docker compose start halo
trap - EXIT INT TERM
printf 'Backup saved privately to %s\n' "$TARGET"
START=$(date +%s)
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 3 http://127.0.0.1:18090/actuator/health/readiness | grep -q '"UP"'; then
    ELAPSED=$(( $(date +%s) - START ))
    printf 'Readiness after restart: %s seconds\n' "$ELAPSED"
    printf '%s\n' "$ELAPSED" > evidence/restart-readiness-seconds.txt
    exit 0
  fi
  sleep 2
done
echo 'Readiness timed out after restart' >&2
exit 1
