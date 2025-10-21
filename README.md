# Dependabot for Clojure projects

Dependabot doesn't directly support Clojure projects, but it is possible to send the dependencies list to Dependabot through GitHub [Submission API](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/using-the-dependency-submission-api).

This GitHub Action looks for all `project.clj` and `deps.edn` in the repository and sends the list of dependencies (both primary dependencies and transitive dependencies) to Dependabot. If enabled, it can open pull-requests to update packages.

**Maintainers:** ☁️ Pitch Cloud Engineering Team (https://www.pitch.com)

## Required Tokens

The Action requires the following environment variables to run the [maven-dependency-submission-action](https://github.com/advanced-security/maven-dependency-submission-action) CLI and GitHub CLI to list GitHub Security Alerts (GHSA) and to create auto-pull-requests for dependencies updates. Both a Personal Access Token (PAT) and GitHub Token (`github.token`) are required: the Action needs a PAT because GitHub Token cannot be used to list security alerts for security reasons ([_"Granting access to security alerts"_](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-security-and-analysis-settings-for-your-repository#granting-access-to-security-alerts)), and the Action cannot open pull-requests as `github-actions (bot)` if it doesn't use the GitHub Token.

- GitHub Personal Access Token to run GitHub CLI (recommended privileges: `repo:all`)
- `github.token`
- `github.repository`
- `github.ref`
- `github.sha`
- `github.workspace`

## Example

```
name: Dependabot for Clojure

on:
  workflow_dispatch:

env:
  GITHUB_PAT: ${{ secrets.PAT }}
  GITHUB_TOKEN: ${{ github.token }}
  GITHUB_REPOSITORY: ${{ github.repository }}
  GITHUB_REF: ${{ github.ref }}
  GITHUB_SHA: ${{ github.sha }}
  GITHUB_WORKSPACE: ${{ github.workspace }}

jobs:
  dependabot:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Dependabot for Clojure
        uses: pitch-io/clojure-dependabot@main
        with:
          labels: "third-party,bug"
          reviewers: "luigigubello"
          directory: "foo/bar"
```

See [`actions.yml`](./actions.yml) for more details on each option.

## Development

Test your changes by running:

```sh
bb test:bb
```

## Security

If you find a security vulnerability, please report it privately at [security@pitch.com](mailto:security@pitch.com).
