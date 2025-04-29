#!/bin/bash
set -e

# Unsafe decision to fix https://github.com/actions/runner/issues/2033
git config --global --add safe.directory "$GITHUB_WORKSPACE"
git config --global user.email "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>"
git config --global user.name "github-actions[bot]"
if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running local_dependency.sh"
fi
chmod +x /local_dependency.sh
/local_dependency.sh
if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running scanner.sh"
fi
chmod +x /scanner.sh
/scanner.sh project.clj
/scanner.sh deps.edn
if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running dependabot_alerts.sh"
fi
chmod +x /dependabot_alerts.sh
/dependabot_alerts.sh
if [[ "$INPUT_VERBOSE" == true ]] && [[ "$INPUT_SUMMARY" == true ]]; then
    echo "Running alerts_summary.sh"
fi
chmod +x /alerts_summary.sh
if [[ "$INPUT_SUMMARY" == true ]]; then
    /alerts_summary.sh project.clj
    /alerts_summary.sh deps.edn
fi
if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running antq.sh"
fi
chmod +x /antq.sh
/antq.sh project.clj
/antq.sh deps.edn
git checkout "$INPUT_MAIN_BRANCH"
git restore .