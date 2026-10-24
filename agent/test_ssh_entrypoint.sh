#!/bin/sh
# Dedicated LOCAL acceptance fixture; never install this script on a production host.
set -eu
umask 077
mkdir -p /root/.ssh /tmp/live-monitor-ops-e2e/secrets
ssh-keygen -A
if [ ! -f /tmp/live-monitor-ops-e2e/client-key ]; then
  ssh-keygen -q -t ed25519 -N '' -f /tmp/live-monitor-ops-e2e/client-key
fi
printf 'restrict,command="python3 /opt/live-monitor-agent/agent.py" ' > /root/.ssh/authorized_keys
cat /tmp/live-monitor-ops-e2e/client-key.pub >> /root/.ssh/authorized_keys
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys
passwd -d root >/dev/null
python3 - <<'PY'
import json, os
from pathlib import Path
root = Path('/tmp/live-monitor-ops-e2e')
config = {'environment': 'acceptance', 'state_root': str(root/'state'), 'backup_root': str(root/'backup'),
          'secrets_root': str(root/'secrets'), 'allowed_registries': ['docker.io'], 'allowed_bind_roots': [],
          'backup_helper_image': os.environ['LIVE_MONITOR_TEST_HELPER_IMAGE']}
Path('/etc/live-monitor-agent.json').write_text(json.dumps(config))
public = Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()
fixture = {'ssh_host_key': ' '.join(public[:2]), 'private_key': (root/'client-key').read_text()}
(root/'fixture-private.json').write_text(json.dumps(fixture))
PY
exec /usr/sbin/sshd -D -e -o PasswordAuthentication=no -o PermitRootLogin=prohibit-password -o HostKeyAlgorithms=ssh-ed25519
