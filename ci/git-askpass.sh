#!/usr/bin/env bash
# Git invokes this helper. The PAT stays out of process arguments and remote URLs.
set -euo pipefail
case "${1:-}" in
    *Username*) printf '%s\n' 'x-access-token' ;;
    *Password*) printf '%s\n' "${GITHUB_PAT:?Missing GitHub credential}" ;;
    *) exit 1 ;;
esac
