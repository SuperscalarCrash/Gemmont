#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VIVADO_IMAGE="${VIVADO_IMAGE:-}"
VIVADO_MAX_THREADS="${VIVADO_MAX_THREADS:-8}"

if [[ ! "${VIVADO_MAX_THREADS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "error: VIVADO_MAX_THREADS must be a positive integer" >&2
  exit 1
fi

if [[ -z "${VIVADO_IMAGE}" ]]; then
  command -v vivado >/dev/null 2>&1 || {
    echo "error: Vivado is required when VIVADO_IMAGE is unset" >&2
    echo "set VIVADO_IMAGE to an explicitly provisioned image to use Docker" >&2
    exit 1
  }
  exec "${ROOT_DIR}/scripts/ci/build-fpga-release.sh"
fi

for command in docker id; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "error: required command not found: ${command}" >&2
    exit 1
  fi
done

if ! docker image inspect "${VIVADO_IMAGE}" >/dev/null 2>&1; then
  echo "error: local Docker image is missing: ${VIVADO_IMAGE}" >&2
  echo "The CI deliberately uses --pull never and will not download another image." >&2
  exit 1
fi

host_uid="$(id -u)"
host_gid="$(id -g)"

docker run --rm --init --pull never \
  --name "gemmont-vivado-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-${FPGA_BUILD_TARGET:-all}" \
  --user 0:0 \
  --volume "${ROOT_DIR}:/workspace" \
  --workdir /workspace \
  --entrypoint /bin/bash \
  --env "HOST_UID=${host_uid}" \
  --env "HOST_GID=${host_gid}" \
  --env "RELEASE_VERSION=${RELEASE_VERSION:-local}" \
  --env "SOURCE_SHA=${SOURCE_SHA:-unknown}" \
  --env "RELEASE_CONFIG=/workspace/ci/release-config.json" \
  --env "FPGA_BUILD_TARGET=${FPGA_BUILD_TARGET:-}" \
  --env "UBOOT_BINARY_URL=${UBOOT_BINARY_URL:-https://gitee.com/loongson-edu/la32r-uboot/releases/download/loongsonsoc-v0.1.0/u-boot.bin}" \
  --env "UBOOT_BINARY_SHA256=${UBOOT_BINARY_SHA256:-357eb0280e3f493ca608e554421575a64f68b351e16e21d69ea7979882d5f0a6}" \
  --env "PROGRAMMER_BY_UART_URL=${PROGRAMMER_BY_UART_URL:-https://gitee.com/chenzes/chiplab-tools/releases/download/chiplab-tools/programmer_by_uart.bit}" \
  --env "PROGRAMMER_BY_UART_SHA256=${PROGRAMMER_BY_UART_SHA256:-f0ac4b8d23887805249f0d0314b182c99d03200269e230d86b42aca485d0e111}" \
  --env "LINUX_RELEASES_URL=${LINUX_RELEASES_URL:-https://gitee.com/loongson-edu/la32r-Linux/releases}" \
  --env "VIVADO_JOBS=${VIVADO_JOBS:-1}" \
  --env "VIVADO_MAX_THREADS=${VIVADO_MAX_THREADS}" \
  --env "VIVADO_RUN_TIMEOUT_MINUTES=${VIVADO_RUN_TIMEOUT_MINUTES:-240}" \
  --env "VIVADO_IMAGE=${VIVADO_IMAGE}" \
  "${VIVADO_IMAGE}" \
  -c '
    set -euo pipefail

    # Vivado 2023.2 forces en_US.UTF-8, while the selected image only ships the
    # equivalent C.utf8 locale.  Add an ephemeral locale alias before dropping
    # root.  A symlink avoids copying roughly 840 MiB into the Docker writable
    # layer; keeping the Vivado launcher untouched avoids IP-flow instability.
    if ! locale -a | grep -Eiq "^en_US\\.utf-?8$"; then
      ln -s C.utf8 /usr/lib/locale/en_US.UTF-8
    fi

    # The image has WebTalk enabled globally. Its Docker host-information
    # probe crashes in libudev on a runner without a udev database, so disable
    # optional usage collection in this ephemeral container.
    printf "collectusagestatistics=false\n" \
      >"${XILINX_VIVADO}/data/webtalk/webtalksettings"

    # Match the image entrypoint after supplying its required locale.
    source "${XILINX_VIVADO}/settings64.sh"

    # The 2023.2 host probe is also incompatible with Bookworm libudev. Batch
    # implementation does not access FPGA devices, so give the probe an empty
    # libudev view. Hardware Manager is never run in this container.
    udev_compat=/tmp/gemmont-libudev
    install -d "${udev_compat}"
    gcc -shared -fPIC -O2 \
      -Wl,-soname,libudev.so.1 \
      -o "${udev_compat}/libudev.so.1" \
      /workspace/scripts/ci/libudev-empty.c
    ln -s libudev.so.1 "${udev_compat}/libudev.so.0"
    export LD_LIBRARY_PATH="${udev_compat}:${LD_LIBRARY_PATH:-}"

    ci_home=/tmp/gemmont-ci-home
    install -d -o "${HOST_UID}" -g "${HOST_GID}" \
      "${ci_home}" "${ci_home}/.Xilinx" "${ci_home}/.Xilinx/Vivado"
    # Match the competition runner Vivado implementation algorithms.  This
    # is separate from launch_runs -jobs and from the parallel Actions matrix.
    printf "set_param general.maxThreads %s\n" "${VIVADO_MAX_THREADS}" \
      >"${ci_home}/.Xilinx/Vivado/Vivado_init.tcl"
    chown "${HOST_UID}:${HOST_GID}" \
      "${ci_home}/.Xilinx/Vivado/Vivado_init.tcl"

    # Bound glibc per-thread arenas; Vivado otherwise retains several GiB of
    # fragmented heap across the large generated Chisel netlist.
    export MALLOC_ARENA_MAX=2

    exec setpriv \
      --reuid "${HOST_UID}" \
      --regid "${HOST_GID}" \
      --clear-groups \
      env HOME="${ci_home}" USER=ci LOGNAME=ci \
      /workspace/scripts/ci/build-fpga-release.sh
  '
