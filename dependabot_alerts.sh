#!/bin/bash

export GITHUB_TOKEN=$GITHUB_PAT
sleep 5
gh api -H "Accept: application/vnd.github+json" "/repos/$GITHUB_REPOSITORY/dependabot/alerts" --paginate > /tmp/dependabot_alerts.json || true