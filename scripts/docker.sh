#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"

# Run 'clojure-dependabot' in the Docker container

declare -r TAG=pitch/clojure-dependabot:latest
docker build -t "$TAG" --build-arg "PULL_DEPENDENCIES=${PULL_DEPENDENCIES:-1}" .

declare -r SRC=/src

declare -a ENV=(
    GITHUB_PAT="${GITHUB_PAT:?'env var not set'}"
    GITHUB_TOKEN="${GITHUB_TOKEN:?'env var not set'}"
    GITHUB_WORKSPACE="$SRC"
    GITHUB_REPOSITORY="${GITHUB_REPOSITORY:?'env var not set. example: my-org/my-repo'}"
    GITHUB_REF="${GITHUB_REF:?'env var not set. example: refs/heads/main'}"
    GITHUB_SHA="${GITHUB_SHA:?'env var not set. should be the sha of the commit you want to test against'}"
    GITHUB_STEP_SUMMARY="/tmp/clojure-dependabot-step-summary.txt"
    INPUT_AUTO_PULL_REQUEST="${INPUT_AUTO_PULL_REQUEST:-true}"
    INPUT_DIRECTORY="${INPUT_DIRECTORY:-}"
    INPUT_IGNORE_DEPENDENCIES="${INPUT_IGNORE_DEPENDENCIES:-}"
    INPUT_INCLUDE_SUBDIRECTORIES="${INPUT_INCLUDE_SUBDIRECTORIES:-true}"
    INPUT_LABELS="${INPUT_LABELS:-}"
    INPUT_LOCAL_DEPENDENCIES="${INPUT_LOCAL_DEPENDENCIES:-}"
    INPUT_MAIN_BRANCH="${INPUT_MAIN_BRANCH=-master}"
    INPUT_REVIEWERS="${INPUT_REVIEWERS:-}"
    INPUT_SECURITY_UPDATES_ONLY="${INPUT_SECURITY_UPDATES_ONLY:-false}"
    INPUT_SEVERITY="${INPUT_SEVERITY:-low}"
)

declare -a CMD=(
    docker run
    --rm -t
    -v "$HOME/.m2:/root/.m2/"
    -v "$HOME/.gitlibs:/root/.gitlibs/"
    -v "../pitch-app:$SRC"
    -w "$SRC"
)

for e in "${ENV[@]}"; do
    CMD+=(-e "$e")
done

exec "${CMD[@]}" "$TAG" "$@"
