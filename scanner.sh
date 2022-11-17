#!/bin/bash

dependency_tree_summary () {
    mvn dependency:tree -Dverbose=true -DoutputFile="${1}/dependency-tree.txt"
    {
        echo "### $INPUT_DIRECTORY$2"
        echo "<details>"
        echo ""
        echo "\`\`\`"
        cat "${1}/dependency-tree.txt"
        echo "\`\`\`"
        echo "</details>"
        echo ""
    } >> "$GITHUB_STEP_SUMMARY"
}

# $1 - "project.clj" or "deps.edn"
if [[ -n $INPUT_DIRECTORY ]]; then
    cd "$GITHUB_WORKSPACE$INPUT_DIRECTORY" || exit
fi
mapfile -t array < <(find . -name "$1")
if [[ $GITHUB_STEP_SUMMARY != *"## Dependency Tree"* ]]; then
  echo "## Dependency Tree" >> "$GITHUB_STEP_SUMMARY"
fi
for i in "${array[@]}"
do
    i=${i/.}
    cljdir=$GITHUB_WORKSPACE$INPUT_DIRECTORY${i//\/$1}
    cd "$cljdir" || exit
    if  [[ $1 == "project.clj" ]]; then
        lein pom
        mkdir projectclj
        dependency_tree_summary "projectclj" "$i"
        mv pom.xml projectclj/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/projectclj" --job-name "${INPUT_DIRECTORY}${i}/projectclj"
    else
        clojure -Spom
        mkdir depsedn
        dependency_tree_summary "depsedn" "$i"
        mv pom.xml depsedn/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/depsedn" --job-name "${INPUT_DIRECTORY}${i}/depsedn"
    fi
done
