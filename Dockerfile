FROM clojure:lein-slim-bullseye

LABEL com.github.actions.name="Dependabot for Clojure projects" \
      com.github.actions.description="Run Dependabot as GitHub Action workflow in your Clojure project."

# Install maven, antq, maven-dependency-submission cli 2.0.1, clojure, and gh cli
RUN apt update && \
    apt install maven libmaven-dependency-plugin-java curl jq git build-essential zlib1g-dev libncurses5-dev libgdbm-dev libnss3-dev libssl-dev libsqlite3-dev libreadline-dev libffi-dev libbz2-dev -y && \
    rm -rf /var/lib/apt/lists/* && \
    curl -O https://download.clojure.org/install/linux-install-1.11.1.1165.sh && \
    chmod +x linux-install-1.11.1.1165.sh && \
    ./linux-install-1.11.1.1165.sh && \
    curl --retry 5 --retry-max-time 120 -L -o maven-dependency-submission-linux-x64 https://github.com/advanced-security/maven-dependency-submission-action/releases/download/v4.0.3/maven-dependency-submission-action-linux && \
    chmod +x maven-dependency-submission-linux-x64 && \
    mv maven-dependency-submission-linux-x64 /usr/bin/maven-dependency-submission-linux-x64 && \
    clojure -Ttools install-latest :lib com.github.liquidz/antq :as antq && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg && \
    chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | tee /etc/apt/sources.list.d/github-cli.list > /dev/null && \
    apt update && \
    apt install gh -y

COPY local_dependency.sh /local_dependency.sh

COPY scanner.sh /scanner.sh

COPY dependabot_alerts.sh /dependabot_alerts.sh

COPY alerts_summary.sh /alerts_summary.sh

COPY antq.sh /antq.sh

COPY entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
