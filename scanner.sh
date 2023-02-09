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

vulnerabilities_summary () {
    mapfile -t info_pack < <(jq -r --arg MANIFEST "$1" '.[] | select(.dependency.manifest_path == $MANIFEST and .state == "open") | (.number|tostring) + "|" + .security_vulnerability.package.name + "|" + .security_vulnerability.severity + "|" + .security_advisory.ghsa_id + "|" + .security_advisory.cve_id + "|" + .security_vulnerability.first_patched_version.identifier + "|"' <<< "$2")
    for i in "${info_pack[@]}"
    do
        IFS='|' read -r -a array_i <<< "$i" 
        cd "/${1/'pom.xml'/''}" || exit
        dep_level=$(mvn dependency:tree -DoutputType=dot -Dincludes="${array_i[1]}" | grep -e "->" | cut -d ">" -f 2 | cut -d '"' -f 2 | cut -d ":" -f 1-2)
        IFS=' ' read -r -a dependency_level <<< "$dep_level"
        array_i+=("${dependency_level[0]}")
        table_row="| "
        counter=0
        for j in "${array_i[@]}"
        do
            if [[ $counter == 0 ]]; then
                table_row+="[$j](https://github.com/$GITHUB_REPOSITORY/security/dependabot/$j) | "
                counter=$((counter+1))
            elif [[ $counter == 1 ]]; then
                table_row+="$j | "
                counter=$((counter+1))
            elif [[ $counter == 2 ]]; then
                if [[ $j == "critical" ]] || [[ $j == "high" ]]; then
                    table_row+="‼️ $j | "
                else
                    table_row+="$j | "
                fi
                counter=$((counter+1))
            elif [[ $counter == 3 ]]; then
                table_row+="$j | "
                counter=$((counter+1))
            elif [[ $counter == 4 ]]; then
                if [[ $j = "null" ]]; then
                    table_row+="  | "
                else
                    table_row+="$j | "
                fi
                counter=$((counter+1))
            elif [[ $counter == 5 ]]; then
                table_row+="$j | "
                counter=$((counter+1))
            elif [[ $counter == 6 ]]; then
                table_row+="$j | "
                counter=$((counter+1))
            else
                continue
            fi
        done
        echo "$table_row" >> "$GITHUB_STEP_SUMMARY"
    done
}


# $1 - "project.clj" or "deps.edn"
if [[ -n $INPUT_DIRECTORY ]]; then
    cd "$GITHUB_WORKSPACE$INPUT_DIRECTORY" || exit
fi
mapfile -t array < <(find . -name "$1")
if [[ $1 == "project.clj" ]]; then
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
        mv pom.xml projectclj/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/projectclj" --job-name "${INPUT_DIRECTORY}${i}/projectclj"
    else
        clojure -Spom
        mkdir depsedn
        mv pom.xml depsedn/
        maven-dependency-submission-linux-x64 --token "$GITHUB_TOKEN" --repository "$GITHUB_REPOSITORY" --branch-ref "$GITHUB_REF" --sha "$GITHUB_SHA" --directory "${cljdir}/depsedn" --job-name "${INPUT_DIRECTORY}${i}/depsedn"
    fi
done
gh auth login --with-token <<<"$GITHUB_TOKEN"
vul_page=$(gh api -H "Accept: application/vnd.github+json" "/repos/$GITHUB_REPOSITORY/dependabot/alerts" --paginate)
for i in "${array[@]}"
do
    i=${i/.}
    cljdir=$GITHUB_WORKSPACE$INPUT_DIRECTORY${i//\/$1}
    cd "$cljdir" || exit
    if  [[ $1 == "project.clj" ]]; then
        dependency_tree_summary "projectclj" "$i"
        db_path="${cljdir}/projectclj/pom.xml"
        db_path=${db_path:1}
        {
            echo "| Number | Package | Severity | GHSA | CVE | Patched in | Dependency level |"
            echo "| --- | --- | --- | --- | --- | --- | --- |"
        } >> "$GITHUB_STEP_SUMMARY"
        vulnerabilities_summary "$db_path" "$vul_page"
        echo "" >> "$GITHUB_STEP_SUMMARY"
    else
        dependency_tree_summary "depsedn" "$i"
        db_path="${cljdir}/depsedn/pom.xml"
        db_path=${db_path:1}
        {
            echo "| Number | Package | Severity | GHSA | CVE | Patched in | Dependency level |"
            echo "| --- | --- | --- | --- | --- | --- | --- |"
        } >> "$GITHUB_STEP_SUMMARY"
        vulnerabilities_summary "$db_path" "$vul_page"
        echo "" >> "$GITHUB_STEP_SUMMARY"
    fi
done