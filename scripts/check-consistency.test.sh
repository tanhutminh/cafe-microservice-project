#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
unset CDPATH

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/.." && pwd)
script="$here/check-consistency.sh"

work=""
cleanup() {
  rm -rf -- "$work"
}
trap cleanup EXIT
work=$(mktemp -d)

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

pristine="$work/pristine"
for file in "${files[@]}"; do
  mkdir -p "$pristine/$(dirname "$file")"
  sed 's/\r$//' "$repo/$file" >"$pristine/$file"
done

pass=0
fail=0
cases=0
dir=""
broken=""

check() {
  local desc=$1 ok=$2
  if [ "$ok" = "true" ]; then
    echo "ok - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc"
    fail=$((fail + 1))
  fi
}

fresh() {
  cases=$((cases + 1))
  dir="$work/case-$cases"
  cp -R "$pristine" "$dir"
  broken=""
}

mutate() {
  local rel=$1 expr=$2
  sed -E "$expr" "$dir/$rel" >"$dir/$rel.new"
  if cmp -s "$dir/$rel" "$dir/$rel.new"; then
    broken="the mutation '$expr' left $rel unchanged"
  fi
  mv "$dir/$rel.new" "$dir/$rel"
}

expect() {
  local desc=$1 status=0 ok=true want
  shift
  bash "$script" "$dir" >"$dir.out" 2>"$dir.err" || status=$?
  if [ -n "$broken" ]; then
    echo "    $broken" >&2
    ok=false
  elif [ "$#" -eq 0 ]; then
    if [ "$status" -ne 0 ] || [ "$(cat "$dir.out")" != "check-consistency: all checks passed" ] || [ -s "$dir.err" ]; then
      ok=false
    fi
  else
    if [ "$status" -ne 1 ] || [ -s "$dir.out" ] || [ "$(printf '%s\n' "$@" | sort)" != "$(sort "$dir.err")" ]; then
      ok=false
    fi
    for want in "$@"; do
      if ! grep -qxF -- "$want" "$dir.err"; then
        echo "    missing: $want" >&2
        ok=false
      fi
    done
  fi
  if [ "$ok" = false ]; then
    sed 's/^/    got: /' "$dir.err" >&2
  fi
  check "$desc" "$ok"
}

line_of() {
  { grep -nF -- "$2" "$pristine/$1" || true; } | sed -n 1p | cut -d: -f1
}

value_of() {
  sed -nE "$2" "$pristine/$1" | sed -n 1p
}

escape() {
  printf '%s' "$1" | sed 's/[.[\*^$/]/\\&/g'
}

pg_version=$(value_of "$compose" 's/^[[:space:]]+image:[[:space:]]*postgres:([0-9.]+)@sha256:[0-9a-f]+$/\1/p')
pg_digest=$(value_of "$compose" 's/^[[:space:]]+image:[[:space:]]*postgres:[0-9.]+@sha256:([0-9a-f]+)$/\1/p')
pg_major=${pg_version%%.*}
kafka_version=$(value_of "$compose" 's/^[[:space:]]+image:[[:space:]]*apache\/kafka:([0-9.]+)@sha256:[0-9a-f]+$/\1/p')
kafka_digest=$(value_of "$compose" 's/^[[:space:]]+image:[[:space:]]*apache\/kafka:[0-9.]+@sha256:([0-9a-f]+)$/\1/p')
kafka_minor=$(printf '%s' "$kafka_version" | sed -E 's/^([0-9]+\.[0-9]+).*/\1/')
java_major=$(value_of "$pom" 's/^[[:space:]]*<java\.version>([0-9]+)<\/java\.version>.*/\1/p')
shellcheck_image=$(value_of "$workflow" 's/.*koalaman\/shellcheck:([^@[:space:]]+@sha256:[0-9a-f]+).*/\1/p')

pg_version_re=$(escape "$pg_version")
kafka_version_re=$(escape "$kafka_version")
kafka_minor_re=$(escape "$kafka_minor")
kafka_major=${kafka_version%%.*}
new_pg_digest=$(printf '%064d' 1)
new_kafka_digest=$(printf '%064d' 2)
other_digest=$(printf '%064d' 3)
new_pg_major=$((pg_major + 1))
new_pg_version="$new_pg_major.1"
new_kafka_minor="$kafka_major.$((${kafka_minor#*.} + 1))"
new_kafka_version="$new_kafka_minor.0"
new_java=$((java_major + 1))
b=$'\x60'
appended_line=$(($(wc -l <"$pristine/$dockerfile") + 1))

pg_ref="$compose:$(line_of "$compose" "image: postgres:")"
kafka_ref="$compose:$(line_of "$compose" "image: apache/kafka:")"
java_ref="$pom:$(line_of "$pom" "<java.version>")"
shellcheck_ref="$workflow:$(line_of "$workflow" "koalaman/shellcheck:")"

fresh
expect "the checked-in files agree"

noarg_status=0
(cd "$work" && bash "$script") >"$work/noarg.out" 2>"$work/noarg.err" || noarg_status=$?
if [ "$noarg_status" -eq 0 ] && [ "$(cat "$work/noarg.out")" = "check-consistency: all checks passed" ] && [ ! -s "$work/noarg.err" ]; then
  check "with no argument it checks the repository the script lives in, from any directory" true
else
  check "with no argument it checks the repository the script lives in, from any directory" false
fi

fresh
for file in "${files[@]}"; do
  sed 's/$/\r/' "$dir/$file" >"$dir/$file.crlf"
  mv "$dir/$file.crlf" "$dir/$file"
done
expect "CRLF line endings are read like LF"

usage_status=0
bash "$script" "$work" "$work" >"$work/usage.out" 2>"$work/usage.err" || usage_status=$?
if [ "$usage_status" -eq 2 ] && [ ! -s "$work/usage.out" ] && [ "$(cat "$work/usage.err")" = "usage: check-consistency.sh [repo-root]" ]; then
  check "two arguments print the usage and exit 2" true
else
  check "two arguments print the usage and exit 2" false
fi

usage_status=0
bash "$script" "$work/no-such-dir" >"$work/usage.out" 2>"$work/usage.err" || usage_status=$?
if [ "$usage_status" -eq 2 ] && [ ! -s "$work/usage.out" ] && [ "$(cat "$work/usage.err")" = "usage: check-consistency.sh [repo-root]" ]; then
  check "a root that is not a directory prints the usage and exits 2" true
else
  check "a root that is not a directory prints the usage and exits 2" false
fi

fresh
rm "$dir/$kafka_cluster" "$dir/$readme"
expect "missing files are each reported and no check runs" \
  "ERROR files: $readme: missing" \
  "ERROR files: $kafka_cluster: missing"

fresh
mutate "$compose" "s/postgres:$pg_version_re@sha256:$pg_digest/postgres:$new_pg_version@sha256:$new_pg_digest/; s/postgres$pg_major-data/postgres$new_pg_major-data/g; s/apache\/kafka:$kafka_version_re@sha256:$kafka_digest/apache\/kafka:$new_kafka_version@sha256:$new_kafka_digest/"
mutate "$service_values" "s/\"$pg_version_re-alpine@/\"$new_pg_version-alpine@/"
mutate "$postgres_cluster" "s/postgresql:$pg_version_re-/postgresql:$new_pg_version-/"
mutate "$order_images" "s/$pg_digest/$new_pg_digest/; s/$kafka_digest/$new_kafka_digest/"
mutate "$inventory_images" "s/$pg_digest/$new_pg_digest/"
mutate "$readme" "s/postgres$pg_major-data/postgres$new_pg_major-data/g; s/^- Java $java_major$/- Java $new_java/"
mutate "$runbook" "s/(pins the Postgres image \\()$pg_major,/\\1$new_pg_major,/; s/(pin image Postgres \\()$pg_major,/\\1$new_pg_major,/; s/version \\($kafka_minor_re\\) is pinned/version ($new_kafka_minor) is pinned/; s/Kafka \\($kafka_minor_re\\) được pin/Kafka ($new_kafka_minor) được pin/"
mutate "$kafka_cluster" "s/^(    version: )$kafka_version_re$/\\1$new_kafka_version/"
mutate "$pom" "s/<java\\.version>$java_major</<java.version>$new_java</"
mutate "$dockerfile" "s/-eclipse-temurin-$java_major@/-eclipse-temurin-$new_java@/; s/eclipse-temurin:$java_major\\./eclipse-temurin:$new_java./"
mutate "$workflow" "s/java-version: '$java_major'/java-version: '$new_java'/"
expect "a coherent bump of Postgres, Kafka and Java everywhere passes"

fresh
mutate "$service_values" "s/\"$pg_version_re-alpine@/\"$pg_version.9-alpine@/"
expect "the wait-for-db tag must have the compose Postgres version" \
  "ERROR postgres-version: $service_values:$(line_of "$service_values" "-alpine@sha256:"): found $pg_version.9, expected $pg_version ($pg_ref)"

fresh
mutate "$postgres_cluster" "s/postgresql:$pg_version_re-/postgresql:$pg_version.9-/"
expect "the CNPG imageName must have the compose Postgres version" \
  "ERROR postgres-version: $postgres_cluster:$(line_of "$postgres_cluster" "imageName: ghcr.io"): found $pg_version.9, expected $pg_version ($pg_ref)"

fresh
mutate "$order_images" "s/\"postgres@sha256:$pg_digest\"/\"postgres@sha256:$new_pg_digest\"/"
expect "order-service's POSTGRES digest must be compose's" \
  "ERROR postgres-version: $order_images:$(line_of "$order_images" '"postgres@sha256:'): found $new_pg_digest, expected $pg_digest ($pg_ref)"

fresh
mutate "$inventory_images" "s/\"postgres@sha256:$pg_digest\"/\"postgres@sha256:$new_pg_digest\"/"
expect "inventory-service's POSTGRES digest must be compose's" \
  "ERROR postgres-version: $inventory_images:$(line_of "$inventory_images" '"postgres@sha256:'): found $new_pg_digest, expected $pg_digest ($pg_ref)"

fresh
mount_line=$(line_of "$compose" "- postgres$pg_major-data:")
decl_line=$({ grep -nx -- "  postgres$pg_major-data:" "$pristine/$compose" || true; } | cut -d: -f1)
mutate "$compose" "s/postgres$pg_major-data/postgres$((pg_major - 1))-data/g"
expect "each compose volume name must carry the Postgres major version" \
  "ERROR postgres-version: $compose:$mount_line: found postgres$((pg_major - 1))-data, expected postgres$pg_major-data ($pg_ref)" \
  "ERROR postgres-version: $compose:$decl_line: found postgres$((pg_major - 1))-data, expected postgres$pg_major-data ($pg_ref)"

fresh
mutate "$compose" "${decl_line}s/\$/ {} # replaces postgres$((pg_major - 1))-data/"
expect "every volume name on a compose line is counted" \
  "ERROR postgres-version: $compose: expected exactly 2 occurrence(s) of a postgres<major>-data volume name, found 3 (lines $mount_line,$decl_line,$decl_line)"

fresh
old_volume="postgres$((pg_major - 1))-data"
mutate "$readme" "s/postgres$pg_major-data/$old_volume/g"
expect "each README sentence that names the current volume must carry the Postgres major version" \
  "ERROR postgres-version: $readme:$(line_of "$readme" "Postgres volume (postgres$pg_major-data)"): found $old_volume, expected postgres$pg_major-data ($pg_ref)" \
  "ERROR postgres-version: $readme:$(line_of "$readme" "major version (${b}postgres$pg_major-data${b})"): found $old_volume, expected postgres$pg_major-data ($pg_ref)" \
  "ERROR postgres-version: $readme:$(line_of "$readme" "removes ${b}<project>_postgres$pg_major-data${b}, which the current"): found $old_volume, expected postgres$pg_major-data ($pg_ref)" \
  "ERROR postgres-version: $readme:$(line_of "$readme" "volume Postgres (postgres$pg_major-data)"): found $old_volume, expected postgres$pg_major-data ($pg_ref)" \
  "ERROR postgres-version: $readme:$(line_of "$readme" "major version của server (${b}postgres$pg_major-data${b})"): found $old_volume, expected postgres$pg_major-data ($pg_ref)" \
  "ERROR postgres-version: $readme:$(line_of "$readme" "xoá ${b}<project>_postgres$pg_major-data${b}, volume mà"): found $old_volume, expected postgres$pg_major-data ($pg_ref)"

fresh
mutate "$readme" "\$a The previous version's volume is ${b}<project>_$old_volume${b}."
expect "a README mention of the previous major version's volume is allowed"

fresh
mutate "$runbook" "s/(pins the Postgres image \\()$pg_major,/\\1$pg_version,/"
expect "the English runbook names the Postgres major version only" \
  "ERROR postgres-version: $runbook:$(line_of "$runbook" "pins the Postgres image ("): found $pg_version, expected $pg_major ($pg_ref)"

fresh
mutate "$runbook" "s/(pin image Postgres \\()$pg_major,/\\1$((pg_major - 1)),/"
expect "the Vietnamese runbook names the compose Postgres major version" \
  "ERROR postgres-version: $runbook:$(line_of "$runbook" "pin image Postgres ("): found $((pg_major - 1)), expected $pg_major ($pg_ref)"

fresh
pg_line=$(line_of "$compose" "image: postgres:")
mutate "$compose" "${pg_line}s/@sha256:$pg_digest//"
expect "a compose postgres image without its digest stops the Postgres comparisons" \
  "ERROR postgres-version: $compose: expected exactly 1 line(s) with the postgres image, found 0" \
  "ERROR digests-present: $compose:$pg_line: postgres:$pg_version has no @sha256 digest"

fresh
tag_line=$(line_of "$service_values" "-alpine@sha256:")
mutate "$service_values" "${tag_line}p"
expect "two wait-for-db tag lines are reported with their line numbers" \
  "ERROR postgres-version: $service_values: expected exactly 1 line(s) with the wait-for-db image tag, found 2 (lines $tag_line,$((tag_line + 1)))"

fresh
mutate "$kafka_cluster" "s/^(    version: )$kafka_version_re$/\\1$kafka_version.1/"
expect "the Strimzi version must be compose's Kafka version" \
  "ERROR kafka-version: $kafka_cluster:$(line_of "$kafka_cluster" "    version: "): found $kafka_version.1, expected $kafka_version ($kafka_ref)"

fresh
mutate "$order_images" "s/\"apache\/kafka@sha256:$kafka_digest\"/\"apache\/kafka@sha256:$new_kafka_digest\"/"
expect "order-service's KAFKA digest must be compose's" \
  "ERROR kafka-version: $order_images:$(line_of "$order_images" '"apache/kafka@sha256:'): found $new_kafka_digest, expected $kafka_digest ($kafka_ref)"

fresh
mutate "$runbook" "s/version \\($kafka_minor_re\\) is pinned/version ($kafka_version) is pinned/"
expect "the English runbook names the Kafka major.minor version only" \
  "ERROR kafka-version: $runbook:$(line_of "$runbook" ") is pinned, so upgrading the Strimzi"): found $kafka_version, expected $kafka_minor ($kafka_ref)"

fresh
mutate "$runbook" "s/Kafka \\($kafka_minor_re\\) được pin/Kafka (9.9) được pin/"
expect "the Vietnamese runbook names the compose Kafka major.minor version" \
  "ERROR kafka-version: $runbook:$(line_of "$runbook" ") được pin, nên nâng cấp operator Strimzi"): found 9.9, expected $kafka_minor ($kafka_ref)"

fresh
mutate "$compose" "s/apache\/kafka:$kafka_version_re@/apache\/kafka:$kafka_major@/"
expect "a compose Kafka tag without a minor version is unsupported" \
  "ERROR kafka-version: $kafka_cluster:$(line_of "$kafka_cluster" "    version: "): found $kafka_version, expected $kafka_major ($kafka_ref)" \
  "ERROR kafka-version: $kafka_ref: unsupported value $kafka_major (no minor version)"

fresh
kafka_line=${kafka_ref##*:}
mutate "$compose" "${kafka_line}s/@sha256:$kafka_digest//"
expect "a compose Kafka image without its digest stops the Kafka comparisons" \
  "ERROR kafka-version: $compose: expected exactly 1 line(s) with the apache/kafka image, found 0" \
  "ERROR digests-present: $compose:$kafka_line: apache/kafka:$kafka_version has no @sha256 digest"

fresh
mutate "$pom" "s/<java\\.version>$java_major</<java.version>$new_java</"
expect "every Java copy is compared with the pom's java.version" \
  "ERROR java-version: $dockerfile:$(line_of "$dockerfile" "FROM maven:"): found $java_major, expected $new_java ($java_ref)" \
  "ERROR java-version: $dockerfile:$(line_of "$dockerfile" "FROM eclipse-temurin:"): found $java_major, expected $new_java ($java_ref)" \
  "ERROR java-version: $workflow:$(line_of "$workflow" "java-version:"): found $java_major, expected $new_java ($java_ref)" \
  "ERROR java-version: $readme:$(grep -nxF -- "- Java $java_major" "$pristine/$readme" | sed -n 1p | cut -d: -f1): found $java_major, expected $new_java ($java_ref)" \
  "ERROR java-version: $readme:$(grep -nxF -- "- Java $java_major" "$pristine/$readme" | sed -n 2p | cut -d: -f1): found $java_major, expected $new_java ($java_ref)"

fresh
mutate "$pom" "/<java\\.version>/d"
expect "a pom without java.version stops the Java comparisons" \
  "ERROR java-version: $pom: expected exactly 1 line(s) with java.version, found 0"

fresh
mutate "$dockerfile" "s/-eclipse-temurin-$java_major@/-eclipse-temurin-$new_java@/"
expect "the Maven image must have the pom's Java major version" \
  "ERROR java-version: $dockerfile:$(line_of "$dockerfile" "FROM maven:"): found $new_java, expected $java_major ($java_ref)"

fresh
mutate "$dockerfile" "s/-eclipse-temurin-$java_major@/-eclipse-temurin-$java_major-noble@/"
expect "a Maven image tag with an OS suffix is read"

fresh
mutate "$dockerfile" "s/eclipse-temurin:$java_major\\./eclipse-temurin:$new_java./"
expect "the JRE image must have the pom's Java major version" \
  "ERROR java-version: $dockerfile:$(line_of "$dockerfile" "FROM eclipse-temurin:"): found $new_java, expected $java_major ($java_ref)"

fresh
mutate "$workflow" "s/java-version: '$java_major'/java-version: '17'/"
expect "setup-java must have the pom's Java major version" \
  "ERROR java-version: $workflow:$(line_of "$workflow" "java-version:"): found 17, expected $java_major ($java_ref)"

fresh
java_readme_line=$({ grep -nxF -- "- Java $java_major" "$pristine/$readme" || true; } | sed -n 2p | cut -d: -f1)
mutate "$readme" "${java_readme_line}s/^- Java $java_major$/- Java 17/"
expect "each README Java prerequisite must have the pom's Java major version" \
  "ERROR java-version: $readme:$java_readme_line: found 17, expected $java_major ($java_ref)"

fresh
mutate "$runbook" "s/(koalaman\/shellcheck:[^@[:space:]]+@sha256:)[0-9a-f]{64}/\\1$other_digest/"
expect "each runbook shellcheck command must use the workflow's image" \
  "ERROR shellcheck-image: $runbook:$(grep -nF -- koalaman/shellcheck: "$pristine/$runbook" | sed -n 1p | cut -d: -f1): found ${shellcheck_image%@*}@sha256:$other_digest, expected $shellcheck_image ($shellcheck_ref)" \
  "ERROR shellcheck-image: $runbook:$(grep -nF -- koalaman/shellcheck: "$pristine/$runbook" | sed -n 2p | cut -d: -f1): found ${shellcheck_image%@*}@sha256:$other_digest, expected $shellcheck_image ($shellcheck_ref)"

fresh
mutate "$workflow" "s/(koalaman\/shellcheck:[^@[:space:]]+@sha256:[0-9a-f]{64})/\\1a/"
expect "a workflow shellcheck digest with an extra hex digit is not read" \
  "ERROR shellcheck-image: $workflow: expected exactly 1 line(s) with the shellcheck image, found 0"

fresh
ui_line=$(line_of "$compose" "image: kafbat/kafka-ui:")
ui_image=$(sed -n "${ui_line}p" "$pristine/$compose" | sed -E 's/^[[:space:]]+image:[[:space:]]*//; s/@sha256:.*//')
mutate "$compose" "${ui_line}s/@sha256:[0-9a-f]{64}//"
expect "every compose image needs a digest" \
  "ERROR digests-present: $compose:$ui_line: $ui_image has no @sha256 digest"

fresh
mutate "$compose" "${ui_line}s/image:[[:space:]]*([^[:space:]]+)$/image: \"\\1\"/"
expect "a quoted compose image with a digest passes"

zipkin_line=$(line_of "$compose" "image: openzipkin/zipkin:")
zipkin_image=$(sed -n "${zipkin_line}p" "$pristine/$compose" | sed -E 's/^[[:space:]]+image:[[:space:]]*//; s/@sha256:.*//')
for q in "'" '"'; do
  fresh
  mutate "$compose" "${zipkin_line}s/image:[[:space:]]*([^@[:space:]]+)@sha256:[0-9a-f]{64}\$/image: $q\\1$q/"
  expect "a $q-quoted compose image without a digest is named without its quotes" \
    "ERROR digests-present: $compose:$zipkin_line: $zipkin_image has no @sha256 digest"
done

fresh
jre_line=$(line_of "$dockerfile" "FROM eclipse-temurin:")
jre_image=$(sed -n "${jre_line}p" "$pristine/$dockerfile" | sed -E 's/^FROM[[:space:]]+//; s/@sha256:.*//')
mutate "$dockerfile" "${jre_line}s/@sha256:[0-9a-f]{64}//"
expect "every Dockerfile base image needs a digest, stage names excepted" \
  "ERROR digests-present: $dockerfile:$jre_line: $jre_image has no @sha256 digest"

fresh
mutate "$dockerfile" "/^[Ff][Rr][Oo][Mm][[:space:]]/d"
expect "a Dockerfile without a FROM line is reported" \
  "ERROR java-version: $dockerfile: expected exactly 1 line(s) with the Maven image, found 0" \
  "ERROR java-version: $dockerfile: expected exactly 1 line(s) with the eclipse-temurin image, found 0" \
  "ERROR digests-present: $dockerfile: expected at least 1 line(s) with a FROM instruction, found 0"

fresh
mutate "$dockerfile" "\$a FROM --platform=linux/amd64 busybox:1.37 AS tools"
expect "FROM flags are skipped to find the image" \
  "ERROR digests-present: $dockerfile:$appended_line: busybox:1.37 has no @sha256 digest"

fresh
mutate "$dockerfile" "\$a from alpine:3.22"
expect "a lowercase from instruction is read" \
  "ERROR digests-present: $dockerfile:$appended_line: alpine:3.22 has no @sha256 digest"

fresh
mutate "$dockerfile" "\$a FROM scratch"
expect "FROM scratch needs no digest"

fresh
mutate "$dockerfile" "\$a FROM DEPS AS extra"
expect "an earlier stage's name matches in any case"

fresh
mutate "$dockerfile" "\$a FROM alpine:3.22@sha256:$other_digest as Tools"
mutate "$dockerfile" "\$a FROM tools"
expect "a lowercase as and a stage name in any case are read"

fresh
mutate "$dockerfile" "\$a FROM later"
mutate "$dockerfile" "\$a FROM alpine:3.22@sha256:$other_digest AS later"
expect "a stage name counts only after the stage that defines it" \
  "ERROR digests-present: $dockerfile:$appended_line: later has no @sha256 digest"

fresh
mutate "$dockerfile" "\$a \\ \\ FROM alpine:3.22"
expect "an indented FROM is read" \
  "ERROR digests-present: $dockerfile:$appended_line: alpine:3.22 has no @sha256 digest"

fresh
maven_line=$(line_of "$dockerfile" "FROM maven:")
maven_image=$(sed -n "${maven_line}p" "$pristine/$dockerfile" | sed -E 's/^FROM[[:space:]]+//; s/@sha256:.*//')
mutate "$dockerfile" "${maven_line}s/@sha256:[0-9a-f]{64}//"
expect "a Maven image without its digest is reported once" \
  "ERROR digests-present: $dockerfile:$maven_line: $maven_image has no @sha256 digest"

fresh
mutate "$dockerfile" "s/eclipse-temurin:$java_major\\.[^@]*@/eclipse-temurin:$java_major@/"
expect "a JRE tag with only the Java major version is read"

fresh
mutate "$dockerfile" "\$a FROM --platform=linux/amd64"
expect "a FROM with only flags is reported" \
  "ERROR digests-present: $dockerfile:$appended_line: FROM names no image"

fresh
mutate "$dockerfile" "\$a FROM"
expect "a bare FROM is reported" \
  "ERROR digests-present: $dockerfile:$appended_line: FROM names no image"

fresh
mutate "$dockerfile" "\$a FROM --platform=linux/amd64 AS tools"
expect "a FROM whose first word after its flags is AS is reported" \
  "ERROR digests-present: $dockerfile:$appended_line: FROM names no image"

blank_cases=(
  "postgres-version|$service_values|-alpine@sha256:|the wait-for-db image tag|1"
  "postgres-version|$postgres_cluster|imageName: ghcr.io|the CNPG imageName|1"
  "postgres-version|$order_images|\"postgres@sha256:|the POSTGRES digest|1"
  "postgres-version|$inventory_images|\"postgres@sha256:|the POSTGRES digest|1"
  "postgres-version|$compose|- postgres$pg_major-data:|a postgres<major>-data volume name|2|occurrence(s) of"
  "postgres-version|$readme|Postgres volume (postgres$pg_major-data)|the volume name in the English docker compose down comment|1"
  "postgres-version|$readme|major version (${b}postgres$pg_major-data${b})|the volume name in the English \"named after the server's major version\" sentence|1"
  "postgres-version|$readme|removes ${b}<project>_postgres$pg_major-data${b}, which the current|the volume name in the English docker compose down -v sentence|1"
  "postgres-version|$readme|volume Postgres (postgres$pg_major-data)|the volume name in the Vietnamese docker compose down comment|1"
  "postgres-version|$readme|major version của server (${b}postgres$pg_major-data${b})|the volume name in the Vietnamese \"named after the server's major version\" sentence|1"
  "postgres-version|$readme|xoá ${b}<project>_postgres$pg_major-data${b}, volume mà|the volume name in the Vietnamese docker compose down -v sentence|1"
  "postgres-version|$runbook|pins the Postgres image (|the Postgres version of the English imageName bullet|1"
  "postgres-version|$runbook|pin image Postgres (|the Postgres version of the Vietnamese imageName bullet|1"
  "kafka-version|$kafka_cluster|    version: |the Strimzi Kafka version|1"
  "kafka-version|$order_images|\"apache/kafka@sha256:|the KAFKA digest|1"
  "kafka-version|$runbook|) is pinned, so upgrading the Strimzi|the Kafka version of the English Kafka bullet|1"
  "kafka-version|$runbook|) được pin, nên nâng cấp operator Strimzi|the Kafka version of the Vietnamese Kafka bullet|1"
  "java-version|$dockerfile|FROM eclipse-temurin:|the eclipse-temurin image|1"
  "java-version|$workflow|java-version:|java-version|1"
  "java-version|$readme|- Java $java_major|a \"- Java\" prerequisite|2"
  "shellcheck-image|$runbook|koalaman/shellcheck:|the shellcheck image|2"
)

blank_anchor() {
  local rel=$1 anchor=$2 line
  line=$(line_of "$rel" "$anchor")
  if [ -z "$line" ]; then
    broken="no line in $rel contains '$anchor'"
    return 0
  fi
  mutate "$rel" "${line}s/.*//"
}

for blank_case in "${blank_cases[@]}"; do
  IFS='|' read -r row_check rel anchor label count unit <<<"$blank_case"
  unit=${unit:-line(s) with}
  fresh
  blank_anchor "$rel" "$anchor"
  expect "$rel with $((count - 1)) $unit $label is reported" \
    "ERROR $row_check: $rel: expected exactly $count $unit $label, found $((count - 1))"
done

for check_name in postgres-version kafka-version java-version; do
  fresh
  want=()
  for blank_case in "${blank_cases[@]}"; do
    IFS='|' read -r row_check rel anchor label count unit <<<"$blank_case"
    unit=${unit:-line(s) with}
    if [ "$row_check" = "$check_name" ]; then
      blank_anchor "$rel" "$anchor"
      want+=("ERROR $row_check: $rel: expected exactly $count $unit $label, found $((count - 1))")
    fi
  done
  expect "every missing $check_name copy is reported in the same run" "${want[@]}"
done

fresh
mutate "$compose" "/^[[:space:]]+image:/s/.*//"
mutate "$dockerfile" "${jre_line}s/@sha256:[0-9a-f]{64}//"
expect "a compose file without any image line is reported and the Dockerfile is still checked" \
  "ERROR postgres-version: $compose: expected exactly 1 line(s) with the postgres image, found 0" \
  "ERROR kafka-version: $compose: expected exactly 1 line(s) with the apache/kafka image, found 0" \
  "ERROR digests-present: $compose: expected at least 1 line(s) with an image, found 0" \
  "ERROR digests-present: $dockerfile:$jre_line: $jre_image has no @sha256 digest"

mkdir "$work/empty"
bash "$script" "$work/empty" >/dev/null 2>"$work/empty.err" || true
read_files=$(sed -nE 's/^ERROR files: (.*): missing$/\1/p' "$work/empty.err" | sort)
pins=$(awk '/^            pins:$/ { on = 1; next } on && !/^              - / { on = 0 } on' "$pristine/$workflow" | sed -nE "s/^              - '([^']+)'$/\\1/p" | sort)
if [ -n "$pins" ] && [ "$pins" = "$read_files" ]; then
  check "the workflow's pins filter lists exactly the files the script reads" true
else
  check "the workflow's pins filter lists exactly the files the script reads" false
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
