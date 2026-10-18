#!/bin/sh
set -eu
umask 077
POC_ROOT=/opt/aihyb-halo-poc
write_poc_daemon_config() {
cat <<'EOF'
{
  "ip-forward": false,
  "ip-forward-no-drop": true,
  "default-address-pools": [{"base":"172.29.240.0/20","size":24}],
  "bip": "172.29.239.1/24",
  "max-concurrent-downloads": 1,
  "log-driver": "local",
  "log-opts": {"max-size":"10m","max-file":"3"}
}
EOF
}
mkdir -p "$POC_ROOT/evidence" "$POC_ROOT/secrets"
if [ ! -e "$POC_ROOT/evidence/host-before.txt" ]; then
  { date -u; cat /etc/os-release; ip route; ss -ltnp; free -m; df -h /; systemctl show ssh stat_client -p Id -p MainPID -p ActiveState; sysctl net.ipv4.ip_forward; } > "$POC_ROOT/evidence/host-before.txt"
fi
if command -v docker >/dev/null 2>&1; then
  echo 'Docker already installed; leaving daemon configuration untouched.'
  docker version --format '{{.Server.Version}}'
  docker compose version
  exit 0
fi
if [ -e /etc/docker/daemon.json ]; then
  if ! write_poc_daemon_config | cmp -s /etc/docker/daemon.json -; then
    echo 'Unrelated Docker configuration detected; stop for inspection.' >&2
    exit 1
  fi
  echo 'Reusing identical PoC configuration after interrupted installation.'
fi
for package in docker.io containerd runc podman-docker; do
  if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
    echo "Existing runtime package $package; stop rather than removing it." >&2
    exit 1
  fi
done
export DEBIAN_FRONTEND=noninteractive
apt-get -o Acquire::http::Dl-Limit=2048 update
apt-get -y --no-install-recommends -o Acquire::http::Dl-Limit=2048 install ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl --fail --location --retry 2 --connect-timeout 15 --max-time 90 --limit-rate 2m https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod 0644 /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: trixie
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get -o Acquire::https::Dl-Limit=2048 update
install -m 0755 -d /etc/docker
# Preserve the host's initial forwarding=0. The PoC uses a dedicated bridge
# and loopback userland-proxy, and needs no routed container Internet access.
write_poc_daemon_config > /etc/docker/daemon.json
apt-get -s --no-install-recommends install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin > "$POC_ROOT/evidence/docker-install-simulation.txt"
if grep -q '^Remv ' "$POC_ROOT/evidence/docker-install-simulation.txt"; then
  echo 'Docker installation would remove packages; stop for inspection.' >&2
  exit 1
fi
apt-get -y --no-install-recommends -o Acquire::https::Dl-Limit=2048 -o Acquire::http::Dl-Limit=2048 install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl is-active docker
docker version --format '{{.Server.Version}}'
docker compose version
{ date -u; ip route; ss -ltnp; systemctl show ssh stat_client -p Id -p MainPID -p ActiveState; sysctl net.ipv4.ip_forward; free -m; } > "$POC_ROOT/evidence/host-after-docker.txt"
test "$(cat /proc/sys/net/ipv4/ip_forward)" = 0
systemctl is-active ssh stat_client
