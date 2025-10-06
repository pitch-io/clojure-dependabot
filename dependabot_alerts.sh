#!/bin/bash

if [[ "$INPUT_VERBOSE" == true ]]; then
        echo "Updating Dependabot alerts"
fi
export GITHUB_TOKEN=$GITHUB_PAT
sleep 10
gh api -H "Accept: application/vnd.github+json" "/repos/$GITHUB_REPOSITORY/dependabot/alerts" --paginate > /tmp/dependabot_alerts.json || true