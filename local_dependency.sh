#!/bin/bash

if [ -n "$INPUT_LOCAL_DEPENDENCY" ]; then
    IFS=',' read -r -a arrayLocalDeps <<< "$GITHUB_WORKSPACE$INPUT_LOCAL_DEPENDENCY"
    for localDep in "${arrayLocalDeps[@]}"
    do
        IFS=':' read -r -a arrayLocalDep <<< "$localDep"
        mvn -ntp install:install-file -Dfile="${arrayLocalDep[0]}" -DgroupId="${arrayLocalDep[1]}" -DartifactId="${arrayLocalDep[2]}" -Dversion="${arrayLocalDep[3]}" -Dpackaging="${arrayLocalDep[4]}"
    done
fi