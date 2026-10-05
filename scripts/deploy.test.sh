#!/usr/bin/env bash
# Behavioral tests for deploy.sh, at two levels. Run directly: `bash scripts/deploy.test.sh`.
#
# 1. deploy.sh's functions, the copies deploy.sh must stay in sync with, and the chart timings its
#    Helm timeout relies on: sources deploy.sh - which, thanks to its own BASH_SOURCE guard,
#    defines its functions and variables and applies its `set -euo pipefail` and `unset CDPATH`
#    to this shell when sourced, never running main() - and substitutes a fake "does this image
#    exist" function. It also checks main()'s check order, with every check faked. `image-tag.sh`
#    and main()'s own read-only git calls still run for real, against the real repo.
# 2. main() as a real process: copies deploy.sh and image-tag.sh into a throwaway git repo, the
#    fixture repo, and runs it there with fake kubectl/helm/gcloud/gke-gcloud-auth-plugin
#    executables first on PATH, which record every call, print a stdout marker and fail on
#    demand. git is real, but isolated from the developer's own git config and guarded so it can
#    only ever act on the fixture repo. The real kubectl/helm/gcloud/gke-gcloud-auth-plugin are
#    never invoked.
#
# What isn't tested is live cluster/registry behavior: whether an image actually pulls, or a
# rollout actually becomes ready.
set -euo pipefail

# Inherited git variables (set, for example, inside a git hook) would point the fixture repo's
# destructive git commands below (reset --hard, clean -fdx) at the real repository instead.
git_env=$(git rev-parse --local-env-vars) || exit 1
# shellcheck disable=SC2086 # deliberately split into one variable name per word
unset $git_env
# An inherited CDPATH would make the relative `cd` below resolve against it instead of here.
unset CDPATH
cd "$(dirname "${BASH_SOURCE[0]}")/.."

pass=0
fail=0

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

# shellcheck source-path=SCRIPTDIR/..
source scripts/deploy.sh

# Everything the suite writes lives under $work. The trap goes in before the mktemp, so a failure
# part-way through still removes it.
work=""
cleanup() {
  rm -rf -- "$work"
}
trap cleanup EXIT
work=$(mktemp -d)
stderr_file=$work/stderr
stdout_file=$work/stdout
call_log=$work/calls
fixture=$work/fixture
fakes=$work/fakes
mkdir "$fixture" "$fakes"

# One fake existence check for every case: records each image ref it is asked about, and reports
# the ref as missing only when it belongs to $missing_service ("" = nothing is missing).
missing_service=""
checked_refs=()
recording_exists() {
  checked_refs+=("$1")
  [[ "$1" != *"/cafe-${missing_service}:"* ]]
}

# Usage: run_case <missing-service or ""> <service>...
# Resets the recorded state, then runs build_set_args at HEAD into `set_args` with
# recording_exists, leaving its exit code in `rc` and its stderr in $stderr_file.
run_case() {
  missing_service=$1
  shift
  checked_refs=()
  set_args=()
  rc=0
  build_set_args set_args recording_exists HEAD "$@" 2>"$stderr_file" || rc=$?
}

svcs=(gateway auth-service menu-service)

# --- every image exists ---
run_case "" "${svcs[@]}"
[ "$rc" -eq 0 ] && all_present_succeeds=true || all_present_succeeds=false
check "when every image exists, build_set_args succeeds" "$all_present_succeeds"

[ "${#set_args[@]}" -eq $((2 * ${#svcs[@]})) ] && all_present_builds_every_arg=true || all_present_builds_every_arg=false
check "when every image exists, one --set-string pair is built per service" "$all_present_builds_every_arg"

expected_registry=us-central1-docker.pkg.dev/cafe-microservices/cafe-images
pairs_carry_own_tag=true
for i in "${!svcs[@]}"; do
  svc=${svcs[$i]}
  expected_tag=$(bash scripts/image-tag.sh "$svc") || { echo "ABORT - image-tag.sh failed for $svc in the real repo" >&2; exit 1; }
  [[ $expected_tag =~ ^[0-9a-f]{16}$ ]] || pairs_carry_own_tag=false
  # `${…-}`: if the case above failed, these slots may be unset, and set -u would otherwise end
  # the whole suite here instead of reporting a FAIL.
  [ "${set_args[$((2 * i))]-}" = "--set-string" ] || pairs_carry_own_tag=false
  [ "${set_args[$((2 * i + 1))]-}" = "${svc}.image.tag=${expected_tag}" ] || pairs_carry_own_tag=false
  [ "${checked_refs[$i]-}" = "${expected_registry}/cafe-${svc}:${expected_tag}" ] || pairs_carry_own_tag=false
done
check "each service's --set-string pair and its checked image ref carry that service's own content-hash tag" "$pairs_carry_own_tag"

# --- the output array is the one named by the first argument ---
set_args=()
other_args=()
missing_service=""
rc=0
build_set_args other_args recording_exists HEAD gateway 2>"$stderr_file" || rc=$?
[ "$rc" -eq 0 ] && [ "${#other_args[@]}" -eq 2 ] && [ "${#set_args[@]}" -eq 0 ] && fills_named_array=true || fills_named_array=false
check "build_set_args appends to the array named by its first argument, and only that one" "$fills_named_array"

# --- an output array may share a name a maintainer would plausibly give build_set_args' locals ---
# That is why its locals carry the `_bsa_` prefix: a nameref resolves to the innermost variable
# of that name, so an unprefixed local would swallow the caller's array.
for name in out exists_check ref svc tag image; do
  # Run in a subshell so declaring an array under that name leaves this script's own variables
  # untouched; it prints how many elements the named array ended up with.
  filled_count=$(
    declare -a "$name=()"
    build_set_args "$name" recording_exists HEAD gateway 2>/dev/null || exit 1
    declare -n filled=$name
    echo "${#filled[@]}"
  ) || filled_count=""
  [ "$filled_count" = "2" ] && name_filled=true || name_filled=false
  check "build_set_args fills an output array named '${name}'" "$name_filled"
done

# --- exactly one image is missing, at each possible position ---
for pos in "${!svcs[@]}"; do
  run_case "${svcs[$pos]}" "${svcs[@]}"
  of="service $((pos + 1)) of ${#svcs[@]}"

  [ "$rc" -ne 0 ] && aborted=true || aborted=false
  check "missing ${missing_service} (${of}): build_set_args returns non-zero" "$aborted"

  [ "${#set_args[@]}" -eq $((2 * pos)) ] && built_only_earlier_services=true || built_only_earlier_services=false
  check "missing ${missing_service} (${of}): only the services before it are built" "$built_only_earlier_services"

  [ "${#checked_refs[@]}" -eq $((pos + 1)) ] && stopped_checking_after_missing=true || stopped_checking_after_missing=false
  check "missing ${missing_service} (${of}): no service after it is checked" "$stopped_checking_after_missing"

  grep -q "MISSING.*cafe-${missing_service}:" "$stderr_file" && grep -q "not a not-found error" "$stderr_file" \
    && grep -q "workflow_dispatch" "$stderr_file" && error_names_missing_image=true || error_names_missing_image=false
  check "missing ${missing_service} (${of}): the abort message names that image, says to rule out a non-not-found gcloud error first and how to get it built" "$error_names_missing_image"
done

# --- a service whose tag cannot be computed ---
run_case "" does-not-exist gateway
[ "$rc" -ne 0 ] && unknown_service_aborts=true || unknown_service_aborts=false
check "a service image-tag.sh cannot resolve makes build_set_args return non-zero" "$unknown_service_aborts"

[ "${#set_args[@]}" -eq 0 ] && [ "${#checked_refs[@]}" -eq 0 ] && unknown_service_builds_nothing=true || unknown_service_builds_nothing=false
check "a service image-tag.sh cannot resolve is never checked and adds no --set-string pair" "$unknown_service_builds_nothing"

# --- require_bash_version, against the running bash's own version ---
running_major=${BASH_VERSINFO[0]}
running_minor=${BASH_VERSINFO[1]}
bash_version_cases=(
  "$((running_major - 1)) 99|0|an older major passes whatever its minor"
  "$running_major $running_minor|0|the same major and minor passes"
  "$running_major $((running_minor - 1))|0|the same major with an older minor passes"
  "$running_major $((running_minor + 1))|1|the same major with a newer minor fails"
  "$((running_major + 1)) 0|1|a newer major fails"
)
for bash_version_case in "${bash_version_cases[@]}"; do
  IFS='|' read -r required expected_rc desc <<< "$bash_version_case"
  rc=0
  # shellcheck disable=SC2086 # $required is deliberately split into <major> <minor>
  require_bash_version $required 2>"$stderr_file" || rc=$?
  if [ "$expected_rc" -eq 0 ]; then
    [ "$rc" -eq 0 ] && bash_version_ok=true || bash_version_ok=false
  else
    [ "$rc" -ne 0 ] && grep -qF "needs bash ${required/ /.} or newer" "$stderr_file" && bash_version_ok=true || bash_version_ok=false
  fi
  check "require_bash_version: requiring ${required/ /.} of bash ${running_major}.${running_minor}: $desc" "$bash_version_ok"
done

# --- require_helm_version 4 1 1, against a faked `helm version` ---
# Each row: what `helm version --template '{{.Version}}'` prints ("FAIL" = the command fails),
# whether the check should pass, and why.
helm_version_cases=(
  "v4.1.1|0|the exact floor passes"
  "v4.1.2|0|a newer patch passes"
  "v4.3.0+gbec5b06|0|a newer minor passes, build metadata and all"
  "v4.10.0|0|a two-digit minor passes (compared as a number, not as text)"
  "v5.0.0|0|a newer major passes"
  "v4.1.0|1|an older patch fails"
  "v4.0.5|1|an older minor fails"
  "v3.19.0|1|Helm 3 fails"
)
for helm_version_case in "${helm_version_cases[@]}"; do
  IFS='|' read -r reported expected_rc desc <<< "$helm_version_case"
  rc=0
  (
    # shellcheck disable=SC2317,SC2329 # invoked indirectly, by require_helm_version
    helm() { echo "$reported"; }
    require_helm_version 4 1 1
  ) 2>"$stderr_file" || rc=$?
  if [ "$expected_rc" -eq 0 ]; then
    [ "$rc" -eq 0 ] && helm_version_ok=true || helm_version_ok=false
  else
    [ "$rc" -ne 0 ] && grep -qF "needs Helm 4.1.1 or newer, but this is Helm ${reported}" "$stderr_file" \
      && helm_version_ok=true || helm_version_ok=false
  fi
  check "require_helm_version: Helm ${reported}: $desc" "$helm_version_ok"
done

unreadable_helm_versions=("garbage|prints something that isn't a version" "FAIL|fails to report a version")
for unreadable_helm_version in "${unreadable_helm_versions[@]}"; do
  IFS='|' read -r reported desc <<< "$unreadable_helm_version"
  rc=0
  (
    # shellcheck disable=SC2317,SC2329 # invoked indirectly, by require_helm_version
    helm() { [ "$reported" != FAIL ] || return 1; echo "$reported"; }
    require_helm_version 4 1 1
  ) 2>"$stderr_file" || rc=$?
  [ "$rc" -ne 0 ] && grep -qF "couldn't read the version" "$stderr_file" && ! grep -q "this is Helm" "$stderr_file" \
    && unreadable_helm_fails=true || unreadable_helm_fails=false
  check "require_helm_version: a helm that $desc fails without claiming a version" "$unreadable_helm_fails"
done

# --- require_tools ---
rc=0
require_tools bash git >"$stdout_file" 2>"$stderr_file" || rc=$?
[ "$rc" -eq 0 ] && [ ! -s "$stdout_file" ] && [ ! -s "$stderr_file" ] && tools_present_pass=true || tools_present_pass=false
check "require_tools: commands that are all on PATH pass silently" "$tools_present_pass"

rc=0
require_tools bash no-such-tool-a git no-such-tool-b 2>"$stderr_file" || rc=$?
[ "$rc" -ne 0 ] && grep -q "no-such-tool-a" "$stderr_file" && grep -q "no-such-tool-b" "$stderr_file" \
  && ! grep -qw "git" "$stderr_file" && missing_tools_named=true || missing_tools_named=false
check "require_tools: fails naming every missing command, and only those" "$missing_tools_named"

# --- require_gcloud_runs, against a faked gcloud ---
# 49 is what the gcloud launcher exits with when it can't find a Python to run on.
for gcloud_rc in 0 49; do
  rc=0
  (
    # shellcheck disable=SC2317,SC2329 # invoked indirectly, by require_gcloud_runs
    gcloud() { return "$gcloud_rc"; }
    require_gcloud_runs
  ) 2>"$stderr_file" || rc=$?
  if [ "$gcloud_rc" -eq 0 ]; then
    [ "$rc" -eq 0 ] && [ ! -s "$stderr_file" ] && gcloud_check_ok=true || gcloud_check_ok=false
    desc="a gcloud that starts passes silently"
  else
    [ "$rc" -ne 0 ] && grep -qF "fails to start" "$stderr_file" && grep -qF "CLOUDSDK_PYTHON" "$stderr_file" \
      && gcloud_check_ok=true || gcloud_check_ok=false
    desc="a gcloud that fails to start fails it, with the CLOUDSDK_PYTHON hint"
  fi
  check "require_gcloud_runs: $desc" "$gcloud_check_ok"
done

# --- main() runs every check in the documented order, before building the chart ---
# Inside a subshell (main exits it), every check main() calls is replaced by a fake that logs its
# call, arguments included, to $call_log and passes, except the one named by $failing, which
# fails. git is wrapped to log each call, then run for real: main()'s own git calls are read-only
# rev-parses of this repo. Each case's log must be the documented order up to and including the
# failing call. The cluster-tool fakes fail and are never expected in the log.
head_commit=$(command git rev-parse HEAD)
ordered_calls=(
  "require_bash_version 4 3"
  "require_tools gcloud helm kubectl gke-gcloud-auth-plugin git sha256sum"
  "git rev-parse --local-env-vars"
  "require_committed_inputs"
  "git rev-parse HEAD"
  "require_on_master $head_commit"
  "require_helm_version 4 1 1"
  "require_gcloud_runs"
  "require_kube_context"
  "build_set_args set_args image_exists $head_commit ${services[*]}"
)
order_cases=(
  "require_bash_version|1|a too-old bash stops it before anything else"
  "require_tools|2|a missing tool stops it before any git call"
  "build_set_args|${#ordered_calls[@]}|every check runs in the documented order before the tags are built"
)
for order_case in "${order_cases[@]}"; do
  IFS='|' read -r failing expected_count desc <<< "$order_case"
  : > "$call_log"
  rc=0
  # Every fake forwards its arguments, so an argument main passes to any check shows up in the
  # log; that includes the checks main calls without arguments, which is what SC2120 flags.
  # shellcheck disable=SC2120,SC2317,SC2329 # invoked indirectly, by main
  (
    record() { local rc=$1; shift; echo "$*" >> "$call_log"; return "$rc"; }
    fake() { if [ "$1" = "$failing" ]; then record 1 "$@"; else record 0 "$@"; fi; }
    require_bash_version() { fake require_bash_version "$@"; }
    require_tools() { fake require_tools "$@"; }
    require_committed_inputs() { fake require_committed_inputs "$@"; }
    require_on_master() { fake require_on_master "$@"; }
    require_helm_version() { fake require_helm_version "$@"; }
    require_gcloud_runs() { fake require_gcloud_runs "$@"; }
    require_kube_context() { fake require_kube_context "$@"; }
    build_set_args() { fake build_set_args "$@"; }
    git() { record 0 git "$@"; command git "$@"; }
    helm() { record 1 helm "$@"; }
    kubectl() { record 1 kubectl "$@"; }
    gcloud() { record 1 gcloud "$@"; }
    main
  ) > /dev/null 2>&1 || rc=$?
  expected_order_log=$(printf '%s\n' "${ordered_calls[@]:0:expected_count}")
  [ "$rc" -ne 0 ] && [ "$(cat "$call_log")" = "$expected_order_log" ] && runs_in_order=true || runs_in_order=false
  check "main: $desc" "$runs_in_order"
done

# --- the chart's rollout timings, and helm_timeout above them ---
# One parse of the Deployment template and of the wait-for-db script it inlines feeds the checks
# below. Each value must be a plain integer found exactly where one is expected, or the check
# fails rather than guessing: wait-for-db's window (`-ge <N> ]` in the script), the startup
# probe's periodSeconds and failureThreshold (read between its `startupProbe:` and
# `failureThreshold:` lines), and the DB-backed Deployments' progressDeadlineSeconds.
template=charts/cafe-service/templates/deployment.yaml
wait_for_db_script=charts/cafe-service/files/wait-for-db.sh
deadline_lines=$(grep -cE '^ *progressDeadlineSeconds:' "$template" || true)
integer_deadline_lines=$(grep -cE '^ *progressDeadlineSeconds: *[0-9][0-9]* *$' "$template" || true)
deadlines=$(sed -n 's/^ *progressDeadlineSeconds: *\([0-9][0-9]*\) *$/\1/p' "$template")
window=$(sed -n 's/.*-ge \([0-9][0-9]*\) \];.*/\1/p' "$wait_for_db_script")
startup_probe=$(sed -n '/^ *startupProbe:/,/^ *failureThreshold:/p' "$template")
probe_period=$(sed -n 's/^ *periodSeconds: *\([0-9][0-9]*\) *$/\1/p' <<< "$startup_probe")
probe_threshold=$(sed -n 's/^ *failureThreshold: *\([0-9][0-9]*\) *$/\1/p' <<< "$startup_probe")
# Usage: exactly_one <value-list> - true when the list holds exactly one line.
exactly_one() { [ -n "$1" ] && [ "$(wc -l <<< "$1")" -eq 1 ]; }

[ "$integer_deadline_lines" -eq "$deadline_lines" ] && deadlines_are_integers=true || deadlines_are_integers=false
check "every progressDeadlineSeconds in the chart is a plain integer" "$deadlines_are_integers"

# The window only counts if the template really runs that script, and a missing file must fail the
# render rather than inline an empty one.
[ "$(grep -cF 'required "files/wait-for-db.sh is missing from the chart" (.Files.Get "files/wait-for-db.sh")' "$template" || true)" -eq 1 ] \
  && template_inlines_script=true || template_inlines_script=false
check "the Deployment template inlines ${wait_for_db_script} exactly once, behind \`required\`" "$template_inlines_script"

# A floor, not the whole budget: it covers wait-for-db's last attempt, the restart back-off and one
# readiness period (about 28s), with some room left for image pulls and a new node's startup.
startup_allowance=120
if exactly_one "$deadlines" && exactly_one "$window" && exactly_one "$probe_period" && exactly_one "$probe_threshold"; then
  [ "$deadlines" -ge $((window + probe_period * probe_threshold + startup_allowance)) ] \
    && deadline_covers_startup=true || deadline_covers_startup=false
  check "the DB-backed progressDeadlineSeconds (${deadlines}) covers wait-for-db's ${window}s window, the startup probe's ${probe_period}x${probe_threshold}s and ${startup_allowance}s more" "$deadline_covers_startup"
else
  check "the chart has exactly one progressDeadlineSeconds, wait-for-db window, startup-probe periodSeconds and failureThreshold to check against each other" false
fi

# Kubernetes' default, which a Deployment without the field (the gateway) gets, counts too.
largest_deadline=$( { [ -z "$deadlines" ] || echo "$deadlines"; echo 600; } | sort -n | tail -n 1)
if [[ $helm_timeout =~ ^([0-9]+)m$ ]]; then
  timeout_seconds=$((BASH_REMATCH[1] * 60))
  [ "$timeout_seconds" -ge $((largest_deadline + 120)) ] && timeout_exceeds_deadline=true || timeout_exceeds_deadline=false
  check "helm_timeout (${helm_timeout}) exceeds the largest progressDeadlineSeconds in effect (${largest_deadline}s) by at least 2 minutes" "$timeout_exceeds_deadline"
else
  check "helm_timeout (${helm_timeout}) is in whole minutes (Nm), the only form this check parses" false
fi

# --- the registry path stays in sync everywhere it is repeated ---
# These greps match whole lines, so they also depend on each file's exact YAML indentation.
[ "$(image_ref gateway abc123)" = "${expected_registry}/cafe-gateway:abc123" ] && image_ref_shaped=true || image_ref_shaped=false
check "image_ref builds <registry>/cafe-<service>:<tag>" "$image_ref_shaped"

grep -qxF -- "  imageRegistry: ${expected_registry}" charts/cafe/values.yaml && registry_matches_chart=true || registry_matches_chart=false
check "charts/cafe/values.yaml sets global.imageRegistry to the same registry path as deploy.sh" "$registry_matches_chart"

# shellcheck disable=SC2016 # the literal ${{ matrix.service }} expression, not a shell expansion
grep -qxF -- "      IMAGE: ${expected_registry}/cafe-"'${{ matrix.service }}' .github/workflows/backend-ci.yml && registry_matches_ci=true || registry_matches_ci=false
check "backend-ci.yml's IMAGE env pushes to the same registry path as deploy.sh" "$registry_matches_ci"

repositories_match_image_ref=true
for svc in "${services[@]}"; do
  grep -A1 -xF -- "${svc}:" charts/cafe/values.yaml | grep -qxF -- "  image: {repository: cafe-${svc}}" || repositories_match_image_ref=false
done
check "every service's values.yaml block sets image.repository to the cafe-<service> name image_ref uses" "$repositories_match_image_ref"

# --- the namespace matches the one the data layer is deployed into ---
# Matches block and flow style alike (`namespace: cafe`, `{name: …, namespace: cafe, …}`); every
# occurrence must name deploy.sh's namespace, and there must be at least one.
data_layer_namespaces=$(grep -rhoE 'namespace: *[a-z0-9-]+' k8s/data-layer/ | sort -u || true)
[ "$data_layer_namespaces" = "namespace: ${namespace}" ] && namespace_matches_data_layer=true || namespace_matches_data_layer=false
check "every namespace in k8s/data-layer/ is deploy.sh's namespace (${namespace})" "$namespace_matches_data_layer"

# --- the service list stays in sync everywhere it is repeated ---
# Neither the CI matrix nor the render check's services array can read Chart.yaml without an
# extra job, so the list is kept as copies and these checks compare them as sorted sets.
expected_services=$(printf '%s\n' "${services[@]}" | sort)

chart_aliases=$(sed -n 's/.*alias: *\([a-z-]*\).*/\1/p' charts/cafe/Chart.yaml | sort)
[ "$chart_aliases" = "$expected_services" ] && aliases_match=true || aliases_match=false
check "charts/cafe/Chart.yaml's dependency aliases are exactly deploy.sh's services" "$aliases_match"

ci_matrix=$(sed -n 's/^ *service: *\[\(.*\)\] *$/\1/p' .github/workflows/backend-ci.yml | tr ',' '\n' | tr -d ' ' | sort)
[ "$ci_matrix" = "$expected_services" ] && matrix_matches=true || matrix_matches=false
check "backend-ci.yml's build-and-push matrix is exactly deploy.sh's services" "$matrix_matches"

ci_services_lines=$(sed -n 's/^ *services=(\(.*\)) *$/\1/p' .github/workflows/backend-ci.yml)
if exactly_one "$ci_services_lines"; then
  ci_services=$(tr ' ' '\n' <<< "$ci_services_lines" | sort)
  [ "$ci_services" = "$expected_services" ] && render_services_match=true || render_services_match=false
  check "backend-ci.yml's render check services array is exactly deploy.sh's services" "$render_services_match"
else
  check "backend-ci.yml has exactly one services=(…) line, the render check's, to compare" false
fi

values_blocks_present=true
for svc in "${services[@]}"; do
  grep -qxF -- "${svc}:" charts/cafe/values.yaml || values_blocks_present=false
done
check "charts/cafe/values.yaml has a top-level block for every one of deploy.sh's services" "$values_blocks_present"

# ================================================================================================
# Part 2: main() as a real process
# ================================================================================================

# From here on git only touches the fixture repo, so it is isolated from the developer's own
# global and system config and their XDG files (a global ignore file, for one, would change which
# files count as untracked). Part 1 keeps them: it runs image-tag.sh and main()'s read-only git
# calls against the real repo, which may rely on a global safe.directory.
mkdir "$work/xdg"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 XDG_CONFIG_HOME="$work/xdg"
# Likewise for gcloud: main() gets this empty directory as its config, never the developer's own.
mkdir "$work/gcloud-config"

# Usage: call_line <command> <arg>... - prints one call the way the fakes log it: the command,
# then each argument shell-quoted (%q), so an argument containing spaces stays one argument
# instead of reading the same as several.
call_line() {
  printf '%s' "$1"
  shift
  (($#)) && printf ' %q' "$@"
  echo
}

# One fake for every cluster/registry tool, each a copy of $work/fake-tool in $fakes (which holds
# nothing else, since main() gets it first on PATH). Each call to a fake:
#   - appends its call_line to $FAKE_CALL_LOG;
#   - answers `helm version` with $FAKE_HELM_VERSION (default: a supported release), and prints
#     `fake-stdout: <its call_line>` for every other call, so a check can tell which tool's output
#     reaches deploy.sh's stdout;
#   - exits with the code of the first $FAKE_FAILS line ("<code> <glob>") whose glob matches the
#     call, or 0 when none does.
# gke-gcloud-auth-plugin is never called; it only has to exist for require_tools. The fake gets
# this suite's own call_line and runs under this suite's own bash ($BASH), because %q's output
# differs between bash versions: the logged and the expected calls must be quoted by the same bash
# to compare equal.
fake_tools=(kubectl helm gcloud gke-gcloud-auth-plugin)
{
  echo "#!$BASH"
  declare -f call_line
  cat <<'EOF'
call=$(call_line "$(basename "$0")" "$@")
echo "$call" >> "$FAKE_CALL_LOG"
if [[ $call == "helm version"* ]]; then
  echo "${FAKE_HELM_VERSION:-v4.3.0}"
else
  echo "fake-stdout: $call"
fi
while read -r code pattern; do
  if [[ -n $pattern && $call == $pattern ]]; then
    exit "$code"
  fi
done <<< "${FAKE_FAILS:-}"
exit 0
EOF
} > "$work/fake-tool"
for tool in "${fake_tools[@]}"; do
  cp "$work/fake-tool" "$fakes/$tool"
  chmod +x "$fakes/$tool"
done

# Refuse to run any scenario unless every fake really shadows the real tool on the PATH main()
# gets - the real kubectl/helm/gcloud may be installed on this machine.
for tool in "${fake_tools[@]}"; do
  resolved=$(PATH="$fakes:$PATH" type -P "$tool") || resolved=""
  if [ "$resolved" != "$fakes/$tool" ]; then
    echo "ABORT - fake $tool does not shadow the real one (resolved: ${resolved:-nothing})" >&2
    exit 1
  fi
done

# Refuses to go on unless git, run in $fixture, resolves to the fixture repo's own work tree
# and index - the last line of defence before the destructive reset/clean in reset_fixture.
assert_fixture_repo() {
  local top index
  top=$(git -C "$fixture" rev-parse --show-toplevel) || top=""
  index=$(git -C "$fixture" rev-parse --git-path index) || index=""
  if [ -z "$top" ] || ! [ "$top" -ef "$fixture" ] || [ "$index" != ".git/index" ]; then
    echo "ABORT - git in $fixture does not resolve to the fixture repo (top level: ${top:-?}, index: ${index:-?})" >&2
    exit 1
  fi
}

# The fixture repo: just enough files for image-tag.sh to hash each service, plus the
# working-tree deploy.sh and image-tag.sh under test and the real repo's own .gitignore. The
# cases below rely on its `target/`, `*-local.yml` and `charts/*/charts/*.tgz` patterns, and fail
# loudly if one of them goes.
(
  cd "$fixture"
  git init -q
  assert_fixture_repo
  git config user.name deploy-test
  git config user.email deploy-test@example.invalid
  git config core.autocrlf false
  mkdir -p scripts charts/cafe
  for dir in "${services[@]}" common-lib; do
    mkdir -p "backend/$dir"
    echo "$dir" > "backend/$dir/source"
  done
  echo pom > backend/pom.xml
  echo "name: cafe" > charts/cafe/Chart.yaml
  cp "$script_dir/../.gitignore" .
  echo readme > README.md
  cp "$script_dir/deploy.sh" "$script_dir/image-tag.sh" scripts/
  git add -A
  git -c core.hooksPath=/dev/null commit -q -m fixture
)
fixture_commit=$(git -C "$fixture" rev-parse HEAD)
fixture_branch=$(git -C "$fixture" symbolic-ref --short HEAD)

# Removing the index first also recovers from the corrupt-index scenario below, which
# `git reset --hard` alone cannot read past; the reset rebuilds it from the fixture commit (also
# undoing any commit a case added), which clears any assume-unchanged/skip-worktree bits too.
# HEAD goes back onto the fixture branch first, in case a case detached it, then every other
# local branch a case created is deleted. The fixture repo has no remote, so origin/master is a
# bare remote-tracking ref set to the fixture commit.
reset_fixture() {
  local refs ref
  assert_fixture_repo
  git -C "$fixture" symbolic-ref HEAD "refs/heads/$fixture_branch"
  refs=$(git -C "$fixture" for-each-ref --format='%(refname)' refs/heads/)
  while read -r ref; do
    [ -z "$ref" ] || [ "$ref" = "refs/heads/$fixture_branch" ] || git -C "$fixture" update-ref -d "$ref"
  done <<< "$refs"
  rm -f "$fixture/.git/index"
  git -C "$fixture" reset -q --hard "$fixture_commit"
  git -C "$fixture" clean -q -f -d -x
  git -C "$fixture" update-ref refs/remotes/origin/master "$fixture_commit"
}

# Exit code a subshell uses when it cannot `cd` where a case runs; deploy.sh, and the deploy.sh
# functions these subshells call, only ever exit or return 0 or 1, never 125. errexit is off
# inside a `( … ) || rc=$?` subshell, so without this a failed cd would carry on in the real repo
# and could even read as a pass.
cd_failed=125

# Usage: abort_if_cd_failed <rc> <dir> - ends the run when <rc> is cd_failed, i.e. a case's
# subshell couldn't cd to <dir>.
abort_if_cd_failed() {
  [ "$1" -ne "$cd_failed" ] || { echo "ABORT - cannot cd to $2" >&2; exit 1; }
}

# Usage: [VAR=value …] run_main <FAKE_FAILS lines or ""> [directory to run from]
# Runs the fixture repo's deploy.sh as its own process, from the given directory (default: the
# fixture repo) - by the documented relative path from the fixture repo, by absolute path from
# anywhere else - isolated from any real kubeconfig or gcloud config, leaving its exit code in
# `main_rc`, its stdout in $stdout_file, its stderr in $stderr_file and every fake call, in order,
# in $call_log.
run_main() {
  local fails=$1 from=${2:-$fixture} script=scripts/deploy.sh
  [ "$from" = "$fixture" ] || script=$fixture/scripts/deploy.sh
  : > "$call_log"
  main_rc=0
  (
    cd "$from" || exit "$cd_failed"
    PATH="$fakes:$PATH" FAKE_CALL_LOG="$call_log" FAKE_FAILS="$fails" \
      KUBECONFIG=/nonexistent CLOUDSDK_CONFIG="$work/gcloud-config" \
      bash "$script"
  ) > "$stdout_file" 2>"$stderr_file" || main_rc=$?
  abort_if_cd_failed "$main_rc" "$from"
}

# The exact calls a fully successful run makes, in order, with the tags image-tag.sh computes in
# the fixture repo. precheck_log is everything before the chart is built: precheck_prefix (the Helm
# version check, the gcloud startup check, then the context check), then registry_lookups (one per
# service, in services' order). The cases below that stop part-way through the lookups slice that
# array rather than counting lines. The repo-state checks before all of it only run git, which
# isn't faked.
helm_version_call=$(call_line helm version --template '{{.Version}}')
gcloud_info_call=$(call_line gcloud info --format='value(basic.version)')
context_check_call=$(call_line kubectl config get-contexts "$kube_context")
precheck_prefix=${helm_version_call}$'\n'${gcloud_info_call}$'\n'${context_check_call}
registry_lookups=()
upgrade_args=(helm upgrade --install cafe charts/cafe -n "$namespace"
  --kube-context "$kube_context" --wait=watcher --timeout "$helm_timeout" --description "commit $fixture_commit")
for svc in "${services[@]}"; do
  tag=$(cd "$fixture" && bash scripts/image-tag.sh "$svc") || { echo "ABORT - image-tag.sh failed for $svc in the fixture repo" >&2; exit 1; }
  registry_lookups+=("$(call_line gcloud artifacts docker images describe "$(image_ref "$svc" "$tag")")")
  upgrade_args+=(--set-string "${svc}.image.tag=${tag}")
done
upgrade_call=$(call_line "${upgrade_args[@]}")
precheck_log=$(printf '%s\n' "$precheck_prefix" "${registry_lookups[@]}")
dependency_build_call=$(call_line helm dependency build --skip-refresh charts/cafe)
get_pods_call=$(call_line kubectl --context "$kube_context" get pods -n "$namespace")
get_secret_call=$(call_line kubectl --context "$kube_context" get secret -n "$namespace")
dependency_build_failure_log=${precheck_log}$'\n'${dependency_build_call}
upgrade_failure_log=${dependency_build_failure_log}$'\n'${upgrade_call}$'\n'${get_pods_call}
expected_success_log=${dependency_build_failure_log}$'\n'${upgrade_call}$'\n'${get_pods_call}$'\n'${get_secret_call}
expected_success_stdout=$(printf 'fake-stdout: %s\n' "$dependency_build_call" "$upgrade_call" "$get_pods_call" "$get_secret_call")

# Usage: succeeded_cleanly - true when the last run_main exited 0, made exactly a fully
# successful run's calls, printed only their expected stdout and wrote nothing to stderr.
succeeded_cleanly() {
  [ "$main_rc" -eq 0 ] && [ "$(cat "$call_log")" = "$expected_success_log" ] \
    && [ "$(cat "$stdout_file")" = "$expected_success_stdout" ] && [ ! -s "$stderr_file" ]
}

# --- a fully successful run ---
reset_fixture
run_main ""
succeeded_cleanly && success_sequence=true || success_sequence=false
check "main: a successful run makes exactly the expected calls, in order, shows only the chart build's, upgrade's and report calls' output, writes nothing to stderr and exits 0" "$success_sequence"

# --- a Helm older than 4.1.1 ---
reset_fixture
FAKE_HELM_VERSION=v4.1.0 run_main ""
[ "$main_rc" -ne 0 ] && [ "$(cat "$call_log")" = "$helm_version_call" ] && grep -qF "4.1.1" "$stderr_file" \
  && old_helm_aborts=true || old_helm_aborts=false
check "main: a Helm older than 4.1.1 aborts naming the floor, before any cluster or registry call" "$old_helm_aborts"

# --- gcloud is on PATH but fails to start ---
reset_fixture
run_main "49 gcloud info *"
[ "$main_rc" -ne 0 ] && [ "$(cat "$call_log")" = "${helm_version_call}"$'\n'"${gcloud_info_call}" ] \
  && grep -qF "CLOUDSDK_PYTHON" "$stderr_file" && gcloud_start_failure_aborts=true || gcloud_start_failure_aborts=false
check "main: a gcloud that fails to start aborts with the CLOUDSDK_PYTHON hint, before the context check or any registry call" "$gcloud_start_failure_aborts"

# --- the kube-context check fails ---
reset_fixture
run_main "1 kubectl config get-contexts *"
[ "$main_rc" -ne 0 ] && [ "$(cat "$call_log")" = "$precheck_prefix" ] \
  && grep -qxF "If the gke_cafe-microservices_us-central1-a_cafe-cluster context is missing, run: gcloud container clusters get-credentials cafe-cluster --zone=us-central1-a --project=cafe-microservices" "$stderr_file" \
  && context_check_aborts=true || context_check_aborts=false
check "main: a failing context check aborts with the get-credentials hint before any registry call" "$context_check_aborts"

# --- HEAD must be on origin/master ---
# HEAD is one commit ahead of origin/master in each case. A local branch literally named
# origin/master, at HEAD, must not stand in for the remote-tracking ref.
not_on_master_cases=(
  "a HEAD one commit ahead of origin/master|:"
  "a local branch named origin/master at that HEAD|git branch origin/master"
)
for not_on_master_case in "${not_on_master_cases[@]}"; do
  IFS='|' read -r desc extra_step <<< "$not_on_master_case"
  reset_fixture
  git -C "$fixture" -c core.hooksPath=/dev/null commit -q --allow-empty -m "not on master"
  (cd "$fixture" && eval "$extra_step")
  run_main ""
  [ "$main_rc" -ne 0 ] && [ ! -s "$call_log" ] && grep -qF "isn't on origin/master" "$stderr_file" \
    && ! grep -qF "Couldn't check" "$stderr_file" && not_on_master_aborts=true || not_on_master_aborts=false
  check "main: $desc aborts naming origin/master, before any kubectl, registry or helm call" "$not_on_master_aborts"
done

reset_fixture
git -C "$fixture" update-ref -d refs/remotes/origin/master
run_main ""
# git's own error names the missing ref; neither of deploy.sh's messages contains refs/remotes.
[ "$main_rc" -ne 0 ] && [ ! -s "$call_log" ] && grep -qF "Couldn't check whether commit $fixture_commit is on origin/master" "$stderr_file" \
  && ! grep -qF "isn't on origin/master" "$stderr_file" && grep -qF "refs/remotes/origin/master" "$stderr_file" \
  && missing_master_ref_aborts=true || missing_master_ref_aborts=false
check "main: a missing origin/master ref aborts saying the check couldn't run, with git's own error, before any kubectl, registry or helm call" "$missing_master_ref_aborts"

# require_on_master checks the commit it is given, not whatever HEAD is: here HEAD is on
# origin/master, and only the commit passed in is ahead of it.
reset_fixture
git -C "$fixture" -c core.hooksPath=/dev/null commit -q --allow-empty -m "ahead of master"
ahead_commit=$(git -C "$fixture" rev-parse HEAD)
git -C "$fixture" checkout -q --detach "$fixture_commit"
rc=0
(
  cd "$fixture" || exit "$cd_failed"
  require_on_master "$ahead_commit"
) 2>"$stderr_file" || rc=$?
abort_if_cd_failed "$rc" "$fixture"
[ "$rc" -ne 0 ] && grep -qF "Commit $ahead_commit isn't on origin/master" "$stderr_file" \
  && checks_given_commit=true || checks_given_commit=false
check "require_on_master checks the commit it is given, not HEAD" "$checks_given_commit"

# A detached HEAD at an older master commit deploys that commit.
reset_fixture
git -C "$fixture" -c core.hooksPath=/dev/null commit -q --allow-empty -m "newer master commit"
git -C "$fixture" update-ref refs/remotes/origin/master HEAD
git -C "$fixture" checkout -q --detach "$fixture_commit"
run_main ""
succeeded_cleanly && older_master_commit_deploys=true || older_master_commit_deploys=false
check "main: a detached HEAD at an older origin/master commit deploys it cleanly" "$older_master_commit_deploys"

# --- uncommitted, git-hidden or git-ignored files the deploy depends on ---
# Each file is expected with the marker deploy.sh prints before it: git status' `XY` code, git
# ls-files -v's `h`/`S` tag, or `!! ` for a git-ignored file.
dirty_cases=(
  "a modified backend file| M backend/gateway/source|echo changed >> backend/gateway/source"
  "an untracked backend file|?? backend/gateway/New.java|echo new > backend/gateway/New.java"
  "a deleted backend file| D backend/gateway/source|rm backend/gateway/source"
  "a modified tracked charts file| M charts/cafe/Chart.yaml|echo '# changed' >> charts/cafe/Chart.yaml"
  "an untracked charts file|?? charts/new.yaml|echo new > charts/new.yaml"
  "a file in a new charts directory|?? charts/newdir/new.yaml|mkdir charts/newdir && echo new > charts/newdir/new.yaml"
  "a staged image-tag.sh change|M  scripts/image-tag.sh|echo '# changed' >> scripts/image-tag.sh && git add scripts/image-tag.sh"
  "an unstaged image-tag.sh change| M scripts/image-tag.sh|echo '# changed' >> scripts/image-tag.sh"
  "an assume-unchanged charts edit|h charts/cafe/Chart.yaml|git update-index --assume-unchanged charts/cafe/Chart.yaml && echo '# hidden' >> charts/cafe/Chart.yaml"
  "a skip-worktree charts edit|S charts/cafe/Chart.yaml|git update-index --skip-worktree charts/cafe/Chart.yaml && echo '# hidden' >> charts/cafe/Chart.yaml"
  "a charts edit hidden both ways|s charts/cafe/Chart.yaml|git update-index --assume-unchanged charts/cafe/Chart.yaml && git update-index --skip-worktree charts/cafe/Chart.yaml && echo '# hidden' >> charts/cafe/Chart.yaml"
  "a git-ignored chart template|!! charts/cafe-service/templates/debug-local.yml|mkdir -p charts/cafe-service/templates && echo debug > charts/cafe-service/templates/debug-local.yml"
  "a git-ignored package inside a subchart|!! charts/cafe-service/charts/stray.tgz|mkdir -p charts/cafe-service/charts && echo stray > charts/cafe-service/charts/stray.tgz"
  "a git-ignored package nested below the regenerated one|!! charts/cafe/charts/target/x.tgz|mkdir -p charts/cafe/charts/target && echo x > charts/cafe/charts/target/x.tgz"
  "a staged rename under charts|R  charts/cafe/Chart.yaml -> charts/cafe/Renamed.yaml|git mv charts/cafe/Chart.yaml charts/cafe/Renamed.yaml"
  "a staged backend deletion|D  backend/gateway/source|git rm -q backend/gateway/source"
  "the leftover of an interrupted helm dependency build|?? charts/cafe/tmpcharts-4242/cafe-service-0.1.0.tgz|mkdir -p charts/cafe/tmpcharts-4242 && echo partial > charts/cafe/tmpcharts-4242/cafe-service-0.1.0.tgz"
)
for dirty_case in "${dirty_cases[@]}"; do
  IFS='|' read -r desc path change <<< "$dirty_case"
  reset_fixture
  (cd "$fixture" && eval "$change")
  run_main ""
  [ "$main_rc" -ne 0 ] && [ ! -s "$call_log" ] && grep -qF -- "$path" "$stderr_file" \
    && dirty_aborts=true || dirty_aborts=false
  check "main: $desc aborts naming '$path', before any kubectl, registry or helm call" "$dirty_aborts"
done

# --- a git-hidden file also gets the hint for un-hiding it ---
reset_fixture
(cd "$fixture" && git update-index --skip-worktree charts/cafe/Chart.yaml)
run_main ""
[ "$main_rc" -ne 0 ] && grep -qF -- "--no-skip-worktree" "$stderr_file" && hidden_hint_shown=true || hidden_hint_shown=false
check "main: a git-hidden file comes with the hint for clearing assume-unchanged/skip-worktree" "$hidden_hint_shown"

# --- an untracked file stays visible whatever git config says about untracked files ---
printf '[status]\n\tshowUntrackedFiles = no\n' > "$work/no-untracked.gitconfig"
reset_fixture
echo new > "$fixture/charts/new.yaml"
GIT_CONFIG_GLOBAL="$work/no-untracked.gitconfig" run_main ""
[ "$main_rc" -ne 0 ] && [ ! -s "$call_log" ] && grep -qF -- "?? charts/new.yaml" "$stderr_file" \
  && untracked_despite_config=true || untracked_despite_config=false
check "main: an untracked charts file aborts even with status.showUntrackedFiles=no" "$untracked_despite_config"

# --- git itself fails (a corrupt index) ---
reset_fixture
echo garbage > "$fixture/.git/index"
run_main ""
[ "$main_rc" -ne 0 ] && [ ! -s "$call_log" ] && ! grep -q "Uncommitted" "$stderr_file" \
  && git_status_failure_aborts=true || git_status_failure_aborts=false
check "main: a failing git aborts before any kubectl, registry or helm call, without claiming uncommitted files" "$git_status_failure_aborts"

# --- each of require_committed_inputs' git calls failing on its own ---
for failing in "status" "ls-files -v" "ls-files --others"; do
  reset_fixture
  rc=0
  (
    cd "$fixture" || exit "$cd_failed"
    # shellcheck disable=SC2317,SC2329 # invoked indirectly, by require_committed_inputs
    git() { if [[ "$*" == "$failing"* ]]; then return 1; fi; command git "$@"; }
    require_committed_inputs
  ) 2>"$stderr_file" || rc=$?
  abort_if_cd_failed "$rc" "$fixture"
  [ "$rc" -ne 0 ] && ! grep -q "Uncommitted" "$stderr_file" && git_call_failure_aborts=true || git_call_failure_aborts=false
  check "require_committed_inputs: a failing 'git ${failing}' fails it, without claiming uncommitted files" "$git_call_failure_aborts"
done

# --- uncommitted or git-ignored files the deploy does not depend on ---
clean_enough_cases=(
  "a modified README.md|echo changed >> README.md"
  "a modified scripts/deploy.sh|echo '# changed' >> scripts/deploy.sh"
  "an ignored target/ file under backend|mkdir -p backend/gateway/target && echo built > backend/gateway/target/app.jar"
  "the packaged subchart helm dependency build regenerates|mkdir -p charts/cafe/charts && echo packaged > charts/cafe/charts/cafe-service-0.1.0.tgz"
  "that packaged subchart force-added and marked assume-unchanged|mkdir -p charts/cafe/charts && echo packaged > charts/cafe/charts/cafe-service-0.1.0.tgz && git add -f charts/cafe/charts/cafe-service-0.1.0.tgz && git update-index --assume-unchanged charts/cafe/charts/cafe-service-0.1.0.tgz"
  "that packaged subchart even when .gitignore stops ignoring it|echo '!charts/*/charts/*.tgz' >> .gitignore && mkdir -p charts/cafe/charts && echo packaged > charts/cafe/charts/cafe-service-0.1.0.tgz"
  "an untracked script other than image-tag.sh|echo '# other' > scripts/other.sh"
)
for clean_enough_case in "${clean_enough_cases[@]}"; do
  IFS='|' read -r desc change <<< "$clean_enough_case"
  reset_fixture
  (cd "$fixture" && eval "$change")
  run_main ""
  succeeded_cleanly && ignored_change_deploys=true || ignored_change_deploys=false
  check "main: $desc doesn't stop a clean deploy" "$ignored_change_deploys"
done

# --- the caller's environment can't point it somewhere else ---
git init -q "$work/other"
reset_fixture
GIT_DIR="$work/other/.git" GIT_INDEX_FILE="$work/other/.git/index" run_main ""
succeeded_cleanly && ignores_git_env=true || ignores_git_env=false
check "main: an inherited GIT_DIR/GIT_INDEX_FILE doesn't redirect its git calls away from this checkout; it deploys cleanly" "$ignores_git_env"

reset_fixture
GIT_DIR=/nonexistent run_main ""
succeeded_cleanly && ignores_stale_git_dir=true || ignores_stale_git_dir=false
check "main: an inherited GIT_DIR pointing nowhere doesn't break its git calls; it deploys cleanly" "$ignores_stale_git_dir"

mkdir -p "$work/cdpath/scripts"
reset_fixture
CDPATH="$work/cdpath" run_main ""
succeeded_cleanly && ignores_cdpath=true || ignores_cdpath=false
check "main: an inherited CDPATH with its own scripts/ doesn't change where it runs from; it deploys cleanly" "$ignores_cdpath"

# --- the commit being deployed can't be read ---
# A git wrapper first on PATH fails only deploy.sh's own `git rev-parse HEAD`, printing a marker
# so the check can tell that read apart from any later failure; every repo-state check before it
# passes silently.
git_no_head_marker="git-no-head: rev-parse HEAD refused"
mkdir "$work/git-no-head"
cat > "$work/git-no-head/git" <<EOF
#!/usr/bin/env bash
if [[ "\$*" == "rev-parse HEAD" ]]; then echo "$git_no_head_marker" >&2; exit 1; fi
exec "\$REAL_GIT" "\$@"
EOF
chmod +x "$work/git-no-head/git"
reset_fixture
REAL_GIT=$(type -P git) PATH="$work/git-no-head:$PATH" run_main ""
[ "$main_rc" -ne 0 ] && [ ! -s "$call_log" ] && [ "$(cat "$stderr_file")" = "$git_no_head_marker" ] \
  && unreadable_commit_aborts=true || unreadable_commit_aborts=false
check "main: an unreadable HEAD commit aborts at that read, before the master check and any kubectl, registry or helm call" "$unreadable_commit_aborts"

# --- build_set_args computes tags at the ref it is given, not at HEAD ---
# HEAD is one commit past the fixture commit and changes gateway's source, so the two tags differ.
reset_fixture
echo changed >> "$fixture/backend/gateway/source"
git -C "$fixture" -c core.hooksPath=/dev/null commit -q -a -m "change gateway"
ref_tag=$(cd "$fixture" && bash scripts/image-tag.sh gateway "$fixture_commit") || ref_tag=""
head_tag=$(cd "$fixture" && bash scripts/image-tag.sh gateway) || head_tag=""
rc=0
ref_args=$(
  cd "$fixture" || exit "$cd_failed"
  missing_service=""
  set_args=()
  build_set_args set_args recording_exists "$fixture_commit" gateway || exit 1
  echo "${set_args[*]}"
) 2>"$stderr_file" || rc=$?
abort_if_cd_failed "$rc" "$fixture"
[ "$rc" -eq 0 ] && [ -n "$ref_tag" ] && [ "$ref_tag" != "$head_tag" ] \
  && [ "$ref_args" = "--set-string gateway.image.tag=${ref_tag}" ] && tags_follow_ref=true || tags_follow_ref=false
check "build_set_args computes each tag at the git ref it is given, not at HEAD" "$tags_follow_ref"

# --- a missing image, at the first, a middle and the last position ---
for pos in 0 $((${#services[@]} / 2)) $((${#services[@]} - 1)); do
  svc=${services[$pos]}
  reset_fixture
  run_main "1 gcloud artifacts docker images describe */cafe-${svc}:*"
  expected_log=$(printf '%s\n' "$precheck_prefix" "${registry_lookups[@]:0:pos+1}")
  [ "$main_rc" -ne 0 ] && [ "$(cat "$call_log")" = "$expected_log" ] && grep -q "MISSING.*cafe-${svc}:" "$stderr_file" \
    && grep -qF "committed at $fixture_commit" "$stderr_file" && missing_image_aborts=true || missing_image_aborts=false
  check "main: a missing ${svc} image (service $((pos + 1)) of ${#services[@]}) aborts naming it and the commit its tag came from, with no later check and no chart build or upgrade" "$missing_image_aborts"
done

# --- a service whose tag cannot be computed at HEAD (its directory is gone from the commit) ---
# The last service is dropped, so every service before it is still looked up.
last_service=${services[${#services[@]} - 1]}
reset_fixture
git -C "$fixture" rm -r -q "backend/$last_service"
git -C "$fixture" -c core.hooksPath=/dev/null commit -q -m "drop $last_service"
git -C "$fixture" update-ref refs/remotes/origin/master HEAD
run_main ""
expected_log=$(printf '%s\n' "$precheck_prefix" "${registry_lookups[@]:0:${#services[@]}-1}")
[ "$main_rc" -ne 0 ] && [ "$(cat "$call_log")" = "$expected_log" ] && unresolvable_tag_aborts=true || unresolvable_tag_aborts=false
check "main: a service image-tag.sh cannot resolve aborts after checking the services before it, with no chart build or upgrade" "$unresolvable_tag_aborts"

# --- helm dependency build fails ---
reset_fixture
run_main "1 helm dependency build *"
[ "$main_rc" -ne 0 ] && [ "$(cat "$call_log")" = "$dependency_build_failure_log" ] && dependency_build_aborts=true || dependency_build_aborts=false
check "main: a failing helm dependency build aborts before the upgrade" "$dependency_build_aborts"

# --- helm upgrade fails ---
reset_fixture
run_main "1 helm upgrade *"
[ "$main_rc" -eq 1 ] && [ "$(cat "$call_log")" = "$upgrade_failure_log" ] && grep -q "Step 8 Troubleshooting" "$stderr_file" \
  && upgrade_failure_reported=true || upgrade_failure_reported=false
check "main: a failing helm upgrade lists the pods (not the secrets), points to the runbook and exits 1" "$upgrade_failure_reported"

reset_fixture
run_main $'1 helm upgrade *\n1 kubectl --context * get pods *'
[ "$main_rc" -eq 1 ] && grep -q "warning: could not run kubectl get pods" "$stderr_file" && grep -q "Step 8 Troubleshooting" "$stderr_file" \
  && pointer_survives_unreachable_cluster=true || pointer_survives_unreachable_cluster=false
check "main: when listing pods also fails after a failed upgrade, it warns and still points to the runbook, exiting 1" "$pointer_survives_unreachable_cluster"

# --- the deploy succeeds but reporting cluster state fails ---
for failing_get in pods secret; do
  reset_fixture
  run_main "1 kubectl --context * get ${failing_get} *"
  [ "$main_rc" -eq 0 ] && [ "$(cat "$call_log")" = "$expected_success_log" ] && grep -q "warning: could not run kubectl get ${failing_get}" "$stderr_file" \
    && report_failure_only_warns=true || report_failure_only_warns=false
  check "main: a failing 'get ${failing_get}' after a successful deploy only warns, still runs every report call and exits 0" "$report_failure_only_warns"
done

# --- the working directory it is started from does not matter ---
reset_fixture
run_main "" "$work"
succeeded_cleanly && runs_from_anywhere=true || runs_from_anywhere=false
check "main: started from outside the repo, it makes the same calls, with the same output, as from the repo root" "$runs_from_anywhere"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
