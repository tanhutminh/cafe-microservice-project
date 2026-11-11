#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
unset CDPATH

usage() {
  echo "usage: check-consistency.sh [repo-root]" >&2
  exit 2
}

if [ "$#" -gt 1 ]; then
  usage
fi
if [ "$#" -eq 1 ]; then
  if [ ! -d "$1" ]; then
    usage
  fi
  root=$(cd -- "$1" && pwd)
else
  root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fi

compose=docker-compose.yml
readme=README.md
runbook=docs/gke-cicd-runbook.md
pom=backend/pom.xml
dockerfile=backend/Dockerfile
order_images=backend/order-service/src/test/java/com/cafe/orderservice/testsupport/ContainerImages.java
inventory_images=backend/inventory-service/src/test/java/com/cafe/inventoryservice/testsupport/ContainerImages.java
service_values=charts/cafe-service/values.yaml
postgres_cluster=k8s/data-layer/postgres-cluster.yaml
kafka_cluster=k8s/data-layer/kafka-cluster.yaml
workflow=.github/workflows/backend-ci.yml

files=(
  "$compose"
  "$readme"
  "$runbook"
  "$pom"
  "$dockerfile"
  "$order_images"
  "$inventory_images"
  "$service_values"
  "$postgres_cluster"
  "$kafka_cluster"
  "$workflow"
)

S='[[:space:]]'
D='[0-9a-f]{64}'
b=$'\x60'
errors=()
ex_line=()
ex_cap1=()
ex_cap2=()

fail() {
  errors+=("ERROR $1: $2${3:+:$3}: $4")
}

lines() {
  sed 's/\r$//' "$root/$1"
}

compare() {
  if [ "$4" != "$5" ]; then
    fail "$1" "$2" "$3" "found $4, expected $5 ($6)"
  fi
}

extract() {
  local check=$1 rel=$2 label=$3 re=$4 count=$5 mode=${6:-} raw hit n=0 numbers="" msg
  local unit="line(s) with"
  ex_line=()
  ex_cap1=()
  ex_cap2=()
  if [ "$mode" = each ]; then
    unit="occurrence(s) of"
    raw=$(lines "$rel" | grep -noE "$re" || true)
  else
    raw=$(lines "$rel" | grep -nE "$re" || true)
  fi
  if [ -n "$raw" ]; then
    while IFS= read -r hit; do
      n=$((n + 1))
      numbers=${numbers:+$numbers,}${hit%%:*}
      ex_line+=("${hit%%:*}")
      if [[ ${hit#*:} =~ $re ]]; then
        ex_cap1+=("${BASH_REMATCH[1]-}")
        ex_cap2+=("${BASH_REMATCH[2]-}")
      else
        ex_cap1+=("")
        ex_cap2+=("")
      fi
    done <<<"$raw"
  fi
  if [ "$count" = "1+" ]; then
    if [ "$n" -eq 0 ]; then
      fail "$check" "$rel" "" "expected at least 1 $unit $label, found 0"
      return 1
    fi
  elif [ "$n" -ne "$count" ]; then
    msg="expected exactly $count $unit $label, found $n"
    if [ "$n" -gt 1 ]; then
      msg="$msg (lines $numbers)"
    fi
    fail "$check" "$rel" "" "$msg"
    return 1
  fi
  return 0
}

major_minor() {
  if [[ $1 =~ ^([0-9]+\.[0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

# Compares the version and digest of docker-compose.yml's postgres image with each place that
# repeats them:
# - charts/cafe-service/values.yaml's wait-for-db tag: the same full version;
# - k8s/data-layer/postgres-cluster.yaml's imageName: the same full version;
# - both testsupport/ContainerImages classes: the same digest;
# - the two postgres<major>-data volume names in docker-compose.yml: its major version;
# - README.md's sentences that name the current postgres<major>-data volume: its major version;
# - the runbook's English and Vietnamese imageName bullets: its major version.
check_postgres_version() {
  local c=postgres-version v d ref major i file
  local -a readme_labels readme_res
  readme_labels=(
    "the volume name in the English docker compose down comment"
    "the volume name in the English \"named after the server's major version\" sentence"
    "the volume name in the English docker compose down -v sentence"
    "the volume name in the Vietnamese docker compose down comment"
    "the volume name in the Vietnamese \"named after the server's major version\" sentence"
    "the volume name in the Vietnamese docker compose down -v sentence"
  )
  readme_res=(
    "Postgres volume \\(postgres([0-9]+)-data\\)"
    "major version \\(${b}postgres([0-9]+)-data${b}\\)"
    "removes ${b}<project>_postgres([0-9]+)-data${b}, which the current"
    "volume Postgres \\(postgres([0-9]+)-data\\)"
    "major version của server \\(${b}postgres([0-9]+)-data${b}\\)"
    "xoá ${b}<project>_postgres([0-9]+)-data${b}, volume mà ${b}docker-compose\\.yml${b} hiện tại"
  )
  extract $c "$compose" "the postgres image" "^$S+image:$S*postgres:([0-9][0-9.]*)@sha256:($D)\$" 1 || return 0
  v=${ex_cap1[0]}
  d=${ex_cap2[0]}
  ref="$compose:${ex_line[0]}"
  major=${v%%.*}
  if extract $c "$service_values" "the wait-for-db image tag" "^$S+tag:$S*\"([0-9][0-9.]*)-alpine@sha256:$D\"\$" 1; then
    compare $c "$service_values" "${ex_line[0]}" "${ex_cap1[0]}" "$v" "$ref"
  fi
  if extract $c "$postgres_cluster" "the CNPG imageName" "^$S+imageName:$S*ghcr\\.io/cloudnative-pg/postgresql:([0-9][0-9.]*)-[0-9]{12}-[a-z]+-[a-z]+@sha256:$D\$" 1; then
    compare $c "$postgres_cluster" "${ex_line[0]}" "${ex_cap1[0]}" "$v" "$ref"
  fi
  for file in "$order_images" "$inventory_images"; do
    if extract $c "$file" "the POSTGRES digest" "\"postgres@sha256:($D)\";" 1; then
      compare $c "$file" "${ex_line[0]}" "${ex_cap1[0]}" "$d" "$ref"
    fi
  done
  if extract $c "$compose" "a postgres<major>-data volume name" 'postgres([0-9]+)-data' 2 each; then
    for i in "${!ex_line[@]}"; do
      compare $c "$compose" "${ex_line[$i]}" "postgres${ex_cap1[$i]}-data" "postgres$major-data" "$ref"
    done
  fi
  for i in "${!readme_res[@]}"; do
    if extract $c "$readme" "${readme_labels[$i]}" "${readme_res[$i]}" 1; then
      compare $c "$readme" "${ex_line[0]}" "postgres${ex_cap1[0]}-data" "postgres$major-data" "$ref"
    fi
  done
  if extract $c "$runbook" "the Postgres version of the English imageName bullet" "${b}imageName${b} pins the Postgres image \\(([0-9][0-9.]*), " 1; then
    compare $c "$runbook" "${ex_line[0]}" "${ex_cap1[0]}" "$major" "$ref"
  fi
  if extract $c "$runbook" "the Postgres version of the Vietnamese imageName bullet" "${b}imageName${b} pin image Postgres \\(([0-9][0-9.]*), " 1; then
    compare $c "$runbook" "${ex_line[0]}" "${ex_cap1[0]}" "$major" "$ref"
  fi
  return 0
}

# Compares the version and digest of docker-compose.yml's apache/kafka image with each place
# that repeats them:
# - k8s/data-layer/kafka-cluster.yaml's Strimzi version: the same full version;
# - order-service's testsupport/ContainerImages class: the same digest;
# - the runbook's English and Vietnamese Kafka bullets: its major.minor version.
# A compose version with no minor version is reported as unsupported, and the runbook bullets
# are then not compared.
check_kafka_version() {
  local c=kafka-version k d ref minor
  extract $c "$compose" "the apache/kafka image" "^$S+image:$S*apache/kafka:([0-9][0-9.]*)@sha256:($D)\$" 1 || return 0
  k=${ex_cap1[0]}
  d=${ex_cap2[0]}
  ref="$compose:${ex_line[0]}"
  if extract $c "$kafka_cluster" "the Strimzi Kafka version" "^$S+version:$S*([0-9][0-9.]*)\$" 1; then
    compare $c "$kafka_cluster" "${ex_line[0]}" "${ex_cap1[0]}" "$k" "$ref"
  fi
  if extract $c "$order_images" "the KAFKA digest" "\"apache/kafka@sha256:($D)\";" 1; then
    compare $c "$order_images" "${ex_line[0]}" "${ex_cap1[0]}" "$d" "$ref"
  fi
  if ! minor=$(major_minor "$k"); then
    fail $c "$compose" "${ref##*:}" "unsupported value $k (no minor version)"
    return 0
  fi
  if extract $c "$runbook" "the Kafka version of the English Kafka bullet" 'version \(([0-9][0-9.]*)\) is pinned' 1; then
    compare $c "$runbook" "${ex_line[0]}" "${ex_cap1[0]}" "$minor" "$ref"
  fi
  if extract $c "$runbook" "the Kafka version of the Vietnamese Kafka bullet" 'Kafka \(([0-9][0-9.]*)\) được pin' 1; then
    compare $c "$runbook" "${ex_line[0]}" "${ex_cap1[0]}" "$minor" "$ref"
  fi
  return 0
}

# Compares backend/pom.xml's java.version, a Java major version, with each place that repeats it:
# - backend/Dockerfile's Maven image tag (`-eclipse-temurin-<major>`): the same major version;
# - backend/Dockerfile's eclipse-temurin JRE image tag: the same major version;
# - the workflow's setup-java java-version: the same major version;
# - the two "- Java <major>" prerequisites in README.md: the same major version.
check_java_version() {
  local c=java-version n ref i
  extract $c "$pom" "java.version" "^$S*<java\\.version>([0-9]+)</java\\.version>$S*\$" 1 || return 0
  n=${ex_cap1[0]}
  ref="$pom:${ex_line[0]}"
  if extract $c "$dockerfile" "the Maven image" "^FROM$S+maven:[0-9.]+-eclipse-temurin-([0-9]+)(-[a-z0-9]+)*([@[:space:]]|\$)" 1; then
    compare $c "$dockerfile" "${ex_line[0]}" "${ex_cap1[0]}" "$n" "$ref"
  fi
  if extract $c "$dockerfile" "the eclipse-temurin image" "^FROM$S+eclipse-temurin:([0-9]+)([._@-]|[[:space:]]|\$)" 1; then
    compare $c "$dockerfile" "${ex_line[0]}" "${ex_cap1[0]}" "$n" "$ref"
  fi
  if extract $c "$workflow" "java-version" "^$S+java-version:$S*'([0-9]+)'\$" 1; then
    compare $c "$workflow" "${ex_line[0]}" "${ex_cap1[0]}" "$n" "$ref"
  fi
  if extract $c "$readme" "a \"- Java\" prerequisite" '^- Java ([0-9]+)$' 2; then
    for i in "${!ex_line[@]}"; do
      compare $c "$readme" "${ex_line[$i]}" "${ex_cap1[$i]}" "$n" "$ref"
    done
  fi
  return 0
}

# Compares the shellcheck image (tag and digest) of the workflow's lint step with each place that
# repeats it:
# - the two local lint commands in docs/gke-cicd-runbook.md, English and Vietnamese: the same tag
#   and digest.
check_shellcheck_image() {
  local c=shellcheck-image re image ref i
  re="koalaman/shellcheck:([^@[:space:]]+@sha256:$D)([^0-9a-f]|\$)"
  extract $c "$workflow" "the shellcheck image" "$re" 1 || return 0
  image=${ex_cap1[0]}
  ref="$workflow:${ex_line[0]}"
  if extract $c "$runbook" "the shellcheck image" "$re" 2; then
    for i in "${!ex_line[@]}"; do
      compare $c "$runbook" "${ex_line[$i]}" "${ex_cap1[$i]}" "$image" "$ref"
    done
  fi
  return 0
}

# Checks that these images are pinned by an @sha256 digest:
# - each image: line in docker-compose.yml, quoted or not;
# - each FROM line in backend/Dockerfile, in any case or indentation: the image after its flags,
#   unless it is scratch or the name of an earlier stage (in any case). A FROM that names no image
#   is reported too.
check_digests_present() {
  local c=digests-present digest_re i image words word ref name next stages=" "
  digest_re="@sha256:$D\$"
  if extract $c "$compose" "an image" "^$S+image:$S*[\"']?([^[:space:]\"']+)" 1+; then
    for i in "${!ex_line[@]}"; do
      image=${ex_cap1[$i]}
      if ! [[ $image =~ $digest_re ]]; then
        fail $c "$compose" "${ex_line[$i]}" "$image has no @sha256 digest"
      fi
    done
  fi
  if extract $c "$dockerfile" "a FROM instruction" "^$S*[Ff][Rr][Oo][Mm]($S.*)?\$" 1+; then
    for i in "${!ex_line[@]}"; do
      read -r -a words <<<"${ex_cap1[$i]}"
      ref=""
      name=""
      next=""
      for word in "${words[@]}"; do
        if [ -z "$ref" ]; then
          if [[ $word != --* ]]; then
            ref=$word
          fi
        elif [ "$next" = as ]; then
          name=$word
          next=taken
        elif [ "${word,,}" = as ]; then
          next=as
        fi
      done
      if [ -z "$ref" ] || [ "${ref,,}" = as ]; then
        fail $c "$dockerfile" "${ex_line[$i]}" "FROM names no image"
      elif [ "${ref,,}" != scratch ] && [[ $stages != *" ${ref,,} "* ]]; then
        if ! [[ $ref =~ $digest_re ]]; then
          fail $c "$dockerfile" "${ex_line[$i]}" "$ref has no @sha256 digest"
        fi
      fi
      if [ -n "$name" ]; then
        stages="$stages${name,,} "
      fi
    done
  fi
  return 0
}

for file in "${files[@]}"; do
  if [ ! -f "$root/$file" ]; then
    fail files "$file" "" "missing"
  fi
done

if [ "${#errors[@]}" -eq 0 ]; then
  check_postgres_version
  check_kafka_version
  check_java_version
  check_shellcheck_image
  check_digests_present
fi

if [ "${#errors[@]}" -gt 0 ]; then
  printf '%s\n' "${errors[@]}" >&2
  exit 1
fi
echo "check-consistency: all checks passed"
