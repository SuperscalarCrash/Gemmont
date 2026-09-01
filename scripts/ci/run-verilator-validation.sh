#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${VERILATOR_BUILD_DIR:-${ROOT_DIR}/build/verilator-validation}"
CORE_RTL_DIR="${CORE_RTL_DIR:-${ROOT_DIR}}"
CHIPLAB_DIR="${BUILD_DIR}/chiplab-functional"
REPORT_DIR="${BUILD_DIR}/reports"
LOG_DIR="${REPORT_DIR}/functional-logs"
RESOURCE_DIR="${ROOT_DIR}/src/main/resources"
RESOURCE_FILES=(
  btb-init.hex
  btb-redirect-control-init.hex
  btb-redirect-state-init.hex
  h64-residual-weights-int4.hex
  h64-residual-weights-int4-bank0.hex
  h64-residual-weights-int4-bank1.hex
  h64-residual-weights-int4-bank2.hex
  h64-residual-weights-int4-bank3.hex
  h64-residual-weights-int4-bank4.hex
  h64-residual-weights-int4-bank5.hex
  h64-residual-weights-int4-bank6.hex
  h64-residual-weights-int4-bank7.hex
  pht-init.hex
)

CHIPLAB_URL=""
SOURCE_SHA="${SOURCE_SHA:-unknown}"
VERILATOR_THREADS="${VERILATOR_THREADS:-8}"
CHIPLAB_TOOLS_DIR="${CHIPLAB_TOOLS_DIR:-/opt/chiplab}"
TOOLCHAIN_NAME="loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0"
RELEASE_CONFIG="${RELEASE_CONFIG:-${ROOT_DIR}/ci/release-config.json}"

FUNCTION_TARGETS=(
  func/func_lab3
  func/func_lab4
  func/func_lab6
  func/func_lab7
  func/func_lab8
  func/func_lab9
  func/func_lab14
  func/func_lab15
  func/func_lab19
  func/func_advance
)

if [[ "${1:-}" == "--print-config" ]]; then
  echo "functional_targets=${#FUNCTION_TARGETS[@]}"
  printf '%s\n' "${FUNCTION_TARGETS[@]}"
  exit 0
fi

die() {
  echo "error: $*" >&2
  exit 1
}

for command in git make verilator gcc g++ python3 timeout tee sha256sum install; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

config_values="$(python3 "${ROOT_DIR}/scripts/ci/read-release-config.py" \
  --config "${RELEASE_CONFIG}" --shell)" || die "invalid release configuration"
while IFS='=' read -r key value; do
  case "${key}" in
    CHIPLAB_URL|FUNCTION_CHIPLAB_REF|FUNCTION_CHIPLAB_SHA)
      printf -v "${key}" '%s' "${value}"
      ;;
  esac
done <<<"${config_values}"

[[ "${FUNCTION_CHIPLAB_SHA}" =~ ^[0-9a-f]{40}$ ]] || \
  die "FUNCTION_CHIPLAB_SHA must be a 40-character Chiplab commit"
[[ "${VERILATOR_THREADS}" =~ ^[1-9][0-9]*$ ]] || \
  die "VERILATOR_THREADS must be a positive integer"
[[ "${BUILD_DIR}" == "${ROOT_DIR}/build/"* ]] || \
  die "VERILATOR_BUILD_DIR must be a child of ${ROOT_DIR}/build"
[[ -d "${CORE_RTL_DIR}" ]] || die "CORE_RTL_DIR does not exist: ${CORE_RTL_DIR}"

mapfile -t core_rtl_files < <(
  find "${CORE_RTL_DIR}" -maxdepth 1 -type f -name '*.v' -print | sort
)
mapfile -t core_rtl_support_files < <(
  find "${CORE_RTL_DIR}" -maxdepth 1 -type f \
    \( -name '*.v' -o -name '*.h' \) -print | sort
)
((${#core_rtl_files[@]} > 0)) || die "CORE_RTL_DIR contains no Verilog files"
mapfile -t core_top_files < <(grep -l '^module core_top' "${core_rtl_files[@]}" || true)
((${#core_top_files[@]} == 1)) || die "CORE_RTL_DIR must define exactly one core_top"
CORE_TOP_FILE="${core_top_files[0]}"
for resource in "${RESOURCE_FILES[@]}"; do
  [[ -s "${RESOURCE_DIR}/${resource}" ]] || \
    die "readmem resource is missing or empty: ${RESOURCE_DIR}/${resource}"
done

rm -rf "${BUILD_DIR}"
mkdir -p "${CHIPLAB_DIR}" "${REPORT_DIR}" "${LOG_DIR}"
TOOLCHAIN_DIR="${CHIPLAB_TOOLS_DIR}/${TOOLCHAIN_NAME}"
PICOLIBC_DIR="${CHIPLAB_TOOLS_DIR}/picolibc"
NEMU_FILE="${CHIPLAB_TOOLS_DIR}/nemu/la32r-nemu-interpreter-so"
[[ -x "${TOOLCHAIN_DIR}/bin/loongarch32r-linux-gnusf-gcc" ]] || \
  die "LoongArch32R toolchain is missing"
[[ -f "${PICOLIBC_DIR}/include/stdio.h" ]] || die "picolibc is missing"
[[ -x "${NEMU_FILE}" ]] || die "NEMU is missing"
export PATH="${TOOLCHAIN_DIR}/bin:${PATH}"

echo "Fetching Chiplab ${FUNCTION_CHIPLAB_SHA} (${FUNCTION_CHIPLAB_REF})"
git -C "${CHIPLAB_DIR}" init -q
git -C "${CHIPLAB_DIR}" remote add origin "${CHIPLAB_URL}"
git -C "${CHIPLAB_DIR}" fetch -q --depth 1 origin "${FUNCTION_CHIPLAB_SHA}"
git -C "${CHIPLAB_DIR}" checkout -q --detach FETCH_HEAD
[[ "$(git -C "${CHIPLAB_DIR}" rev-parse HEAD)" == "${FUNCTION_CHIPLAB_SHA}" ]] || \
  die "checked-out Chiplab commit does not match ${FUNCTION_CHIPLAB_REF}"

mkdir -p "${CHIPLAB_DIR}/toolchains/nemu"
ln -s "${TOOLCHAIN_DIR}" "${CHIPLAB_DIR}/toolchains/${TOOLCHAIN_NAME}"
ln -s "${PICOLIBC_DIR}" "${CHIPLAB_DIR}/toolchains/picolibc"
ln -s "${NEMU_FILE}" "${CHIPLAB_DIR}/toolchains/nemu/la32r-nemu-interpreter-so"
rm -rf "${CHIPLAB_DIR}/IP/myCPU"
mkdir -p "${CHIPLAB_DIR}/IP/myCPU"
install -m 0644 "${core_rtl_support_files[@]}" "${CHIPLAB_DIR}/IP/myCPU/"

RUN_DIR="${CHIPLAB_DIR}/sims/verilator/run_prog"
[[ -x "${RUN_DIR}/configure.sh" ]] || die "Chiplab configure.sh is missing"
[[ -f "${RUN_DIR}/Makefile" ]] || die "Chiplab Verilator Makefile is missing"

mkdir -p "${RUN_DIR}/src/main/resources"
for resource in "${RESOURCE_FILES[@]}"; do
  install -m 0644 "${RESOURCE_DIR}/${resource}" \
    "${RUN_DIR}/src/main/resources/${resource}"
done

python3 - "${RUN_DIR}/Makefile" <<'PY'
import sys
from pathlib import Path

makefile = Path(sys.argv[1])
text = makefile.read_text(encoding="utf-8")
copy_line = "\tcp -a ./src/main/resources/. ./tmp/src/main/resources/;\t\\\n"
if copy_line in text:
    raise SystemExit(0)
mkdir_line = "\tmkdir -p ./tmp/src/main/resources;\t\\\n"
lines = text.splitlines(keepends=True)
patched = []
matches = 0
for line in lines:
    if "make simulation_run_prog -C ./tmp -f ../Makefile_run" in line:
        patched.extend((mkdir_line, copy_line))
        matches += 1
    patched.append(line)
if matches == 0:
    raise SystemExit("could not find Chiplab simulation_run_prog invocation")
makefile.write_text("".join(patched), encoding="utf-8")
PY

run_with_log() {
  local duration="$1"
  local log_file="$2"
  shift 2
  set +e
  timeout --signal=TERM --kill-after=60s "${duration}" "$@" \
    2>&1 | tee "${log_file}"
  local status="${PIPESTATUS[0]}"
  set -e
  return "${status}"
}

FUNCTION_CSV="$(IFS=,; echo "${FUNCTION_TARGETS[*]}")"
BUILD_LOG="${REPORT_DIR}/functional-build.log"
# shellcheck disable=SC2016
if run_with_log 60m "${BUILD_LOG}" bash -c '
  set -euo pipefail
  run_dir="$1"
  targets="$2"
  threads="$3"
  chiplab_dir="$4"
  export CHIPLAB_HOME="${chiplab_dir}"
  cd "${run_dir}"
  ./configure.sh --run "${targets}" --threads "${threads}" \
    --disable-simu-trace --output-nothing
  make compile
  make soft
  IFS=, read -r -a target_list <<<"${targets}"
  for target in "${target_list[@]}"; do
    mkdir -p "log/${target}_log"
    : >"log/${target}_log/golden_trace.txt"
  done
' _ "${RUN_DIR}" "${FUNCTION_CSV}" "${VERILATOR_THREADS}" "${CHIPLAB_DIR}"; then
  build_status=0
else
  build_status=$?
fi

if ((build_status == 0)); then
  for target in "${FUNCTION_TARGETS[@]}"; do
    stem="${target//\//__}"
    log_file="${LOG_DIR}/${stem}.log"
    status_file="${LOG_DIR}/${stem}.status"
    # shellcheck disable=SC2016
    if run_with_log 30m "${log_file}" bash -c '
      set -euo pipefail
      export CHIPLAB_HOME="$3"
      cd "$1"
      make RUN_SOFTWARE="$2" simulation_run_prog
    ' _ "${RUN_DIR}" "${target}" "${CHIPLAB_DIR}"; then
      target_status=0
    else
      target_status=$?
    fi
    echo "${target_status}" >"${status_file}"
  done
else
  for target in "${FUNCTION_TARGETS[@]}"; do
    stem="${target//\//__}"
    {
      echo "functional build failed with status ${build_status}"
      echo "see ${BUILD_LOG}"
    } >"${LOG_DIR}/${stem}.log"
    echo "${build_status}" >"${LOG_DIR}/${stem}.status"
  done
fi

if python3 "${ROOT_DIR}/scripts/ci/parse-functional-results.py" \
  --logs-dir "${LOG_DIR}" \
  --output-json "${REPORT_DIR}/functional-results.json" \
  --output-markdown "${REPORT_DIR}/functional-summary.md"; then
  functional_status=0
else
  functional_status=$?
fi

NEMU_LIBRARY="${CHIPLAB_DIR}/toolchains/nemu/la32r-nemu-interpreter-so"
{
  echo "source_sha=${SOURCE_SHA}"
  echo "chiplab_url=${CHIPLAB_URL}"
  echo "functional_chiplab_ref=${FUNCTION_CHIPLAB_REF}"
  echo "functional_chiplab_sha=${FUNCTION_CHIPLAB_SHA}"
  echo "core_top_file=$(basename "${CORE_TOP_FILE}")"
  echo "core_top_sha256=$(sha256sum "${CORE_TOP_FILE}" | awk '{print $1}')"
  echo "validation_runner_sha256=$(sha256sum "${ROOT_DIR}/scripts/ci/run-verilator-validation.sh" | awk '{print $1}')"
  echo "functional_parser_sha256=$(sha256sum "${ROOT_DIR}/scripts/ci/parse-functional-results.py" | awk '{print $1}')"
  echo "verilator_version=$(verilator --version | head -n 1)"
  echo "compiler_version=$(g++ --version | head -n 1)"
  if [[ -f "${NEMU_LIBRARY}" ]]; then
    echo "nemu_sha256=$(sha256sum "${NEMU_LIBRARY}" | awk '{print $1}')"
  else
    echo "nemu_sha256=missing"
  fi
  echo "functional_targets=${FUNCTION_CSV}"
} >"${REPORT_DIR}/VERIFICATION_INFO.txt"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" && -f "${REPORT_DIR}/functional-summary.md" ]]; then
  cat "${REPORT_DIR}/functional-summary.md" >>"${GITHUB_STEP_SUMMARY}"
fi

if ((functional_status != 0)); then
  echo "error: functional simulation validation failed" >&2
  exit 1
fi

echo "Functional simulation validation passed"
