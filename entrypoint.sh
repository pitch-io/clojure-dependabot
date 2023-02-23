#!/bin/sh
set -e

# Unsafe decision to fix https://github.com/actions/runner/issues/2033
git config --global --add safe.directory "$GITHUB_WORKSPACE"
git config --global user.email "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>"
git config --global user.name "github-actions[bot]"
chmod +x /scanner.sh
/scanner.sh project.clj
/scanner.sh deps.edn
chmod +x /dependabot_alerts.sh
/dependabot_alerts.sh
chmod +x /alerts_summary.sh
/alerts_summary.sh project.clj
/alerts_summary.sh deps.edn
chmod +x /antq.sh
/antq.sh project.clj
/antq.sh deps.edn