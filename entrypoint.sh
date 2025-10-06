#!/bin/bash
set -e

# Unsafe decision to fix https://github.com/actions/runner/issues/2033
git config --global --add safe.directory "$GITHUB_WORKSPACE"
git config --global user.email "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>"
git config --global user.name "github-actions[bot]"

if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running local_dependency.sh"
fi

/local_dependency.sh

if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running scanner.sh"
fi

/scanner.sh project.clj
/scanner.sh deps.edn

if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running dependabot_alerts.sh"
fi

/dependabot_alerts.sh

if [[ "$INPUT_VERBOSE" == true ]] && [[ "$INPUT_SUMMARY" == true ]]; then
    echo "Running alerts_summary.sh"
fi

if [[ "$INPUT_SUMMARY" == true ]]; then
    /alerts_summary.sh project.clj
    /alerts_summary.sh deps.edn
fi

if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running antq.sh"
fi

/antq.sh project.clj
/antq.sh deps.edn

git checkout "$INPUT_MAIN_BRANCH"
git restore .
