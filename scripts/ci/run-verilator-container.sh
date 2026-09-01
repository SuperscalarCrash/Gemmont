#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORE_RTL_DIR="${CORE_RTL_DIR:-${ROOT_DIR}}"

if [[ -z "${VERILATOR_IMAGE:-}" ]]; then
  if [[ "${CORE_RTL_DIR}" == "${ROOT_DIR}" && ! -s "${ROOT_DIR}/core_top.v" ]]; then
    echo "error: generated core_top.v is missing" >&2
    echo "run ./scripts/generate.sh before starting functional validation" >&2
    exit 1
  fi
  exec "${ROOT_DIR}/scripts/ci/run-verilator-validation.sh"
fi

command -v docker >/dev/null 2>&1 || {
  echo "error: Docker is required when VERILATOR_IMAGE is set" >&2
  exit 1
}
command -v id >/dev/null 2>&1 || {
  echo "error: id is required when VERILATOR_IMAGE is set" >&2
  exit 1
}
docker image inspect "${VERILATOR_IMAGE}" >/dev/null 2>&1 || {
  echo "error: local Docker image is missing: ${VERILATOR_IMAGE}" >&2
  echo "preload it on the runner or unset VERILATOR_IMAGE for host execution" >&2
  exit 1
}

CONTAINER_CORE_RTL_DIR="/workspace"
DOCKER_ARGS=(
  run --rm --init --pull never
  --user "$(id -u):$(id -g)"
  --volume "${ROOT_DIR}:/workspace"
)
if [[ "${CORE_RTL_DIR}" != "${ROOT_DIR}" ]]; then
  [[ -d "${CORE_RTL_DIR}" ]] || {
    echo "error: CORE_RTL_DIR does not exist: ${CORE_RTL_DIR}" >&2
    exit 1
  }
  CONTAINER_CORE_RTL_DIR="/cpu-rtl"
  DOCKER_ARGS+=(--volume "${CORE_RTL_DIR}:${CONTAINER_CORE_RTL_DIR}:ro")
fi

DOCKER_ARGS+=(
  --workdir /workspace
  --env HOME=/tmp
  --env RELEASE_CONFIG=/workspace/ci/release-config.json
  --env "SOURCE_SHA=${SOURCE_SHA:-unknown}"
  --env "VERILATOR_THREADS=${VERILATOR_THREADS:-8}"
  --env "VERILATOR_BUILD_DIR=${VERILATOR_BUILD_DIR:-/workspace/build/verilator-validation}"
  --env "CHIPLAB_TOOLS_DIR=${CHIPLAB_TOOLS_DIR:-/opt/chiplab}"
  --env "CORE_RTL_DIR=${CONTAINER_CORE_RTL_DIR}"
  "${VERILATOR_IMAGE}"
  ./scripts/ci/run-verilator-validation.sh
)

exec docker "${DOCKER_ARGS[@]}"
