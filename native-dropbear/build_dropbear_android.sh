#!/usr/bin/env bash
set -euo pipefail

DROPBEAR_TAG="DROPBEAR_2026.94"
NDK_DIR="${ANDROID_SDK_ROOT}/ndk/29.0.14206865"
TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64"
MAKE_BIN="${NDK_DIR}/prebuilt/linux-x86_64/bin/make"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORK_DIR="/tmp/dropbear-build"
SRC_DIR="${WORK_DIR}/src"

rm -rf "${WORK_DIR}"
mkdir -p "${SRC_DIR}"

if [ -d "/tmp/dropbear-src/src" ]; then
  cp -a /tmp/dropbear-src/. "${SRC_DIR}/"
else
  curl -fsSL "https://github.com/mkj/dropbear/archive/refs/tags/${DROPBEAR_TAG}.tar.gz" | tar -xz -C "${SRC_DIR}" --strip-components=1
fi

python3 "${SCRIPT_DIR}/patch_dropbear.py" "${SRC_DIR}"

cat > "${SRC_DIR}/localoptions.h" << 'EOF'
#define DEFAULT_RECV_WINDOW 262144
#define DROPBEAR_CLI_PASSWORD_AUTH 1
#define DROPBEAR_CLI_PUBKEY_AUTH 1
#define DROPBEAR_CLI_ANYCLI 1
#define DROPBEAR_CLI_LOCALTCPFWD 1
#define DROPBEAR_CLI_REMOTETCPFWD 1
#define DROPBEAR_USE_PASSWORD_ENV 1
#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_PAM_AUTH 0
#define DROPBEAR_AES128 1
#define DROPBEAR_AES256 1
#define DROPBEAR_CHACHA20POLY1305 1
#define DROPBEAR_ED25519 1
#define DROPBEAR_ECDSA 1
#define DROPBEAR_RSA 1
EOF

build_abi() {
  local abi="$1"
  local target="$2"
  local api="24"
  local build_sub="${WORK_DIR}/build-${abi}"
  local out_dir="${REPO_ROOT}/app/src/main/jniLibs/${abi}"

  mkdir -p "${build_sub}" "${out_dir}"
  cp -a "${SRC_DIR}/." "${build_sub}/"

  export CC="${TOOLCHAIN}/bin/${target}${api}-clang"
  export AR="${TOOLCHAIN}/bin/llvm-ar"
  export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
  export STRIP="${TOOLCHAIN}/bin/llvm-strip"
  export CFLAGS="-O2 -fPIE -DANDROID"
  export LDFLAGS="-pie"

  pushd "${build_sub}" >/dev/null
  ./configure --host="${target}" --disable-zlib --disable-lastlog --disable-utmp --disable-utmpx --disable-wtmp --disable-wtmpx --disable-loginfunc --disable-pututline --disable-pututxline --disable-syslog
  "${MAKE_BIN}" PROGRAMS="dbclient"
  "${STRIP}" dbclient
  cp dbclient "${out_dir}/libdropbear.so"
  chmod 0755 "${out_dir}/libdropbear.so"
  popd >/dev/null
}

build_abi "arm64-v8a" "aarch64-linux-android"
build_abi "armeabi-v7a" "armv7a-linux-androideabi"
build_abi "x86_64" "x86_64-linux-android"
build_abi "x86" "i686-linux-android"

ls -lh "${REPO_ROOT}"/app/src/main/jniLibs/*/libdropbear.so
