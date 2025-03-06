#!/bin/bash

# $1 - "project.clj" or "deps.edn"
if [[ -n $INPUT_DIRECTORY ]]; then
    if [[ "$INPUT_VERBOSE" == true ]]; then
        echo "Moving to $GITHUB_WORKSPACE$INPUT_DIRECTORY"
    fi
    cd "$GITHUB_WORKSPACE$INPUT_DIRECTORY" || exit
fi
if [[ "$INPUT_VERBOSE" == true ]]; then
        echo "Finding all $1 files"
fi
mapfile -t array < <(find . -name "$1")
if [[ $INPUT_INCLUDE_SUBDIRECTORIES != true ]]; then
    if [[ $1 == "project.clj" ]] && [[ "${array[*]}" == *"./project.clj"* ]]; then
        array=("./project.clj")
    elif [[ $1 == "deps.edn" ]] && [[ "${array[*]}" == *"./deps.edn"* ]]; then
        array=("./deps.edn")
    else
        array=()
    fi
fi
for i in "${array[@]}"
do
    if [[ "$INPUT_VERBOSE" == true ]]; then
        echo "Converting $i to pom.xml and summitting dependencies to Dependabot"
    fi
    i=${i/.}
    cljdir=$GITHUB_WORKSPACE$INPUT_DIRECTORY${i//\/$1}
    cd "$cljdir" || exit
    if  [[ $1 == "project.clj" ]]; then
        lein pom
        mkdir projectclj
        mv pom.xml projectclj/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/projectclj" --job-name "${INPUT_DIRECTORY}${i}/projectclj"
    else
        echo "!!!!!!!!!!!!!! INPUT VARIABLES !!!!!!!!!!!!!!!!!!!!!"
        echo "GITHUB_REPOSITORY: ${GITHUB_REPOSITORY}"
        echo "GITHUB_REF: ${GITHUB_REF}"

        # just a bit of hackery to get just the "utwig" out of "lifecheq/utwig"
        s="/${GITHUB_REPOSITORY}"
        repo="${s##/*/}"
      
        mkdir pom-generator
        cp /pom_generator.clj /github/workspace/pom-generator/pom_generator.clj
        cp /deps.edn /github/workspace/pom-generator/deps.edn
        clojure -X:deps prep
        clojure -A:app -Strace

        clojure -X pom-generator/generate-pom :repository \"$repo\"
        
        mkdir depsedn
        mv pom.xml depsedn/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/depsedn" --job-name "${INPUT_DIRECTORY}${i}/depsedn"
    fi
done
