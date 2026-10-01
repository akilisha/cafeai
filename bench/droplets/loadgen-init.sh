#!/bin/bash
exec > /var/log/bench-setup.log 2>&1
cat >/etc/sysctl.d/99-bench.conf <<'X'
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.ip_local_port_range = 1024 65535
net.ipv4.tcp_tw_reuse = 1
net.core.netdev_max_backlog = 65535
fs.file-max = 2097152
X
sysctl --system
cat >/etc/security/limits.d/99-bench.conf <<'X'
* soft nofile 1048576
* hard nofile 1048576
root soft nofile 1048576
root hard nofile 1048576
X
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl wget gnupg ca-certificates unzip htop sysstat
apt-get install -y build-essential libssl-dev zlib1g-dev git
git clone --depth 1 https://github.com/giltene/wrk2.git /opt/wrk2 && make -C /opt/wrk2 && ln -sf /opt/wrk2/wrk /usr/local/bin/wrk2
# k6 from its release binary: the apt repo's signing key rotates, and the image has no dirmngr to fetch it
V=$(curl -sI https://github.com/grafana/k6/releases/latest | grep -i "^location" | grep -oE "v[0-9.]+")
curl -sL "https://github.com/grafana/k6/releases/download/$V/k6-$V-linux-amd64.tar.gz" | tar xz -C /tmp
mv "/tmp/k6-$V-linux-amd64/k6" /usr/local/bin/k6
touch /var/log/bench-ready
