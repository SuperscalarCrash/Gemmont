#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/fpga-release"
CHIPLAB_SOURCE_DIR="${BUILD_DIR}/chiplab-source"
WORK_ROOT="${BUILD_DIR}/work"
STAGE_ROOT="${BUILD_DIR}/stage"
LOG_ROOT="${BUILD_DIR}/logs"

RELEASE_VERSION="${RELEASE_VERSION:-local}"
SOURCE_SHA="${SOURCE_SHA:-unknown}"
CHIPLAB_URL=""
CHIPLAB_REF=""
CHIPLAB_SHA=""
FPGA_BUILD_TARGET="${FPGA_BUILD_TARGET:-}"
FUNC_REQUESTED_MHZ=""
PERF_REQUESTED_MHZ=""
UBOOT_REQUESTED_MHZ=""
UBOOT_BINARY_URL="${UBOOT_BINARY_URL:-https://gitee.com/loongson-edu/la32r-uboot/releases/download/loongsonsoc-v0.1.0/u-boot.bin}"
UBOOT_BINARY_SHA256="${UBOOT_BINARY_SHA256:-357eb0280e3f493ca608e554421575a64f68b351e16e21d69ea7979882d5f0a6}"
PROGRAMMER_BY_UART_URL="${PROGRAMMER_BY_UART_URL:-https://gitee.com/chenzes/chiplab-tools/releases/download/chiplab-tools/programmer_by_uart.bit}"
PROGRAMMER_BY_UART_SHA256="${PROGRAMMER_BY_UART_SHA256:-f0ac4b8d23887805249f0d0314b182c99d03200269e230d86b42aca485d0e111}"
LINUX_RELEASES_URL="${LINUX_RELEASES_URL:-https://gitee.com/loongson-edu/la32r-Linux/releases}"
VIVADO_JOBS="${VIVADO_JOBS:-1}"
VIVADO_RUN_TIMEOUT_MINUTES="${VIVADO_RUN_TIMEOUT_MINUTES:-240}"
VIVADO_IMAGE="${VIVADO_IMAGE:-}"
VIVADO_RUNTIME="host"
RELEASE_CONFIG="${RELEASE_CONFIG:-${ROOT_DIR}/ci/release-config.json}"
RELEASE_CONFIG_READER="${ROOT_DIR}/scripts/ci/read-release-config.py"
READMEM_RESOURCE_DIR="${ROOT_DIR}/src/main/resources"
READMEM_RESOURCE_FILES=(
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

die() {
  echo "error: $*" >&2
  exit 1
}

install_public_text() {
  local source="$1"
  local destination="$2"

  python3 - "${source}" "${destination}" <<'PY'
import re
import sys
from pathlib import Path

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
data = source.read_bytes()

# Vivado can copy the absolute home directory recorded in an upstream XPR
# into reports.  Keep the reports useful while removing host-specific paths
# from the public bundle.
data = re.sub(
    rb"/(?:Users|home)/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._+@%=-]+)*",
    b"<redacted-path>",
    data,
)
destination.write_bytes(data)
PY
  chmod 0644 "${destination}"
}

for command in cp curl git vivado zip sha256sum find install python3; do
  command -v "${command}" >/dev/null 2>&1 || die "required command not found: ${command}"
done

config_values="$(python3 "${RELEASE_CONFIG_READER}" \
  --config "${RELEASE_CONFIG}" --shell)" || die "invalid release configuration"
while IFS='=' read -r key value; do
  case "${key}" in
    CHIPLAB_URL|FPGA_CHIPLAB_REF|FPGA_CHIPLAB_SHA|UBOOT_CHIPLAB_REF|UBOOT_CHIPLAB_SHA|FUNC_REQUESTED_MHZ|PERF_REQUESTED_MHZ|UBOOT_REQUESTED_MHZ)
      printf -v "${key}" '%s' "${value}"
      ;;
  esac
done <<<"${config_values}"

case "${FPGA_BUILD_TARGET}" in
  uboot)
    CHIPLAB_REF="${UBOOT_CHIPLAB_REF}"
    CHIPLAB_SHA="${UBOOT_CHIPLAB_SHA}"
    ;;
  func|perf|"")
    CHIPLAB_REF="${FPGA_CHIPLAB_REF}"
    CHIPLAB_SHA="${FPGA_CHIPLAB_SHA}"
    ;;
esac
if [[ -n "${VIVADO_IMAGE}" ]]; then
  VIVADO_RUNTIME="container:${VIVADO_IMAGE}"
fi

[[ "${RELEASE_VERSION}" =~ ^[A-Za-z0-9._+-]+$ ]] || \
  die "release version contains unsupported characters: ${RELEASE_VERSION}"
[[ "${VIVADO_JOBS}" =~ ^[1-9][0-9]*$ ]] || \
  die "VIVADO_JOBS must be a positive integer"
[[ "${VIVADO_RUN_TIMEOUT_MINUTES}" =~ ^[1-9][0-9]*$ ]] || \
  die "VIVADO_RUN_TIMEOUT_MINUTES must be a positive integer"
[[ "${CHIPLAB_SHA}" =~ ^[0-9a-f]{40}$ ]] || \
  die "CHIPLAB_SHA must be the resolved 40-character Chiplab commit"
[[ -s "${ROOT_DIR}/core_top.v" ]] || \
  die "generated core_top.v is missing; run ./scripts/generate.sh first"
[[ -s "${ROOT_DIR}/difftest_wrap.v" ]] || die "difftest_wrap.v is missing or empty"
grep -q '^module core_top' "${ROOT_DIR}/core_top.v" || die "core_top.v does not define core_top"
for readmem_file in "${READMEM_RESOURCE_FILES[@]}"; do
  [[ -s "${READMEM_RESOURCE_DIR}/${readmem_file}" ]] || \
    die "readmem resource is missing or empty: ${READMEM_RESOURCE_DIR}/${readmem_file}"
done

[[ "${FPGA_BUILD_TARGET}" == "func" ||
   "${FPGA_BUILD_TARGET}" == "perf" ||
   "${FPGA_BUILD_TARGET}" == "uboot" ]] || \
  die "FPGA_BUILD_TARGET must be func, perf, or uboot"
build_targets=("${FPGA_BUILD_TARGET}")

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}" "${WORK_ROOT}" "${STAGE_ROOT}" "${LOG_ROOT}"
mkdir -p "${CHIPLAB_SOURCE_DIR}"

echo "Fetching Chiplab ${CHIPLAB_SHA} (${CHIPLAB_REF}) from ${CHIPLAB_URL}"
git -C "${CHIPLAB_SOURCE_DIR}" init -q
git -C "${CHIPLAB_SOURCE_DIR}" remote add origin "${CHIPLAB_URL}"
git -C "${CHIPLAB_SOURCE_DIR}" fetch -q --depth 1 origin "${CHIPLAB_SHA}"
git -C "${CHIPLAB_SOURCE_DIR}" checkout -q --detach FETCH_HEAD
[[ "$(git -C "${CHIPLAB_SOURCE_DIR}" rev-parse HEAD)" == "${CHIPLAB_SHA}" ]] || \
  die "checked-out Chiplab commit does not match CHIPLAB_SHA"

# Chiplab models the CPU as a submodule.  The release build intentionally does
# not initialise that submodule; it replaces the slot with this tagged RTL.
rm -rf "${CHIPLAB_SOURCE_DIR}/IP/myCPU"
mkdir -p "${CHIPLAB_SOURCE_DIR}/IP/myCPU"
install -m 0644 "${ROOT_DIR}/core_top.v" "${CHIPLAB_SOURCE_DIR}/IP/myCPU/core_top.v"
install -m 0644 "${ROOT_DIR}/difftest_wrap.v" "${CHIPLAB_SOURCE_DIR}/IP/myCPU/difftest_wrap.v"

NSCSCC_RUN_DIR_SOURCE="${CHIPLAB_SOURCE_DIR}/fpga/nscscc-team/run_vivado"
NSCSCC_SOC_CONFIG_SOURCE="${CHIPLAB_SOURCE_DIR}/chip/soc_demo/nscscc-team/soc_config.vh"
UBOOT_RUN_DIR_SOURCE="${CHIPLAB_SOURCE_DIR}/fpga/loongson/2023.2"
UBOOT_PROJECT_SOURCE="${UBOOT_RUN_DIR_SOURCE}/system_run.xpr"
UBOOT_SOC_TOP_SOURCE="${CHIPLAB_SOURCE_DIR}/chip/soc_demo/loongson/soc_top.v"
need_nscscc=false
need_uboot=false
for target in "${build_targets[@]}"; do
  case "${target}" in
    func|perf)
      need_nscscc=true
      ;;
    uboot)
      need_uboot=true
      ;;
  esac
done
if [[ "${need_nscscc}" == "true" ]]; then
  [[ -f "${NSCSCC_RUN_DIR_SOURCE}/create_project.tcl" ]] || \
    die "Chiplab FPGA project scripts are missing"
  [[ -f "${NSCSCC_SOC_CONFIG_SOURCE}" ]] || die "Chiplab soc_config.vh is missing"
  cp "${NSCSCC_SOC_CONFIG_SOURCE}" "${BUILD_DIR}/soc_config.vh.base"
fi
if [[ "${need_uboot}" == "true" ]]; then
  [[ -f "${UBOOT_PROJECT_SOURCE}" ]] || die "Chiplab U-Boot FPGA project is missing"
  [[ -f "${UBOOT_SOC_TOP_SOURCE}" ]] || die "Chiplab U-Boot SoC top is missing"
fi

install_readmem_resources() {
  local target_root="$1"
  local target_dir="${target_root}/src/main/resources"

  mkdir -p "${target_dir}"
  for readmem_file in "${READMEM_RESOURCE_FILES[@]}"; do
    install -m 0644 \
      "${READMEM_RESOURCE_DIR}/${readmem_file}" \
      "${target_dir}/${readmem_file}"
  done
}

configure_variant() {
  local chiplab_dir="$1"
  local variant="$2"
  local soc_config="${chiplab_dir}/chip/soc_demo/nscscc-team/soc_config.vh"

  cp "${BUILD_DIR}/soc_config.vh.base" "${soc_config}"

  if [[ "${variant}" == "func" ]]; then
    # shellcheck disable=SC2016
    sed -i -E \
      -e 's|^[[:space:]]*(//[[:space:]]*)?`define RUN_FUNC_TEST.*$|`define RUN_FUNC_TEST|' \
      -e 's|^[[:space:]]*(//[[:space:]]*)?`define RUN_PERF_TEST.*$|// `define RUN_PERF_TEST|' \
      "${soc_config}"
  else
    # shellcheck disable=SC2016
    sed -i -E \
      -e 's|^[[:space:]]*(//[[:space:]]*)?`define RUN_FUNC_TEST.*$|// `define RUN_FUNC_TEST|' \
      -e 's|^[[:space:]]*(//[[:space:]]*)?`define RUN_PERF_TEST.*$|`define RUN_PERF_TEST|' \
      "${soc_config}"
  fi

  local active_macros
  active_macros="$(grep -Ec '^[[:space:]]*`define RUN_(FUNC|PERF)_TEST' "${soc_config}")"
  [[ "${active_macros}" == "1" ]] || die "failed to select the ${variant} SoC configuration"
}

wait_for_implementation() {
  local variant="$1"
  local runs_dir="$2"
  local impl_dir="${runs_dir}/impl_1"
  local deadline=$((SECONDS + VIVADO_RUN_TIMEOUT_MINUTES * 60))
  local next_update=${SECONDS}
  local failed_dir
  local failed_markers=()

  while ((SECONDS < deadline)); do
    if [[ -f "${impl_dir}/.vivado.end.rst" ]]; then
      echo "Vivado ${variant} implementation completed"
      return 0
    fi

    mapfile -d '' failed_markers < <(find "${runs_dir}" -mindepth 2 -maxdepth 2 \
      -type f -name '.vivado.error.rst' -print0)
    if ((${#failed_markers[@]} > 0)); then
      echo "Vivado ${variant} implementation failed" >&2
      for failed_marker in "${failed_markers[@]}"; do
        failed_dir="$(dirname "${failed_marker}")"
        echo "Failed run: $(basename "${failed_dir}")" >&2
        tail -n 80 "${failed_dir}/runme.log" >&2 || true
      done
      return 1
    fi

    if ((SECONDS >= next_update)); then
      echo "Waiting for Vivado ${variant} implementation..."
      next_update=$((SECONDS + 60))
    fi
    sleep 15
  done

  echo "Vivado ${variant} implementation timed out after ${VIVADO_RUN_TIMEOUT_MINUTES} minutes" >&2
  return 1
}

verify_core_top_systemverilog() {
  local runs_dir="$1"

  python3 - "${runs_dir}" <<'PY'
import re
import sys
from pathlib import Path

runs_dir = Path(sys.argv[1])
scripts = sorted(runs_dir.glob("synth_1/*.tcl"))
if not scripts:
    raise SystemExit(f"no generated synthesis Tcl found under {runs_dir}")
pattern = re.compile(r"read_verilog\b[^\n]*\s-sv\b[^\n]*core_top\.v")
for script in scripts:
    text = script.read_text(encoding="utf-8", errors="replace").replace("\\\n", " ")
    if pattern.search(text):
        print(f"Verified SystemVerilog core_top.v in {script}")
        break
else:
    raise SystemExit("generated synthesis Tcl does not read core_top.v with -sv")
PY
}

collect_results() {
  local chiplab_dir="$1"
  local variant="$2"
  local stage_dir="$3"
  local run_dir="${chiplab_dir}/fpga/nscscc-team/run_vivado"
  local soc_config="${chiplab_dir}/chip/soc_demo/nscscc-team/soc_config.vh"
  local impl_dir="${run_dir}/project/loongson.runs/impl_1"
  local variant_bit_dir="${stage_dir}/bitstream/${variant}"
  local variant_report_dir="${stage_dir}/reports/${variant}"
  local bit_files=()

  mapfile -t bit_files < <(find "${impl_dir}" -maxdepth 1 -type f -name '*.bit' -print | sort)
  ((${#bit_files[@]} == 1)) || \
    die "expected exactly one bitstream for ${variant}, found ${#bit_files[@]}"
  [[ -s "${impl_dir}/timing_summary.rpt" ]] || \
    die "timing summary is missing for ${variant}"

  mkdir -p "${variant_bit_dir}" "${variant_report_dir}"
  install -m 0644 "${bit_files[0]}" "${variant_bit_dir}/Gemmont-${RELEASE_VERSION}-${variant}.bit"
  install -m 0644 "${soc_config}" "${variant_bit_dir}/soc_config.vh"

  while IFS= read -r -d '' file; do
    install_public_text "${file}" "${variant_report_dir}/$(basename "${file}")"
  done < <(find "${impl_dir}" -maxdepth 1 -type f \
    \( -name '*.rpt' -o -name '*.log' -o -name '*.ltx' \) -print0)

  for file in \
    "${run_dir}/vivado-create-${variant}.log" \
    "${run_dir}/vivado-build-${variant}.log" \
    "${run_dir}/clock-config-${variant}.txt"; do
    [[ -f "${file}" ]] && \
      install_public_text "${file}" "${variant_report_dir}/$(basename "${file}")"
  done

  python3 "${ROOT_DIR}/scripts/ci/parse-vivado-timing.py" \
    --variant "${variant}" \
    --report "${impl_dir}/timing_summary.rpt" \
    --output-json "${variant_report_dir}/timing-metrics.json" \
    --output-markdown "${variant_report_dir}/timing-summary.md"
}

download_verified_file() {
  local url="$1"
  local output="$2"
  local expected_sha256="$3"
  local tmp_file="${output}.tmp"

  mkdir -p "$(dirname "${output}")"
  curl --fail --location --silent --show-error --retry 3 \
    "${url}" --output "${tmp_file}"
  printf '%s  %s\n' "${expected_sha256}" "${tmp_file}" \
    | sha256sum --check --strict
  install -m 0644 "${tmp_file}" "${output}"
  rm -f "${tmp_file}"
}

generate_openfpgaloader_helper() {
  local stage_dir="$1"
  local helper="${stage_dir}/boot/tools/openfpgaloader.sh"
  local uart_helper="${stage_dir}/boot/tools/uart-xmodem-ftdi.py"
  local bitstream_name="Gemmont-${RELEASE_VERSION}-uboot.bit"

  cat >"${uart_helper}" <<'PYEOF'
#!/usr/bin/env python3
import argparse
import ctypes
import ctypes.util
import sys
import time

SOH = 0x01
EOT = 0x04
ACK = 0x06
NAK = 0x15
CAN = 0x18
CRCCHR = ord("C")


def parse_int(text):
    try:
        return int(text, 0)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"invalid integer: {text}") from exc


def crc16_ccitt(block):
    crc = 0
    for byte in block:
        crc ^= byte << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


class FtdiUart:
    def __init__(self, vendor, product, serial, baud, raise_modem):
        lib_path = ctypes.util.find_library("ftdi1") or "libftdi1.so.2"
        try:
            self.lib = ctypes.CDLL(lib_path)
        except OSError as exc:
            raise RuntimeError(
                "failed to load libftdi1; install libftdi1 and run with USB access"
            ) from exc

        self.lib.ftdi_new.restype = ctypes.c_void_p
        signatures = {
            "ftdi_usb_open_desc": (
                [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p],
                ctypes.c_int,
            ),
            "ftdi_get_error_string": ([ctypes.c_void_p], ctypes.c_char_p),
            "ftdi_set_baudrate": ([ctypes.c_void_p, ctypes.c_int], ctypes.c_int),
            "ftdi_set_line_property": (
                [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int],
                ctypes.c_int,
            ),
            "ftdi_setflowctrl": ([ctypes.c_void_p, ctypes.c_int], ctypes.c_int),
            "ftdi_set_latency_timer": ([ctypes.c_void_p, ctypes.c_ubyte], ctypes.c_int),
            "ftdi_usb_purge_buffers": ([ctypes.c_void_p], ctypes.c_int),
            "ftdi_read_data": (
                [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte), ctypes.c_int],
                ctypes.c_int,
            ),
            "ftdi_write_data": (
                [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte), ctypes.c_int],
                ctypes.c_int,
            ),
            "ftdi_usb_close": ([ctypes.c_void_p], ctypes.c_int),
            "ftdi_write_data_set_chunksize": ([ctypes.c_void_p, ctypes.c_uint], ctypes.c_int),
            "ftdi_read_data_set_chunksize": ([ctypes.c_void_p, ctypes.c_uint], ctypes.c_int),
            "ftdi_setdtr": ([ctypes.c_void_p, ctypes.c_int], ctypes.c_int),
            "ftdi_setrts": ([ctypes.c_void_p, ctypes.c_int], ctypes.c_int),
        }
        for name, (argtypes, restype) in signatures.items():
            if hasattr(self.lib, name):
                func = getattr(self.lib, name)
                func.argtypes = argtypes
                func.restype = restype
        self.lib.ftdi_free.argtypes = [ctypes.c_void_p]

        self.ctx = self.lib.ftdi_new()
        if not self.ctx:
            raise RuntimeError("ftdi_new failed")
        self.rx = bytearray()

        serial_bytes = serial.encode("ascii") if serial else None
        self.check(
            self.lib.ftdi_usb_open_desc(self.ctx, vendor, product, None, serial_bytes),
            f"open FTDI UART {vendor:#06x}:{product:#06x}"
            + (f" serial={serial}" if serial else ""),
        )
        self.check(self.lib.ftdi_set_baudrate(self.ctx, baud), f"set baud {baud}")
        self.check(self.lib.ftdi_set_line_property(self.ctx, 8, 0, 0), "set 8N1")
        self.check(self.lib.ftdi_setflowctrl(self.ctx, 0), "disable flow control")
        if hasattr(self.lib, "ftdi_write_data_set_chunksize"):
            self.lib.ftdi_write_data_set_chunksize(self.ctx, 4096)
        if hasattr(self.lib, "ftdi_read_data_set_chunksize"):
            self.lib.ftdi_read_data_set_chunksize(self.ctx, 4096)
        if hasattr(self.lib, "ftdi_set_latency_timer"):
            self.lib.ftdi_set_latency_timer(self.ctx, 1)
        if raise_modem:
            if hasattr(self.lib, "ftdi_setdtr"):
                self.check(self.lib.ftdi_setdtr(self.ctx, 1), "raise DTR")
            if hasattr(self.lib, "ftdi_setrts"):
                self.check(self.lib.ftdi_setrts(self.ctx, 1), "raise RTS")
        self.check(self.lib.ftdi_usb_purge_buffers(self.ctx), "purge FTDI buffers")

    def error_string(self):
        message = self.lib.ftdi_get_error_string(self.ctx)
        return message.decode(errors="replace") if message else "<no libftdi error>"

    def check(self, rc, operation):
        if rc < 0:
            raise RuntimeError(f"{operation} failed: {rc}: {self.error_string()}")

    def close(self):
        if self.ctx:
            try:
                self.lib.ftdi_usb_close(self.ctx)
            finally:
                self.lib.ftdi_free(self.ctx)
                self.ctx = None

    def write(self, data):
        offset = 0
        while offset < len(data):
            chunk = data[offset : offset + 4096]
            buf = (ctypes.c_ubyte * len(chunk)).from_buffer_copy(chunk)
            written = self.lib.ftdi_write_data(self.ctx, buf, len(chunk))
            if written < 0:
                raise RuntimeError(f"FTDI write failed: {written}: {self.error_string()}")
            if written == 0:
                time.sleep(0.01)
            offset += written

    def read_some(self):
        buf = (ctypes.c_ubyte * 4096)()
        count = self.lib.ftdi_read_data(self.ctx, buf, 4096)
        if count < 0:
            raise RuntimeError(f"FTDI read failed: {count}: {self.error_string()}")
        if count > 0:
            self.rx.extend(bytes(buf[:count]))
        return count

    def wait_for(self, candidates, timeout, echo=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.rx:
                self.read_some()
                if not self.rx:
                    time.sleep(0.01)
                    continue
            byte = self.rx.pop(0)
            if echo:
                if byte in (10, 13) or 32 <= byte < 127:
                    sys.stdout.write(chr(byte))
                else:
                    sys.stdout.write(f"<{byte:02x}>")
                sys.stdout.flush()
            if byte == CAN:
                raise RuntimeError("receiver cancelled transfer")
            if byte in candidates:
                return byte
        buffered = bytes(self.rx[:80]).hex()
        raise TimeoutError(f"timeout waiting for {sorted(candidates)}; buffered={buffered}")


def send_xmodem(uart, payload, ack_timeout):
    print("Sending x to enter XMODEM receive mode...", flush=True)
    uart.write(b"x")
    handshake = uart.wait_for({CRCCHR, NAK}, 15, echo=True)
    use_crc = handshake == CRCCHR
    print(f"\nReceiver handshake: {'C' if use_crc else hex(handshake)}; crc={use_crc}", flush=True)

    total_blocks = (len(payload) + 127) // 128
    start = time.time()
    block_no = 1
    position = 0
    retries = 0
    next_progress = 64 * 1024

    while position < len(payload):
        block = payload[position : position + 128]
        if len(block) < 128:
            block += bytes([0x1A]) * (128 - len(block))
        packet = bytes([SOH, block_no & 0xFF, 0xFF - (block_no & 0xFF)]) + block
        if use_crc:
            crc = crc16_ccitt(block)
            packet += bytes([(crc >> 8) & 0xFF, crc & 0xFF])
        else:
            packet += bytes([sum(block) & 0xFF])

        for _ in range(10):
            uart.write(packet)
            try:
                response = uart.wait_for({ACK, NAK, CAN}, ack_timeout)
            except TimeoutError:
                response = NAK
            if response == ACK:
                break
            if response == CAN:
                raise RuntimeError("receiver cancelled transfer")
            retries += 1
        else:
            raise RuntimeError(f"block {block_no} failed after retries")

        position += 128
        done = min(position, len(payload))
        if done >= next_progress or done == len(payload):
            print(f"sent {done}/{len(payload)} bytes ({done * 100 // len(payload)}%)", flush=True)
            next_progress += 64 * 1024
        block_no = (block_no + 1) & 0xFF

    for attempt in range(10):
        uart.write(bytes([EOT]))
        try:
            response = uart.wait_for({ACK, NAK, CAN}, 15)
        except TimeoutError:
            response = NAK
        if response == ACK:
            elapsed = time.time() - start
            rate = len(payload) / elapsed if elapsed else 0.0
            print(
                f"XMODEM transfer complete; blocks={total_blocks}; retries={retries}; "
                f"elapsed={elapsed:.1f}s; rate={rate:.0f} B/s",
                flush=True,
            )
            return
        if response == CAN:
            raise RuntimeError("receiver cancelled transfer")
        print(f"retry EOT {attempt + 1}", flush=True)
    raise RuntimeError("EOT was not acknowledged")


def main():
    parser = argparse.ArgumentParser(
        description="Send u-boot.bin to Chiplab programmer_by_uart.bit via FTDI/libftdi XMODEM."
    )
    parser.add_argument("--file", required=True, help="binary to send")
    parser.add_argument("--vendor", type=parse_int, default=0x0403, help="FTDI USB vendor id")
    parser.add_argument("--product", type=parse_int, default=0x6001, help="FTDI USB product id")
    parser.add_argument("--serial", default="", help="optional FTDI serial number")
    parser.add_argument("--baud", type=int, default=230400, help="UART programmer baud rate")
    parser.add_argument("--ack-timeout", type=float, default=10.0, help="per-block ACK timeout")
    parser.add_argument("--post-timeout", type=float, default=5.0, help="final message capture timeout")
    parser.add_argument("--no-raise-modem", action="store_true", help="do not raise DTR/RTS")
    args = parser.parse_args()

    with open(args.file, "rb") as stream:
        payload = stream.read()
    if not payload:
        raise RuntimeError(f"input file is empty: {args.file}")
    print(f"XMODEM source: {args.file}, {len(payload)} bytes", flush=True)

    uart = FtdiUart(
        args.vendor,
        args.product,
        args.serial,
        args.baud,
        raise_modem=not args.no_raise_modem,
    )
    try:
        time.sleep(0.3)
        send_xmodem(uart, payload, args.ack_timeout)
        deadline = time.time() + args.post_timeout
        final = bytearray()
        while time.time() < deadline:
            uart.read_some()
            if uart.rx:
                final.extend(uart.rx)
                uart.rx.clear()
            time.sleep(0.05)
        if final:
            print("Final programmer output:")
            print(final.decode("ascii", "replace"), flush=True)
    finally:
        uart.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
PYEOF
  chmod 0755 "${uart_helper}"

  cat >"${helper}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

bundle_root="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/../.." && pwd)"
cable="\${OPENFPGALOADER_CABLE:-ft232}"
part="\${OPENFPGALOADER_PART:-xc7a200tfbg676}"
freq="\${OPENFPGALOADER_FREQ:-}"
common_args=(-c "\${cable}" --fpga-part "\${part}")
if [[ -n "\${freq}" ]]; then
  common_args+=(--freq "\${freq}")
fi
uart_ftdi_vendor="\${UART_FTDI_VENDOR:-0x0403}"
uart_ftdi_product="\${UART_FTDI_PRODUCT:-0x6001}"
uart_ftdi_serial="\${UART_FTDI_SERIAL:-}"
uart_programmer_baud="\${UART_PROGRAMMER_BAUD:-230400}"
uart_helper="\${bundle_root}/boot/tools/uart-xmodem-ftdi.py"
uboot_bin="\${bundle_root}/boot/uboot/u-boot.bin"
uboot_bit="\${bundle_root}/boot/bitstream/${bitstream_name}"

usage() {
  cat <<'USAGE'
Usage: openfpgaloader.sh <command>

Commands:
  scan-usb      List attached probes.
  detect        Detect the FPGA on the selected JTAG cable.
  programmer    Load the UART flash programmer bitstream into FPGA SRAM.
  flash-uboot   Load the UART programmer, then write u-boot.bin with FTDI XMODEM.
  boot          Write u-boot.bin with FTDI XMODEM, then load the SoC bitstream to SRAM.
  sram          Load the U-Boot-capable SoC bitstream into FPGA SRAM.
  flash-fpga    Write the U-Boot-capable SoC bitstream to FPGA config flash.

Environment:
  OPENFPGALOADER_CABLE        defaults to ft232
  OPENFPGALOADER_PART         defaults to xc7a200tfbg676
  OPENFPGALOADER_FREQ         optionally sets JTAG frequency in Hz
  UART_FTDI_VENDOR            defaults to 0x0403
  UART_FTDI_PRODUCT           defaults to 0x6001
  UART_FTDI_SERIAL            optional FTDI UART serial, useful with multiple adapters
  UART_PROGRAMMER_BAUD        defaults to 230400
USAGE
}

load_programmer() {
  openFPGALoader "\${common_args[@]}" \
    "\${bundle_root}/boot/tools/programmer_by_uart.bit"
}

flash_uboot() {
  load_programmer
  uart_args=(
    --file "\${uboot_bin}"
    --vendor "\${uart_ftdi_vendor}"
    --product "\${uart_ftdi_product}"
    --baud "\${uart_programmer_baud}"
  )
  if [[ -n "\${uart_ftdi_serial}" ]]; then
    uart_args+=(--serial "\${uart_ftdi_serial}")
  fi
  python3 "\${uart_helper}" "\${uart_args[@]}"
}

load_soc_sram() {
  openFPGALoader "\${common_args[@]}" "\${uboot_bit}"
}

case "\${1:-sram}" in
  scan-usb)
    exec openFPGALoader --scan-usb
    ;;
  detect)
    exec openFPGALoader "\${common_args[@]}" --detect
    ;;
  programmer)
    load_programmer
    ;;
  flash-uboot)
    flash_uboot
    ;;
  boot)
    flash_uboot
    load_soc_sram
    ;;
  sram|program-fpga)
    load_soc_sram
    ;;
  flash-fpga)
    exec openFPGALoader "\${common_args[@]}" -f --verify "\${uboot_bit}"
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
EOF
  chmod 0755 "${helper}"
}

prepare_chiplab_workspace() {
  local target="$1"
  local chiplab_dir="$2"

  echo "Preparing isolated Chiplab workspace for ${target}"
  rm -rf "${chiplab_dir}"
  mkdir -p "$(dirname "${chiplab_dir}")"
  cp -a "${CHIPLAB_SOURCE_DIR}/." "${chiplab_dir}/"
}

build_uboot_boot_materials() {
  local variant="uboot"
  local chiplab_dir="${WORK_ROOT}/${variant}/chiplab"
  local stage_dir="${STAGE_ROOT}/${variant}"
  local uboot_run_dir="${chiplab_dir}/fpga/loongson/2023.2"
  local uboot_soc_top="${chiplab_dir}/chip/soc_demo/loongson/soc_top.v"
  local runs_dir="${uboot_run_dir}/system_run.runs"
  local impl_dir="${runs_dir}/impl_1"
  local report_dir="${stage_dir}/reports/${variant}"
  local bit_dir="${stage_dir}/boot/bitstream"
  local bit_files=()

  prepare_chiplab_workspace "${variant}" "${chiplab_dir}"
  install_readmem_resources "${uboot_run_dir}"
  echo "Building U-Boot-capable Loongson bitstream with Vivado (${VIVADO_JOBS} jobs)"
  rm -rf \
    "${uboot_run_dir}/.Xil" \
    "${uboot_run_dir}/system_run.cache" \
    "${uboot_run_dir}/system_run.gen" \
    "${uboot_run_dir}/system_run.hw" \
    "${uboot_run_dir}/system_run.ip_user_files" \
    "${uboot_run_dir}/system_run.runs"

  (
    cd "${uboot_run_dir}"
    rm -f "clock-config-${variant}.txt"
    vivado -mode batch -notrace -source "${ROOT_DIR}/scripts/ci/vivado-configure-uboot-clock.tcl" \
      -tclargs "${UBOOT_REQUESTED_MHZ}" "clock-config-${variant}.txt" \
      2>&1 | tee "vivado-configure-${variant}.log"
    [[ -s "clock-config-${variant}.txt" ]] || \
      die "Vivado did not produce a valid ${variant} clock configuration"
    vivado -mode batch -notrace -source "${ROOT_DIR}/scripts/ci/vivado-build.tcl" \
      -tclargs "${VIVADO_JOBS}" "system_run.xpr" \
      "${chiplab_dir}/IP/myCPU/core_top.v" \
      "${chiplab_dir}/IP/myCPU/difftest_wrap.v" \
      2>&1 | tee "vivado-build-${variant}.log"
    wait_for_implementation "${variant}" "${runs_dir}" \
      2>&1 | tee -a "vivado-build-${variant}.log"
    verify_core_top_systemverilog "${runs_dir}" \
      2>&1 | tee -a "vivado-build-${variant}.log"
    vivado -mode batch -notrace -source "${ROOT_DIR}/scripts/ci/vivado-report.tcl" \
      -tclargs "system_run.xpr" "system_run.runs/impl_1" \
      2>&1 | tee -a "vivado-build-${variant}.log"
  )

  mapfile -t bit_files < <(find "${impl_dir}" -maxdepth 1 -type f -name '*.bit' -print | sort)
  ((${#bit_files[@]} == 1)) || \
    die "expected exactly one U-Boot bitstream, found ${#bit_files[@]}"
  [[ -s "${impl_dir}/timing_summary.rpt" ]] || \
    die "timing summary is missing for U-Boot bitstream"

  mkdir -p "${bit_dir}" "${report_dir}"
  install -m 0644 "${bit_files[0]}" "${bit_dir}/Gemmont-${RELEASE_VERSION}-${variant}.bit"
  install -m 0644 "${uboot_soc_top}" "${bit_dir}/loongson-soc_top.v"
  install -m 0644 "${chiplab_dir}/chip/soc_demo/loongson/config.h" \
    "${bit_dir}/loongson-config.h"

  while IFS= read -r -d '' file; do
    install_public_text "${file}" "${report_dir}/$(basename "${file}")"
  done < <(find "${impl_dir}" -maxdepth 1 -type f \
    \( -name '*.rpt' -o -name '*.log' -o -name '*.ltx' \) -print0)
  [[ -f "${uboot_run_dir}/vivado-build-${variant}.log" ]] && \
    install_public_text "${uboot_run_dir}/vivado-build-${variant}.log" \
      "${report_dir}/vivado-build-${variant}.log"
  [[ -f "${uboot_run_dir}/vivado-configure-${variant}.log" ]] && \
    install_public_text "${uboot_run_dir}/vivado-configure-${variant}.log" \
      "${report_dir}/vivado-configure-${variant}.log"
  [[ -f "${uboot_run_dir}/clock-config-${variant}.txt" ]] && \
    install_public_text "${uboot_run_dir}/clock-config-${variant}.txt" \
      "${report_dir}/clock-config-${variant}.txt"

  python3 "${ROOT_DIR}/scripts/ci/parse-vivado-timing.py" \
    --variant "${variant}" \
    --report "${impl_dir}/timing_summary.rpt" \
    --output-json "${report_dir}/timing-metrics.json" \
    --output-markdown "${report_dir}/timing-summary.md"

  download_verified_file \
    "${UBOOT_BINARY_URL}" \
    "${stage_dir}/boot/uboot/u-boot.bin" \
    "${UBOOT_BINARY_SHA256}"
  download_verified_file \
    "${PROGRAMMER_BY_UART_URL}" \
    "${stage_dir}/boot/tools/programmer_by_uart.bit" \
    "${PROGRAMMER_BY_UART_SHA256}"
  generate_openfpgaloader_helper "${stage_dir}"

  cat >"${stage_dir}/boot/BOOT_INFO.txt" <<EOF
variant=${variant}
soc_bitstream=boot/bitstream/Gemmont-${RELEASE_VERSION}-${variant}.bit
u_boot_binary=boot/uboot/u-boot.bin
u_boot_url=${UBOOT_BINARY_URL}
u_boot_sha256=${UBOOT_BINARY_SHA256}
programmer_bitstream=boot/tools/programmer_by_uart.bit
uart_xmodem_helper=boot/tools/uart-xmodem-ftdi.py
programmer_by_uart_url=${PROGRAMMER_BY_UART_URL}
programmer_by_uart_sha256=${PROGRAMMER_BY_UART_SHA256}
linux_releases_url=${LINUX_RELEASES_URL}
uboot_requested_mhz=${UBOOT_REQUESTED_MHZ}
openfpgaloader_part=xc7a200tfbg676
openfpgaloader_default_cable=ft232
uart_ftdi_vendor=0x0403
uart_ftdi_product=0x6001
uart_ftdi_serial_env=UART_FTDI_SERIAL
uart_programmer_baud=230400
uboot_uart_baud=115200
EOF

  cat >"${stage_dir}/boot/README.md" <<EOF
# Gemmont ${RELEASE_VERSION} U-Boot boot materials

This directory contains a Chiplab Loongson-board SoC bitstream with the current
Gemmont RTL, the prebuilt LoongArch32R U-Boot binary referenced by Chiplab, and
an openFPGALoader helper.

The U-Boot/Linux-capable SoC bitstream fixes the CPU clock at
${UBOOT_REQUESTED_MHZ} MHz.

## Files

- \`bitstream/Gemmont-${RELEASE_VERSION}-${variant}.bit\`: U-Boot/Linux-capable SoC bitstream.
- \`uboot/u-boot.bin\`: bootloader image for the removable SoC SPI flash.
- \`tools/programmer_by_uart.bit\`: Chiplab UART flash programmer bitstream.
- \`tools/openfpgaloader.sh\`: convenience wrapper for openFPGALoader.
- \`tools/uart-xmodem-ftdi.py\`: libftdi XMODEM sender used by \`flash-uboot\`.
- \`BOOT_INFO.txt\`: source URLs, checksums, serial rates, and defaults.

## Program without Vivado

Detect the JTAG adapter:

\`\`\`bash
./boot/tools/openfpgaloader.sh scan-usb
./boot/tools/openfpgaloader.sh detect
\`\`\`

Write \`boot/uboot/u-boot.bin\` to the SoC boot SPI flash.  This loads
\`programmer_by_uart.bit\` with openFPGALoader, then sends \`u-boot.bin\` over
an FTDI USB-UART using XMODEM-CRC at 230400 baud:

\`\`\`bash
./boot/tools/openfpgaloader.sh flash-uboot
\`\`\`

Then load the Gemmont SoC bitstream:

\`\`\`bash
./boot/tools/openfpgaloader.sh sram
\`\`\`

The combined command is:

\`\`\`bash
./boot/tools/openfpgaloader.sh boot
\`\`\`

If you need to drive the UART programmer manually, load it into FPGA SRAM:

\`\`\`bash
./boot/tools/openfpgaloader.sh programmer
\`\`\`

Then open the programmer UART at 230400 baud, input \`x\`, and send
\`boot/uboot/u-boot.bin\` with XMODEM.

To make the FPGA configuration persistent, write the same bitstream to FPGA
configuration flash:

\`\`\`bash
./boot/tools/openfpgaloader.sh flash-fpga
\`\`\`

Open the board serial console at 115200 baud. U-Boot should print its banner and
prompt if the removable SoC SPI flash contains \`u-boot.bin\`.

\`flash-fpga\` writes the FPGA configuration flash.  It is separate from the
verified U-Boot path above; do not use \`flash-fpga\` to program
\`boot/uboot/u-boot.bin\`.

Linux kernel releases: ${LINUX_RELEASES_URL}

Set \`OPENFPGALOADER_CABLE\`, \`OPENFPGALOADER_PART\`, or
\`OPENFPGALOADER_FREQ\` if your JTAG adapter differs from the default FT232
\`xc7a200tfbg676\` setup.  Set \`UART_FTDI_SERIAL\`, \`UART_FTDI_VENDOR\`, or
\`UART_FTDI_PRODUCT\` if the FTDI USB-UART differs from the default
\`0403:6001\` adapter or if multiple FTDI UARTs are attached.
EOF
}

build_func_perf_variant() {
  local variant="$1"
  local chiplab_dir="${WORK_ROOT}/${variant}/chiplab"
  local stage_dir="${STAGE_ROOT}/${variant}"
  local run_dir="${chiplab_dir}/fpga/nscscc-team/run_vivado"

  prepare_chiplab_workspace "${variant}" "${chiplab_dir}"
  # Match the submission bundle copied by the competition CI. Generated RTL
  # carries the H64 synthesis image; canonical resources remain beside it for
  # simulation and provenance.
  install_readmem_resources "${chiplab_dir}/IP/myCPU"
  configure_variant "${chiplab_dir}" "${variant}"

  echo "Building ${variant} bitstream with Vivado (${VIVADO_JOBS} jobs)"
  (
    cd "${run_dir}"
    if [[ "${variant}" == "perf" ]]; then
      # Competition physical authority: configure the platform-owned Clock
      # Wizard before create_project.tcl, using the script from the pinned
      # Chiplab snapshot.  This also freezes sys_clk and ddr_clk at the
      # platform values and records the Clock Wizard's actual frequencies.
      rm -f "clock-config-${variant}.txt"
      vivado -mode batch -notrace -source generate_perf_pll.tcl \
        -tclargs "${PERF_REQUESTED_MHZ}" \
        "${chiplab_dir}/chip/soc_demo/nscscc-team/xilinx_ip/clk_pll/clk_pll.xci" \
        "clock-config-${variant}.txt" \
        2>&1 | tee "vivado-create-${variant}.log"
      [[ -s "clock-config-${variant}.txt" ]] || \
        die "Vivado did not produce a valid ${variant} clock configuration"
      vivado -mode batch -notrace -source create_project.tcl \
        2>&1 | tee -a "vivado-create-${variant}.log"
    else
      # Competition func uses the untouched platform XCI: a 33 MHz request
      # realized by Clock Wizard as 32.727 MHz.  Do not regenerate this PLL.
      vivado -mode batch -notrace -source create_project.tcl \
        2>&1 | tee "vivado-create-${variant}.log"
    fi
    vivado -mode batch -notrace -source "${ROOT_DIR}/scripts/ci/vivado-build.tcl" \
      -tclargs "${VIVADO_JOBS}" "project/loongson.xpr" \
      2>&1 | tee "vivado-build-${variant}.log"
    wait_for_implementation "${variant}" "${run_dir}/project/loongson.runs" \
      2>&1 | tee -a "vivado-build-${variant}.log"
    verify_core_top_systemverilog "${run_dir}/project/loongson.runs" \
      2>&1 | tee -a "vivado-build-${variant}.log"
    vivado -mode batch -notrace -source "${ROOT_DIR}/scripts/ci/vivado-report.tcl" \
      2>&1 | tee -a "vivado-build-${variant}.log"
  )

  collect_results "${chiplab_dir}" "${variant}" "${stage_dir}"
}

run_build_target() {
  local target="$1"

  case "${target}" in
    func|perf)
      build_func_perf_variant "${target}"
      ;;
    uboot)
      build_uboot_boot_materials
      ;;
    *)
      die "unsupported build target: ${target}"
      ;;
  esac
}

prepare_target_payload() {
  local target="$1"
  local stage_dir="${STAGE_ROOT}/${target}"
  local target_report_dir="${stage_dir}/reports/${target}"
  local vivado_version_text

  [[ -d "${stage_dir}" ]] || die "missing staged output for ${target}"
  mkdir -p "${stage_dir}/rtl" "${target_report_dir}"
  install -m 0644 "${ROOT_DIR}/core_top.v" "${stage_dir}/rtl/core_top.v"
  install -m 0644 "${ROOT_DIR}/difftest_wrap.v" "${stage_dir}/rtl/difftest_wrap.v"
  install -m 0644 \
    "${ROOT_DIR}/src/main/resources/H64WeightRom.sv" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank0.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank1.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank2.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank3.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank4.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank5.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank6.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4-bank7.hex" \
    "${ROOT_DIR}/src/main/resources/h64-residual-weights-int4.json" \
    "${stage_dir}/rtl/"

  case "${target}" in
    func)
      local func_obj="${CHIPLAB_SOURCE_DIR}/software/examples/nscscc_func/obj"
      mkdir -p "${stage_dir}/ram/func" "${stage_dir}/tools"
      for file in main.bin inst_ram.coe inst_ram.mif rom.vlog; do
        [[ -s "${func_obj}/${file}" ]] || \
          die "missing Chiplab function RAM artifact: ${file}"
        install -m 0644 "${func_obj}/${file}" "${stage_dir}/ram/func/${file}"
      done
      install -m 0644 \
        "${NSCSCC_RUN_DIR_SOURCE}/jtag_axi_master.tcl" \
        "${stage_dir}/tools/load_func.tcl"
      # shellcheck disable=SC2016
      sed -i \
        's|^set bin_file \[open .*|set bundle_root [file normalize [file join [file dirname [info script]] ..]]\nset bin_file [open [file join $bundle_root ram func main.bin] "rb"]|' \
        "${stage_dir}/tools/load_func.tcl"
      grep -q '^set bin_file .*ram func main.bin' \
        "${stage_dir}/tools/load_func.tcl" || \
        die "failed to configure the function-test JTAG loader"
      ;;
    perf)
      local perf_obj="${CHIPLAB_SOURCE_DIR}/software/examples/nscscc_perf/obj"
      local perf_bins=0
      local perf_file
      local relative_path
      mkdir -p "${stage_dir}/ram/perf" "${stage_dir}/tools"
      while IFS= read -r -d '' perf_file; do
        relative_path="${perf_file#"${perf_obj}/"}"
        install -D -m 0644 "${perf_file}" \
          "${stage_dir}/ram/perf/${relative_path}"
        [[ "${perf_file}" == *.bin ]] && ((perf_bins += 1))
      done < <(find "${perf_obj}" -type f \
        \( -name '*.bin' -o -name '*.coe' -o -name '*.mif' \) -print0)
      ((perf_bins >= 21)) || \
        die "expected all Chiplab perf RAM binaries, found ${perf_bins}"
      install -m 0644 \
        "${NSCSCC_RUN_DIR_SOURCE}/jtag_axi_master.tcl" \
        "${stage_dir}/tools/load_perf_allbench.tcl"
      # shellcheck disable=SC2016
      sed -i \
        's|^set bin_file \[open .*|set bundle_root [file normalize [file join [file dirname [info script]] ..]]\nset bin_file [open [file join $bundle_root ram perf allbench inst_data.bin] "rb"]|' \
        "${stage_dir}/tools/load_perf_allbench.tcl"
      grep -q '^set bin_file .*ram perf allbench inst_data.bin' \
        "${stage_dir}/tools/load_perf_allbench.tcl" || \
        die "failed to configure the perf JTAG loader"
      ;;
  esac

  vivado_version_text="$(vivado -version | sed -n '1p')"
  local target_requested_mhz="${PERF_REQUESTED_MHZ}"
  case "${target}" in
    func) target_requested_mhz="${FUNC_REQUESTED_MHZ}" ;;
    uboot) target_requested_mhz="${UBOOT_REQUESTED_MHZ}" ;;
  esac
  cat >"${target_report_dir}/build-target-info.txt" <<EOF
target=${target}
release_version=${RELEASE_VERSION}
source_sha=${SOURCE_SHA}
chiplab_url=${CHIPLAB_URL}
chiplab_ref=${CHIPLAB_REF}
chiplab_sha=${CHIPLAB_SHA}
vivado_runtime=${VIVADO_RUNTIME}
vivado_version=${vivado_version_text}
fpga_part=xc7a200tfbg676-2
requested_mhz=${target_requested_mhz}
build_time_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
}

echo "Starting ${FPGA_BUILD_TARGET} Vivado build; streaming log to ${LOG_ROOT}/${FPGA_BUILD_TARGET}.log"
log_file="${LOG_ROOT}/${FPGA_BUILD_TARGET}.log"
status_file="${LOG_ROOT}/${FPGA_BUILD_TARGET}.status"
set +e
run_build_target "${FPGA_BUILD_TARGET}" 2>&1 | tee "${log_file}"
target_status=$?
set -e
echo "${target_status}" >"${status_file}"
if ((target_status != 0)); then
  die "${FPGA_BUILD_TARGET} FPGA build failed with status ${target_status}"
fi
prepare_target_payload "${FPGA_BUILD_TARGET}"
echo "Staged ${FPGA_BUILD_TARGET} output: ${STAGE_ROOT}/${FPGA_BUILD_TARGET}"
