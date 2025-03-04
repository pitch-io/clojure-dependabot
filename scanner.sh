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

      echo "!!!!!!!!!!!!!! INPUT VARIABLES !!!!!!!!!!!!!!!!!!!!!"
      echo "GITHUB_REPOSITORY: ${GITHUB_REPOSITORY}"
      echo "GITHUB_REF: ${GITHUB_REF}"

      
        lein pom
        mkdir projectclj
        mv pom.xml projectclj/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/projectclj" --job-name "${INPUT_DIRECTORY}${i}/projectclj"
    else
      mkdir pom-generator
      cp /pom_generator.clj /github/workspace/pom-generator/pom_generator.clj
      clojure -X:deps prep
      clojure -A:app -Strace

      clojure -Sdeps \{\:deps\ \{org.clojure/tools.deps\ \{\:mvn/version\ \"0.22.1492\"\}\ org.clojure/data.xml\ \{\:mvn/version\ \"0.0.8\"\}\}\ \:paths\ \[\"pom-generator\"\]\} -X pom-generator/generate-pom :repository \"$GITHUB_REPOSITORY\"
        
        mkdir depsedn
        mv pom.xml depsedn/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/depsedn" --job-name "${INPUT_DIRECTORY}${i}/depsedn"
    fi
done
