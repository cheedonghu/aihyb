#!/bin/sh
# Take a private consistent backup of the explicitly selected existing project.
set -eu
umask 077
: "${AIHYB_SITE_ROOT:?Set the absolute existing site directory}"
: "${AIHYB_COMPOSE_PROJECT:?Set the expected Compose project name}"
case "$AIHYB_SITE_ROOT:$AIHYB_COMPOSE_PROJECT" in
  /opt/aihyb-halo-poc:aihyb-halo-poc|/opt/aihyb-halo-prd:aihyb-halo-prd) ;;
  *) echo 'Unrecognized site directory and project pair; refusing backup.' >&2; exit 1 ;;
esac
cd "$AIHYB_SITE_ROOT"
test "$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')" = "$AIHYB_COMPOSE_PROJECT"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
TARGET="$AIHYB_SITE_ROOT/backups/$STAMP"
mkdir -m 700 "$TARGET"
docker compose stop halo
trap 'docker compose start halo >/dev/null' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker compose exec -T db pg_dump -U halo -d halo -Fc > "$TARGET/halo.dump"
tar -czf "$TARGET/halo-files.tar.gz" data/halo
cp .env "$TARGET/runtime.env"
cp -a secrets "$TARGET/secrets"
cp compose.yaml compose.media.yaml compose.public.yaml "$TARGET/"
ENTRY=$(docker compose config --format json | python3 -c 'import json,sys; print(next(v["source"] for v in json.load(sys.stdin)["services"]["caddy"]["volumes"] if v["target"] == "/etc/caddy/Caddyfile"))')
mkdir "$TARGET/public"
cp "$ENTRY" "$TARGET/public/Caddyfile"
if [ -d public/site-verification ]; then cp -a public/site-verification "$TARGET/public/site-verification"; fi
if [ -d caddy ]; then cp -a caddy "$TARGET/caddy"; fi
if [ -d runtime ]; then cp -a runtime "$TARGET/runtime"; fi
if [ -d data/caddy ]; then tar -czf "$TARGET/caddy-files.tar.gz" data/caddy; fi
if [ "$AIHYB_COMPOSE_PROJECT" = aihyb-halo-prd ]; then
  cp /etc/systemd/system/aihyb-prd-s3-egress.service "$TARGET/"
  cp /usr/local/lib/aihyb-halo-prd/s3-egress-proxy.py "$TARGET/"
else
  cp /etc/systemd/system/aihyb-poc-s3-egress.service "$TARGET/"
  cp /usr/local/lib/aihyb-halo-poc/s3-egress-proxy.py "$TARGET/"
fi
(cd "$TARGET" && sha256sum halo.dump halo-files.tar.gz > checksums.txt)
if [ -f "$TARGET/caddy-files.tar.gz" ]; then
  (cd "$TARGET" && sha256sum caddy-files.tar.gz >> checksums.txt)
fi
docker compose start halo
trap - EXIT INT TERM
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1:18090/actuator/health/readiness 2>/dev/null | grep -q '"UP"' &&
     curl -fsS --max-time 5 'http://127.0.0.1:18090/apis/api.aihyb.run/v1alpha1/contents?size=1&page=1' >/dev/null 2>&1; then
    printf 'Site backup saved privately: %s\n' "$TARGET"
    exit 0
  fi
  sleep 2
done
echo 'Backup created, but Halo readiness did not return in time.' >&2
exit 1
