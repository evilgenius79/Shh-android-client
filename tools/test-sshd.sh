#!/bin/bash
# Starts or stops two throwaway OpenSSH servers for the interoperability tests in :ssh-core.
#
#   sudo tools/test-sshd.sh start   # prints the environment variable the tests look for
#   sudo tools/test-sshd.sh stop
#
# Server 1 (port 2222) is a stock modern configuration with password and public key login.
# Server 2 (port 2223) only offers legacy algorithms and only keyboard-interactive login through
# PAM, which is how many older appliances behave.
#
# Needs root: it creates a local user "shhtest" and sshd has to be able to check its password.
set -euo pipefail

DIR=/tmp/shh-test-sshd
USER_NAME=shhtest
PASSWORD='shh-Test-pw-1'
PORT=2222
LEGACY_PORT=2223

stop() {
    for pidfile in "$DIR"/sshd.pid "$DIR"/sshd-legacy.pid; do
        if [ -f "$pidfile" ]; then kill "$(cat "$pidfile")" 2>/dev/null || true; fi
    done
    rm -rf "$DIR"
}

start() {
    stop
    mkdir -p "$DIR" /run/sshd
    id "$USER_NAME" >/dev/null 2>&1 || useradd -m -s /bin/bash "$USER_NAME"
    echo "$USER_NAME:$PASSWORD" | chpasswd
    ssh-keygen -q -t ed25519 -N '' -f "$DIR/host_ed25519"
    ssh-keygen -q -t rsa -b 3072 -N '' -f "$DIR/host_rsa"
    ssh-keygen -q -t ecdsa -b 256 -N '' -f "$DIR/host_ecdsa"
    : > "$DIR/authorized_keys"
    chown "$USER_NAME" "$DIR/authorized_keys"
    # The tests add and remove keys here, and they usually do not run as root or as the test
    # user. World-writable is fine for a throwaway server (sshd runs with StrictModes off).
    chmod 666 "$DIR/authorized_keys"
    chmod 755 "$DIR"

    cat > "$DIR/sshd_config" <<CONF
Port $PORT
ListenAddress 127.0.0.1
HostKey $DIR/host_ed25519
HostKey $DIR/host_rsa
HostKey $DIR/host_ecdsa
PidFile $DIR/sshd.pid
AuthorizedKeysFile $DIR/authorized_keys
StrictModes no
PasswordAuthentication yes
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM yes
AllowUsers $USER_NAME
AllowTcpForwarding yes
AcceptEnv LANG LC_*
Subsystem sftp internal-sftp
LogLevel VERBOSE
CONF

    cat > "$DIR/sshd_config_legacy" <<CONF
Port $LEGACY_PORT
ListenAddress 127.0.0.1
HostKey $DIR/host_rsa
PidFile $DIR/sshd-legacy.pid
AuthorizedKeysFile none
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication yes
PubkeyAuthentication no
UsePAM yes
AllowUsers $USER_NAME
KexAlgorithms diffie-hellman-group14-sha1
HostKeyAlgorithms ssh-rsa
Ciphers aes128-cbc
MACs hmac-sha1
LogLevel VERBOSE
CONF

    /usr/sbin/sshd -f "$DIR/sshd_config" -E "$DIR/sshd.log"
    /usr/sbin/sshd -f "$DIR/sshd_config_legacy" -E "$DIR/sshd-legacy.log"
    sleep 0.5
    echo "export SHH_TEST_OPENSSH='127.0.0.1|$PORT|$LEGACY_PORT|$USER_NAME|$PASSWORD|$DIR/authorized_keys'"
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
