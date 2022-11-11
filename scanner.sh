#!/bin/bash

# $1 - "project.clj" or "deps.edn"
if [[ -n $INPUT_DIRECTORY ]]; then
    cd "$GITHUB_WORKSPACE$INPUT_DIRECTORY" || exit
fi
mapfile -t array < <(find . -name "$1")
for i in "${array[@]}"
do
    i=${i/.}
    cljdir=$GITHUB_WORKSPACE$INPUT_DIRECTORY${i//\/$1}
    cd "$cljdir" || exit
    if  [[ $1 == "project.clj" ]]; then
        lein pom
        mkdir projectclj
        mv pom.xml projectclj/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/projectclj" --job-name "${INPUT_DIRECTORY}${i}/projectclj"
    else
        clojure -Spom
        mkdir depsedn
        mv pom.xml depsedn/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/depsedn" --job-name "${INPUT_DIRECTORY}${i}/depsedn"
    fi
done
