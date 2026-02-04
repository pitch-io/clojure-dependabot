#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"

# Run 'clojure-dependabot' locally (non-containerized)

export GITHUB_PAT="${GITHUB_PAT:?'env var not set'}"
export GITHUB_TOKEN="${GITHUB_TOKEN:?'env var not set'}"
export GITHUB_WORKSPACE="${GITHUB_WORKSPACE:?'env var not set. example: ../my-project/'}"
export GITHUB_REPOSITORY="${GITHUB_REPOSITORY:?'env var not set. example: my-org/my-repo'}"
export GITHUB_REF="${GITHUB_REF:?'env var not set. example: refs/heads/main'}"
export GITHUB_SHA="${GITHUB_SHA:?'env var not set. should be the sha of the commit you want to test against'}"
export GITHUB_STEP_SUMMARY="/tmp/clojure-dependabot-step-summary.txt"
export INPUT_AUTO_PULL_REQUEST="${INPUT_AUTO_PULL_REQUEST:-true}"
export INPUT_DIRECTORY="${INPUT_DIRECTORY:-}"
export INPUT_IGNORE_DEPENDENCIES="${INPUT_IGNORE_DEPENDENCIES:-}"
export INPUT_INCLUDE_SUBDIRECTORIES="${INPUT_INCLUDE_SUBDIRECTORIES:-true}"
export INPUT_LABELS="${INPUT_LABELS:-}"
export INPUT_LOCAL_DEPENDENCIES="${INPUT_LOCAL_DEPENDENCIES:-}"
export INPUT_MAIN_BRANCH="${INPUT_MAIN_BRANCH=-master}"
export INPUT_REVIEWERS="${INPUT_REVIEWERS:-}"
export INPUT_SECURITY_UPDATES_ONLY="${INPUT_SECURITY_UPDATES_ONLY:-false}"
export INPUT_SEVERITY="${INPUT_SEVERITY:-low}"

exec bb clojure_dependabot.clj "$@"
