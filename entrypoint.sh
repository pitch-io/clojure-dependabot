#!/bin/bash
set -e

echo "Running entrypoint for Clojure Dependabot..."
echo "Setting up SSH keys..."

# Ensure .ssh directory exists
mkdir -p /root/.ssh

# Add SSH private key from environment variables (GitHub Actions Secrets)
echo "$SERVICE_ACCOUNT_PRIVATE_KEY" > /root/.ssh/id_rsa
chmod 600 /root/.ssh/id_rsa

# Add GitHub to known hosts
ssh-keyscan -p 443 ssh.github.com >> /root/.ssh/known_hosts

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
if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running alerts_summary.sh"
fi
chmod +x /alerts_summary.sh
/alerts_summary.sh project.clj
/alerts_summary.sh deps.edn
if [[ "$INPUT_VERBOSE" == true ]]; then
    echo "Running antq.sh"
fi
chmod +x /antq.sh
/antq.sh project.clj
/antq.sh deps.edn
