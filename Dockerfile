FROM clojure:lein-trixie-slim

LABEL com.github.actions.name="Dependabot for Clojure projects" \
      com.github.actions.description="Run Dependabot as GitHub Action workflow in your Clojure project."

RUN export DEBIAN_FRONTEND=noninteractive && \
    apt-get -qq update && \
    apt-get -qq install -y --no-install-recommends curl git maven

RUN set -o pipefail && \
    curl --retry 5 --retry-max-time 120 -sSfL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash

RUN set -o pipefail && \
    curl --retry 5 --retry-max-time 120 -sSfL https://raw.githubusercontent.com/babashka/babashka/v1.12.209/install | bash

RUN set -o pipefail && \
    curl --retry 5 --retry-max-time 120 -sSfL -o /usr/bin/maven-dependency-submission-linux https://github.com/advanced-security/maven-dependency-submission-action/releases/download/v5.0.0/maven-dependency-submission-action-linux && \
    chmod 0755 /usr/bin/maven-dependency-submission-linux

RUN set -o pipefail && \
    export DEBIAN_FRONTEND=noninteractive && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg && \
    chmod 0644 /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list && \
    apt-get -qq update && \
    apt-get -qq install -y --no-install-recommends gh

RUN mkdir /usr/lib/clojure-dependabot/

COPY bb.edn /usr/lib/clojure-dependabot/

# Helper for dev testing. This is pointless in GitHub Actions as the runner sets a different $HOME,
# so the things we download during the build get lost. There might be a better way to do this, like
# at runtime doing some smart copying, the current state is "fine" for now.
ARG PULL_DEPENDENCIES='0'
RUN if [ "$PULL_DEPENDENCIES" = 1 ]; then \
        cd /usr/lib/clojure-dependabot/ && \
        bb -e '(println "tooling installed")'; \
    fi

COPY clojure_dependabot.clj action.yml /usr/lib/clojure-dependabot/

COPY scripts/clojure-dependabot /usr/bin/

CMD ["clojure-dependabot"]
