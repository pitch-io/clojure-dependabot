FROM clojure:lein-trixie-slim

LABEL com.github.actions.name="Dependabot for Clojure projects" \
      com.github.actions.description="Run Dependabot as GitHub Action workflow in your Clojure project."

# Install maven, antq, maven-dependency-submission cli 2.0.1, clojure, and gh cli
RUN set -o pipefail && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        maven libmaven-dependency-plugin-java curl jq git build-essential zlib1g-dev libncurses5-dev libgdbm-dev libnss3-dev libssl-dev libsqlite3-dev libreadline-dev libffi-dev libbz2-dev && \
    curl --retry 5 --retry-max-time 120 -sSfL -o linux-install.sh https://download.clojure.org/install/linux-install-1.11.1.1165.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    curl --retry 5 --retry-max-time 120 -sSfL -o maven-dependency-submission-linux-x64 https://github.com/advanced-security/maven-dependency-submission-action/releases/download/v4.1.1/maven-dependency-submission-action-linux && \
    chmod +x maven-dependency-submission-linux-x64 && \
    mv maven-dependency-submission-linux-x64 /usr/bin/maven-dependency-submission-linux-x64 && \
    clojure -Ttools install-latest :lib com.github.liquidz/antq :as antq && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg && \
    chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | tee /etc/apt/sources.list.d/github-cli.list > /dev/null && \
    apt-get update && \
    apt-get install -y --no-install-recommends gh && \
    rm -rf /var/lib/apt/lists/*

COPY local_dependency.sh /local_dependency.sh

COPY scanner.sh /scanner.sh

COPY dependabot_alerts.sh /dependabot_alerts.sh

COPY alerts_summary.sh /alerts_summary.sh

COPY antq.sh /antq.sh

COPY entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
