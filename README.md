# Dependabot for Clojure projects

Dependabot doesn't directly support Clojure projects, but it is possible to send the dependencies list to Dependabot through GitHub [Submission API](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/using-the-dependency-submission-api). 

This GitHub Action looks for all `project.clj` and `deps.edn` in the repository and sends the list of dependencies (both primary dependencies and transitive dependencies) to Dependabot. If enabled, it can open pull-requests to update packages.

**Maintainers:** 🛳️ Pitch DevOps Team (https://www.pitch.com)

## Required Tokens

The Action requires the following environment variables to run the [maven-dependency-submission-action](https://github.com/advanced-security/maven-dependency-submission-action) CLI and GitHub CLI to create auto-pull-requests for dependencies updates.

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
        uses: actions/checkout@v3
      - name: Dependabot for Clojure
        uses: pitch-io/clojure-dependabot@main
        with:
          labels: "third-party,bug"
          reviewers: "luigigubello"
          directory: "/foo/bar"
```

## Options

### auto_pull_request

Boolean value to enable or disable auto-pull-requests for dependencies updates.

### main_branch

The branch into which you want the pull requests created by the GitHub Action merged.

### labels

Add labels to the pull requests created by the GitHub Action. The labels need to be separated by a comma (`,`) and need to already exist.

### reviewers

Add reviewers to the pull requests created by the GitHub Action. Multiple reviewers need to be separated by a comma (`,`).

### directory

By default, the GitHub Action scans the entire repository by looking for `project.clj` and `deps.edn` files. It is possible to define a specific sub-path to not scan the entire repository.

## Security

If you find a security vulnerability, please report it privately at security@pitch.com.
