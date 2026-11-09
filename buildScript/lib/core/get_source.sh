#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

# Siblings are pulled from the spaiter forks on GitHub. Each fork carries the full
# commit history of its upstream plus the patches this client needs; byedpi's JNI
# patch is already committed on the nekoboxplus-jni branch (no checkout/apply needed).
# Override SIBLINGS_GIT to clone from a mirror (e.g. SIBLINGS_GIT=git@myhost:spaiter).
SIBLINGS_GIT="${SIBLINGS_GIT:-https://github.com/spaiter}"

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout "$SIBLINGS_GIT/android-sing-box.git" sing-box
fi
pushd sing-box
if [ "$(git rev-parse HEAD)" != "$COMMIT_SING_BOX" ]; then
  git checkout "$COMMIT_SING_BOX"
fi
popd

####

if [ ! -d "sing-vmess" ]; then
  git clone --no-checkout "$SIBLINGS_GIT/android-sing-vmess.git" sing-vmess
fi
pushd sing-vmess
if [ "$(git rev-parse HEAD)" != "$COMMIT_SING_VMESS" ]; then
  git checkout "$COMMIT_SING_VMESS"
fi
popd


####

# wireguard-go: our fork (sagernet pin + handshake rebind), used through a replace
# in sing-box/go.mod and libcore/go.mod.
if [ ! -d "wireguard-go" ]; then
  git clone -b nekoboxplus-patched "$SIBLINGS_GIT/android-wireguard-go.git" wireguard-go
fi

####

# byedpi: the JNI api patch is baked into the nekoboxplus-jni branch of the
# fork, so the sibling is used as-is (no upstream checkout, no git apply).
if [ ! -d "byedpi" ]; then
  git clone -b nekoboxplus-jni "$SIBLINGS_GIT/android-byedpi.git" byedpi
fi

####

popd
