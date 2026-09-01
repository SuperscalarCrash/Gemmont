#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${ROOT_DIR}"
H64_DETAILED_TRACE="${H64_DETAILED_TRACE:-false}"
case "${H64_DETAILED_TRACE}" in
  true|false) ;;
  *)
    echo "error: H64_DETAILED_TRACE must be true or false" >&2
    exit 1
    ;;
esac
export H64_DETAILED_TRACE
mill -Dchisel.project.root="${ROOT_DIR}" \
  -i core.runMain gemmont.Generator "${ROOT_DIR}"
rm -f "${ROOT_DIR}/core_top.fir" "${ROOT_DIR}/core_top.anno.json"
