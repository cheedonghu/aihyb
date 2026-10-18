#!/bin/sh
set -eu
umask 077
cd /opt/aihyb-halo-poc
if [ ! -f .env ]; then
  printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" > .env
  printf 'HALO_IMAGE=halohub/halo:2.26.1\nPOSTGRES_IMAGE=postgres:17-bookworm\n' >> .env
fi
chmod 0600 .env
docker compose config --quiet
docker compose --parallel 1 pull
if ! grep -q '^HALO_IMAGE=.*@sha256:' .env; then
  HALO_DIGEST=$(docker image inspect halohub/halo:2.26.1 --format '{{index .RepoDigests 0}}')
  DB_DIGEST=$(docker image inspect postgres:17-bookworm --format '{{index .RepoDigests 0}}')
  sed -i "s|^HALO_IMAGE=.*|HALO_IMAGE=$HALO_DIGEST|;s|^POSTGRES_IMAGE=.*|POSTGRES_IMAGE=$DB_DIGEST|" .env
  printf 'Halo: %s\nPostgreSQL: %s\n' "$HALO_DIGEST" "$DB_DIGEST" > evidence/images.txt
fi
if [ ! -f evidence/first-start-epoch.txt ]; then
  date -u +%s > evidence/first-start-epoch.txt
fi
docker compose up -d
docker compose ps
