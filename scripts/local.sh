#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"

# Run 'clojure-dependabot' locally (non-containerized)
#
# The tools in .tool-versions (babashka, clojure, lein) come from asdf, and are installed if missing.
# Java, Maven, git and gh must already be on the PATH.
#
# By default this scans the repository at ../clojure-dependabot-test (relative to this
# repository). Set GITHUB_WORKSPACE to scan a different one. The other GITHUB_* variables are
# derived from that repository with git and gh, unless they are already set.

if ! command -v asdf >/dev/null; then
    echo "asdf is required to provide the tools in .tool-versions: https://asdf-vm.com" >&2
    exit 1
fi

export PATH="${ASDF_DATA_DIR:-$HOME/.asdf}/shims:$PATH"
# asdf picks versions from the .tool-versions of the directory a command runs in, but clojure and
# lein run in the scanned projects, so pin this repository's versions (e.g. ASDF_LEIN_VERSION)
while read -r tool version _; do
    if [[ -z "$tool" || "$tool" == \#* ]]; then
        continue
    fi
    version_var="ASDF_$(tr '[:lower:]-' '[:upper:]_' <<<"$tool")_VERSION"
    export "$version_var=${!version_var:-$version}"
done < .tool-versions

GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-../clojure-dependabot-test}"
if [[ ! -d "$GITHUB_WORKSPACE" ]]; then
    echo "GITHUB_WORKSPACE is not a directory: $GITHUB_WORKSPACE" >&2
    exit 1
fi
# the absolute path of the repository's root
GITHUB_WORKSPACE="$(git -C "$GITHUB_WORKSPACE" rev-parse --show-toplevel)"

# owner/repo, which gh resolves from the repository's git remotes (or its `gh repo set-default`)
if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
    GITHUB_REPOSITORY="$(cd "$GITHUB_WORKSPACE" && gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi

# the checked out branch, e.g. refs/heads/main (fails on a detached HEAD)
if [[ -z "${GITHUB_REF:-}" ]]; then
    GITHUB_REF="$(git -C "$GITHUB_WORKSPACE" symbolic-ref HEAD)"
fi

if [[ -z "${GITHUB_SHA:-}" ]]; then
    GITHUB_SHA="$(git -C "$GITHUB_WORKSPACE" rev-parse HEAD)"
fi

# the remote's default branch, falling back to main if origin/HEAD isn't known locally
if [[ -z "${INPUT_MAIN_BRANCH:-}" ]]; then
    INPUT_MAIN_BRANCH="$(git -C "$GITHUB_WORKSPACE" symbolic-ref --quiet --short refs/remotes/origin/HEAD || true)"
    INPUT_MAIN_BRANCH="${INPUT_MAIN_BRANCH#origin/}"
    INPUT_MAIN_BRANCH="${INPUT_MAIN_BRANCH:-main}"
fi

export GITHUB_PAT="${GITHUB_PAT:?'env var not set'}"
export GITHUB_TOKEN="${GITHUB_TOKEN:?'env var not set'}"
export GITHUB_WORKSPACE
export GITHUB_REPOSITORY
export GITHUB_REF
export GITHUB_SHA
export GITHUB_STEP_SUMMARY="/tmp/clojure-dependabot-step-summary.txt"
export INPUT_AUTO_PULL_REQUEST="${INPUT_AUTO_PULL_REQUEST:-true}"
export INPUT_DIRECTORY="${INPUT_DIRECTORY:-}"
export INPUT_IGNORE_DEPENDENCIES="${INPUT_IGNORE_DEPENDENCIES:-}"
export INPUT_INCLUDE_SUBDIRECTORIES="${INPUT_INCLUDE_SUBDIRECTORIES:-true}"
export INPUT_LABELS="${INPUT_LABELS:-}"
export INPUT_LOCAL_DEPENDENCIES="${INPUT_LOCAL_DEPENDENCIES:-}"
export INPUT_MAIN_BRANCH
export INPUT_REVIEWERS="${INPUT_REVIEWERS:-}"
export INPUT_SECURITY_UPDATES_ONLY="${INPUT_SECURITY_UPDATES_ONLY:-false}"
export INPUT_SEVERITY="${INPUT_SEVERITY:-low}"

export LOCAL_DEV='1'

echo "Scanning $GITHUB_REPOSITORY ($GITHUB_REF at $GITHUB_SHA) in $GITHUB_WORKSPACE"

exec bb clojure_dependabot.clj "$@"
