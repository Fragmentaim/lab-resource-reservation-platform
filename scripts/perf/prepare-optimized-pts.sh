#!/usr/bin/env bash
set -euo pipefail
"$(dirname "$0")/switch-loadtest-mode.sh" optimized
SLOT_TYPE=HOT "$(dirname "$0")/prepare-reservation-pts.sh"
