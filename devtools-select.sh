#!/usr/bin/env bash
# devtools-select.sh — standalone entry to the UC-27 devtools capability
# selector. The Java install-time CLI (OnboardCommand / ReconfigureCommand)
# shells out to this script (via ProcessRunner.runInheritIO) so the .deb TTY
# auto-onboard path reaches the IDENTICAL raw-mode selector setup.sh uses
# (AC#1,#14). setup.sh itself sources devtools-ui.sh directly rather than
# spawning this — both paths run the same `devtools_run_selector` function.
#
# The ledger location is taken from $AISB_DEVTOOLS_FILE when set (the Java CLI /
# install mode point it at the session/host path); otherwise it defaults to
# ./.ai-sandbox-devtools in the current directory (developer mode), as resolved
# by lib.sh.
#
# Exit codes (consumed by the Java caller to map onboard outcome):
#   0   committed
#   130 cancelled by the operator
#   3   no TTY (headless) → caller degrades to DEFERRED
#   1   no capabilities registered / internal error
set -uo pipefail

# Optional positional arg: the ledger path. The Java install-time CLI
# (OnboardCommand / ReconfigureCommand) passes the session/host ledger path here
# via `bash devtools-select.sh <ledger>` (so it need not set env on the child).
# setup.sh sources devtools-ui.sh directly and never runs this script. Falls back
# to $AISB_DEVTOOLS_FILE / the lib.sh default when absent. Must be set BEFORE
# lib.sh is sourced (lib.sh reads AISB_DEVTOOLS_FILE at source time).
if [ "$#" -ge 1 ] && [ -n "${1:-}" ]; then
    export AISB_DEVTOOLS_FILE="$1"
fi

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)"

# shellcheck source=lib.sh
. "$HERE/lib.sh"
# shellcheck source=devtools-ui.sh
. "$HERE/devtools-ui.sh"

devtools_run_selector
exit $?
