#!/usr/bin/env bash
set -euo pipefail
"$(dirname "$0")/switch-loadtest-mode.sh" sql
SLOT_TYPE=NORMAL "$(dirname "$0")/prepare-reservation-pts.sh"

