#!/bin/bash

IFS=',' read -r -a arrayLocalDeps <<< "$GITHUB_WORKSPACE$INPUT_LOCAL_DEPENDENCY"
for localDep in "${arrayLocalDeps[@]}"
do
    IFS=':' read -r -a arrayLocalDep <<< "$localDep"
    mvn install:install-file -Dfile="${arrayLocalDep[0]}" -DgroupId="${arrayLocalDep[1]}" -DartifactId="${arrayLocalDep[2]}" -Dversion="${arrayLocalDep[3]}" -Dpackaging="${arrayLocalDep[4]}"
done