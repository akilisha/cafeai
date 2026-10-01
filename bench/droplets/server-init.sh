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
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor > /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" > /etc/apt/sources.list.d/adoptium.list
apt-get update -y && apt-get install -y temurin-25-jdk
# cloud-init runs without HOME, so set it or JBang installs under /.jbang
export HOME=/root
curl -Ls https://sh.jbang.dev | bash -s - app setup
ln -sf /root/.jbang/bin/jbang /usr/local/bin/jbang
# Node (Express) and Go (Gin), from their release tarballs
curl -sL https://nodejs.org/dist/v24.21.0/node-v24.21.0-linux-x64.tar.xz | tar xJ -C /opt
ln -sf /opt/node-v24.21.0-linux-x64/bin/node /usr/local/bin/node
ln -sf /opt/node-v24.21.0-linux-x64/bin/npm /usr/local/bin/npm
curl -sL https://go.dev/dl/go1.27.1.linux-amd64.tar.gz | tar xz -C /usr/local
ln -sf /usr/local/go/bin/go /usr/local/bin/go
touch /var/log/bench-ready
