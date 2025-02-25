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
        #clojure -X:deps mvn-pom
      echo "...!!!!!!! RUNNING POM-GENERATOR !!!!!!!!!!!!!!!!"
      echo "ls -lah /"
      ls -lah /
      echo "mkdir pom-generator"
      mkdir pom-generator
      echo "cp /github/workspace/pom_generator.clj /github/workspace/pom-generator/pom_generator.clj"
      cp /github/workspace/pom_generator.clj /github/workspace/pom-generator/pom_generator.clj

      echo "ls -lah /github/workspace/pom-generator"
      ls -lah /github/workspace/pom-generator
      
      
        clojure -Sdeps \{\:deps\ \{org.clojure/tools.deps\ \{\:mvn/version\ \"0.22.1492\"\}\ org.clojure/data.xml\ \{\:mvn/version\ \"0.0.8\"\}\}\ \:paths\ \[\"pom-generator\"\]\} -X pom-generator/generate-pom :path \"$cljdir\"
        mkdir depsedn
        mv pom.xml depsedn/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/depsedn" --job-name "${INPUT_DIRECTORY}${i}/depsedn"
    fi
done
