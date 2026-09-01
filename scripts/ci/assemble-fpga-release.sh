#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PARTS_DIR="${FPGA_PARTS_DIR:-${ROOT_DIR}/build/fpga-parts}"
DIST_DIR="${FPGA_DIST_DIR:-${ROOT_DIR}/dist}"
RELEASE_VERSION="${RELEASE_VERSION:-local}"
SOURCE_SHA="${SOURCE_SHA:-unknown}"
RELEASE_CONFIG="${RELEASE_CONFIG:-${ROOT_DIR}/ci/release-config.json}"
RELEASE_CONFIG_READER="${ROOT_DIR}/scripts/ci/read-release-config.py"
UBOOT_BINARY_URL="${UBOOT_BINARY_URL:-https://gitee.com/loongson-edu/la32r-uboot/releases/download/loongsonsoc-v0.1.0/u-boot.bin}"
UBOOT_BINARY_SHA256="${UBOOT_BINARY_SHA256:-357eb0280e3f493ca608e554421575a64f68b351e16e21d69ea7979882d5f0a6}"
PROGRAMMER_BY_UART_URL="${PROGRAMMER_BY_UART_URL:-https://gitee.com/chenzes/chiplab-tools/releases/download/chiplab-tools/programmer_by_uart.bit}"
PROGRAMMER_BY_UART_SHA256="${PROGRAMMER_BY_UART_SHA256:-f0ac4b8d23887805249f0d0314b182c99d03200269e230d86b42aca485d0e111}"
LINUX_RELEASES_URL="${LINUX_RELEASES_URL:-https://gitee.com/loongson-edu/la32r-Linux/releases}"
TARGETS=(func perf uboot)

die() {
  echo "error: $*" >&2
  exit 1
}

for command in chmod cmp cp find install python3 sha256sum sort xargs zip; do
  command -v "${command}" >/dev/null 2>&1 || \
    die "required command not found: ${command}"
done

config_values="$(python3 "${RELEASE_CONFIG_READER}" \
  --config "${RELEASE_CONFIG}" --shell)" || die "invalid release configuration"
while IFS='=' read -r key value; do
  case "${key}" in
    CHIPLAB_URL|FUNCTION_CHIPLAB_REF|FUNCTION_CHIPLAB_SHA|FPGA_CHIPLAB_REF|FPGA_CHIPLAB_SHA|UBOOT_CHIPLAB_REF|UBOOT_CHIPLAB_SHA|FUNC_REQUESTED_MHZ|PERF_REQUESTED_MHZ|UBOOT_REQUESTED_MHZ)
      printf -v "${key}" '%s' "${value}"
      ;;
  esac
done <<<"${config_values}"

[[ "${RELEASE_VERSION}" =~ ^[A-Za-z0-9._+-]+$ ]] || \
  die "release version contains unsupported characters: ${RELEASE_VERSION}"

for target in "${TARGETS[@]}"; do
  target_dir="${PARTS_DIR}/${target}"
  [[ -d "${target_dir}" ]] || die "FPGA part is missing: ${target_dir}"
  [[ -s "${target_dir}/reports/${target}/timing-metrics.json" ]] || \
    die "${target} timing metrics are missing"
  [[ -s "${target_dir}/reports/${target}/timing-summary.md" ]] || \
    die "${target} timing summary is missing"
  [[ -s "${target_dir}/reports/${target}/build-target-info.txt" ]] || \
    die "${target} build metadata are missing"
done

for rtl_file in core_top.v difftest_wrap.v; do
  cmp -s \
    "${PARTS_DIR}/func/rtl/${rtl_file}" \
    "${PARTS_DIR}/perf/rtl/${rtl_file}" || \
    die "func and perf used different ${rtl_file}"
  cmp -s \
    "${PARTS_DIR}/func/rtl/${rtl_file}" \
    "${PARTS_DIR}/uboot/rtl/${rtl_file}" || \
    die "func and uboot used different ${rtl_file}"
done

mapfile -t build_metadata < <(
  python3 - "${PARTS_DIR}" "${RELEASE_VERSION}" "${SOURCE_SHA}" \
    "${FUNC_REQUESTED_MHZ}" "${PERF_REQUESTED_MHZ}" "${UBOOT_REQUESTED_MHZ}" \
    "${CHIPLAB_URL}" "${FPGA_CHIPLAB_REF}" "${FPGA_CHIPLAB_SHA}" \
    "${UBOOT_CHIPLAB_REF}" "${UBOOT_CHIPLAB_SHA}" <<'PY'
import sys
from pathlib import Path

parts = Path(sys.argv[1])
expected_common = {
    "release_version": sys.argv[2],
    "source_sha": sys.argv[3],
}
requested_mhz = {"func": sys.argv[4], "perf": sys.argv[5], "uboot": sys.argv[6]}
chiplab = {
    "func": {
        "chiplab_url": sys.argv[7],
        "chiplab_ref": sys.argv[8],
        "chiplab_sha": sys.argv[9],
    },
    "perf": {
        "chiplab_url": sys.argv[7],
        "chiplab_ref": sys.argv[8],
        "chiplab_sha": sys.argv[9],
    },
    "uboot": {
        "chiplab_url": sys.argv[7],
        "chiplab_ref": sys.argv[10],
        "chiplab_sha": sys.argv[11],
    },
}
vivado_versions = set()
vivado_runtimes = set()

for target in ("func", "perf", "uboot"):
    path = parts / target / "reports" / target / "build-target-info.txt"
    values = {}
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise SystemExit(f"invalid {path} line {line_number}")
        values[key] = value
    if values.get("target") != target:
        raise SystemExit(f"{path} describes the wrong target")
    for key, wanted in expected_common.items():
        if values.get(key) != wanted:
            raise SystemExit(
                f"{path}: {key}={values.get(key)!r}, expected {wanted!r}"
            )
    if values.get("requested_mhz") != requested_mhz[target]:
        raise SystemExit(
            f"{path}: requested_mhz={values.get('requested_mhz')!r}, "
            f"expected {requested_mhz[target]!r}"
        )
    for key, wanted in chiplab[target].items():
        if values.get(key) != wanted:
            raise SystemExit(
                f"{path}: {key}={values.get(key)!r}, expected {wanted!r}"
            )
    vivado_version = values.get("vivado_version")
    vivado_runtime = values.get("vivado_runtime")
    if not vivado_version or not vivado_runtime:
        raise SystemExit(f"{path}: Vivado version or runtime is missing")
    vivado_versions.add(vivado_version)
    vivado_runtimes.add(vivado_runtime)

if len(vivado_versions) != 1:
    raise SystemExit("FPGA targets were built with different Vivado versions")
if len(vivado_runtimes) != 1:
    raise SystemExit("FPGA targets were built with different Vivado runtimes")
print(next(iter(vivado_versions)))
print(next(iter(vivado_runtimes)))
PY
)
((${#build_metadata[@]} == 2)) || die "failed to resolve common FPGA metadata"
VIVADO_VERSION_TEXT="${build_metadata[0]}"
VIVADO_RUNTIME_TEXT="${build_metadata[1]}"

BUNDLE_NAME="Gemmont-${RELEASE_VERSION}-nscscc"
BUNDLE_DIR="${DIST_DIR}/${BUNDLE_NAME}"
rm -rf "${DIST_DIR}"
mkdir -p "${BUNDLE_DIR}"
for target in "${TARGETS[@]}"; do
  cp -a "${PARTS_DIR}/${target}/." "${BUNDLE_DIR}/"
done

for expected_file in \
  "${BUNDLE_DIR}/bitstream/func/Gemmont-${RELEASE_VERSION}-func.bit" \
  "${BUNDLE_DIR}/bitstream/perf/Gemmont-${RELEASE_VERSION}-perf.bit" \
  "${BUNDLE_DIR}/boot/bitstream/Gemmont-${RELEASE_VERSION}-uboot.bit" \
  "${BUNDLE_DIR}/boot/uboot/u-boot.bin" \
  "${BUNDLE_DIR}/boot/tools/programmer_by_uart.bit" \
  "${BUNDLE_DIR}/boot/tools/openfpgaloader.sh" \
  "${BUNDLE_DIR}/boot/tools/uart-xmodem-ftdi.py" \
  "${BUNDLE_DIR}/ram/func/main.bin" \
  "${BUNDLE_DIR}/ram/perf/allbench/inst_data.bin" \
  "${BUNDLE_DIR}/tools/load_func.tcl" \
  "${BUNDLE_DIR}/tools/load_perf_allbench.tcl" \
  "${BUNDLE_DIR}/rtl/core_top.v" \
  "${BUNDLE_DIR}/rtl/difftest_wrap.v"; do
  [[ -s "${expected_file}" ]] || die "release input is missing: ${expected_file}"
done
chmod 0755 \
  "${BUNDLE_DIR}/boot/tools/openfpgaloader.sh" \
  "${BUNDLE_DIR}/boot/tools/uart-xmodem-ftdi.py"

TIMING_MET="$(python3 - "${BUNDLE_DIR}" "${TARGETS[@]}" <<'PY'
import json
import sys
from pathlib import Path

bundle = Path(sys.argv[1])
timing_met = True
for target in sys.argv[2:]:
    path = bundle / "reports" / target / "timing-metrics.json"
    with path.open(encoding="utf-8") as stream:
        metrics = json.load(stream)
    if metrics.get("variant") != target:
        raise SystemExit(f"{path} describes the wrong target")
    if metrics.get("timing_met") is not True:
        timing_met = False
print(str(timing_met).lower())
PY
)" || die "failed to determine aggregate timing status"
[[ "${TIMING_MET}" == "true" || "${TIMING_MET}" == "false" ]] || \
  die "invalid aggregate timing status: ${TIMING_MET}"

cat >"${BUNDLE_DIR}/BUILD_INFO.txt" <<EOF
release_version=${RELEASE_VERSION}
source_sha=${SOURCE_SHA}
chiplab_url=${CHIPLAB_URL}
functional_chiplab_ref=${FUNCTION_CHIPLAB_REF}
functional_chiplab_sha=${FUNCTION_CHIPLAB_SHA}
fpga_chiplab_ref=${FPGA_CHIPLAB_REF}
fpga_chiplab_sha=${FPGA_CHIPLAB_SHA}
uboot_chiplab_ref=${UBOOT_CHIPLAB_REF}
uboot_chiplab_sha=${UBOOT_CHIPLAB_SHA}
vivado_runtime=${VIVADO_RUNTIME_TEXT}
vivado_version=${VIVADO_VERSION_TEXT}
fpga_part=xc7a200tfbg676-2
fpga_targets=func perf uboot
func_requested_mhz=${FUNC_REQUESTED_MHZ}
perf_requested_mhz=${PERF_REQUESTED_MHZ}
uboot_requested_mhz=${UBOOT_REQUESTED_MHZ}
timing_met=${TIMING_MET}
uboot_binary_url=${UBOOT_BINARY_URL}
uboot_binary_sha256=${UBOOT_BINARY_SHA256}
programmer_by_uart_url=${PROGRAMMER_BY_UART_URL}
programmer_by_uart_sha256=${PROGRAMMER_BY_UART_SHA256}
linux_releases_url=${LINUX_RELEASES_URL}
build_time_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

cat >"${BUNDLE_DIR}/README.md" <<EOF
# Gemmont ${RELEASE_VERSION} FPGA bundle

This bundle targets the NSCSCC/Loongson competition board
(\`xc7a200tfbg676-2\`) and combines three independently built Vivado targets.

- \`bitstream/func/\`: function-test bitstream.
- \`bitstream/perf/\`: perf-target bitstream.
- \`boot/\`: U-Boot/Linux-capable SoC bitstream and openFPGALoader materials.
- \`ram/\`: function and allbench RAM images.
- \`tools/\`: JTAG Tcl loaders for the packaged RAM images.
- \`reports/\`: implementation and timing reports for all targets.
- \`rtl/\`: the exact generated RTL shared by all three builders.
- \`BUILD_INFO.txt\`: pinned inputs and aggregate timing status.

After programming the corresponding bitstream, source the matching loader by
absolute path:

\`\`\`tcl
source /path/to/${BUNDLE_NAME}/tools/load_func.tcl
source /path/to/${BUNDLE_NAME}/tools/load_perf_allbench.tcl
\`\`\`

For a Vivado-free U-Boot bring-up flow, see \`boot/README.md\`:

\`\`\`bash
./boot/tools/openfpgaloader.sh flash-uboot
./boot/tools/openfpgaloader.sh sram
\`\`\`
EOF

(
  cd "${BUNDLE_DIR}"
  checksum_file="${BUNDLE_DIR}/SHA256SUMS.tmp"
  find . -type f ! -name SHA256SUMS -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum >"${checksum_file}"
  mv "${checksum_file}" "${BUNDLE_DIR}/SHA256SUMS"
)

ARCHIVE_NAME="${BUNDLE_NAME}.zip"
(
  cd "${DIST_DIR}"
  zip -q -r "${ARCHIVE_NAME}" "${BUNDLE_NAME}"
  sha256sum "${ARCHIVE_NAME}" >"${ARCHIVE_NAME}.sha256"
)

echo "Aggregate timing status: ${TIMING_MET}"
echo "Release bundle: ${DIST_DIR}/${ARCHIVE_NAME}"
echo "Checksum:       ${DIST_DIR}/${ARCHIVE_NAME}.sha256"
