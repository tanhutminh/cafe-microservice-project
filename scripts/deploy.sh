#!/usr/bin/env bash
# Deploys the 6 backend services' images for the checked-out commit to the real GKE cluster:
# computes each service's content-hash tag from HEAD's committed content (see image-tag.sh),
# verifies that exact image already exists in Artifact Registry, and only then runs
# `helm upgrade --install --wait=watcher`, returning once every Deployment is ready.
#
# Aborts before any helm upgrade, in this order, if:
#   - bash is older than 4.3;
#   - a required tool isn't on PATH;
#   - backend/, charts/ or image-tag.sh has uncommitted changes or files hidden from git status
#     (assume-unchanged, skip-worktree), or charts/ holds git-ignored files: a deploy must
#     correspond to one commit;
#   - HEAD can't be read, or isn't on origin/master;
#   - Helm is older than 4.1.1;
#   - gcloud is on PATH but fails to start;
#   - the GKE kube-context is missing;
#   - a service's tag can't be computed at HEAD, or its image is missing (checked service by
#     service).
# A tag with no matching image would only produce a silent ImagePullBackOff later. The upgrade
# also never starts if a git command or `helm dependency build` fails.
#
# Tested by deploy.test.sh.
#
# Usage: bash scripts/deploy.sh (from any directory - main() changes to the repo root itself)
set -euo pipefail

# An inherited CDPATH would make the relative `cd` below resolve against it instead of here.
unset CDPATH
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

services=(gateway auth-service menu-service order-service inventory-service report-service)

gcp_project=cafe-microservices
cluster_zone=us-central1-a
cluster_name=cafe-cluster

# The context `gcloud container clusters get-credentials` creates for this cluster, named
# gke_<project>_<zone>_<cluster>. Passed explicitly to every call that talks to the cluster, so a
# deploy never lands on whatever cluster (e.g. a local kind or docker-desktop one) the current
# kube-context points at.
kube_context="gke_${gcp_project}_${cluster_zone}_${cluster_name}"

# The namespace the release goes into. The chart's templates take theirs from .Release.Namespace,
# so this is what puts the app there; it must match the namespace k8s/data-layer/ and the Workload
# Identity bindings use (docs/gke-cicd-runbook.md: Step 1 creates it, Step 2 binds it).
# deploy.test.sh fails if k8s/data-layer/ drifts from it.
namespace=cafe

# How long `helm upgrade --wait=watcher` waits for every Deployment to become ready. With the
# watcher strategy of Helm 4.1.1 or newer (the floor main() enforces), a Deployment past its
# progressDeadlineSeconds (set in charts/cafe-service/templates/deployment.yaml) is marked Failed
# ("Progress deadline exceeded") and the wait returns once every other resource has settled;
# earlier Helm 4 releases wait out the full timeout instead. This timeout must exceed the
# largest such deadline by at least 2 minutes (Helm's clock starts before the Deployment's
# does), so the deadline, not a bare timeout, ends a stalled rollout; deploy.test.sh fails if it
# doesn't.
helm_timeout=22m

# The registry path is repeated in charts/cafe/values.yaml (global.imageRegistry) and in the
# IMAGE env of .github/workflows/backend-ci.yml - keep all three in sync; deploy.test.sh fails
# if any of them drifts.
image_ref() {
  local svc=$1 tag=$2
  echo "us-central1-docker.pkg.dev/cafe-microservices/cafe-images/cafe-${svc}:${tag}"
}

# Returns 0 when the image ref $1 exists in Artifact Registry, non-zero when it doesn't or the
# lookup fails. gcloud's stderr is deliberately left visible, so an auth/permission/network
# failure shows its real cause instead of looking like a missing image. It runs with the
# operator's own credentials, not the ones the cluster's nodes pull with.
image_exists() {
  gcloud artifacts docker images describe "$1" > /dev/null
}

# Usage: build_set_args <out-array-name> <exists-check> <git-ref> <service>...
# Appends a `--set-string <service>.image.tag=<tag>` pair per service to the array named by
# <out-array-name>, each tag computed from <git-ref>'s committed content. Aborts (return 1) the
# moment one service's tag can't be computed or its image is missing, without checking any service
# after it. <exists-check> is the name of a function shaped like image_exists (image-ref -> 0 if it
# exists, non-zero otherwise) - injected, so a fake can stand in for gcloud. Works from any working
# directory inside the repository. Names starting with `_bsa_` are reserved: an <out-array-name>
# matching one of this function's own locals would resolve to that local instead of the caller's
# array.
build_set_args() {
  # `|| return 1`: on a bash without namerefs, `local -n` only prints a usage error and the
  # function would otherwise carry on.
  local -n _bsa_out=$1 || return 1
  local _bsa_exists_check=$2 _bsa_ref=$3
  shift 3
  local _bsa_svc _bsa_tag _bsa_image
  for _bsa_svc in "$@"; do
    # `|| return 1` keeps a failing image-tag.sh fatal even when the caller invokes this
    # function in a `||`/`if` context, where bash disables errexit inside the function.
    _bsa_tag=$(bash "$script_dir/image-tag.sh" "$_bsa_svc" "$_bsa_ref") || return 1
    _bsa_image=$(image_ref "$_bsa_svc" "$_bsa_tag")
    if ! "$_bsa_exists_check" "$_bsa_image"; then
      printf '%s\n' \
        "MISSING or inaccessible: $_bsa_image" \
        "If gcloud's error above is not a not-found error (authentication, permission or network, for example), fix that first, e.g. \`gcloud auth login\`, and rerun." \
        "Otherwise: tags are computed from the backend/ content committed at $_bsa_ref, and CI builds images only from master." \
        "So either $_bsa_ref is a master commit no CI run built (e.g. one inside a merged branch) - deploy a commit CI built, such as master's tip or a merge commit - or master's backend-ci run for it is still running or failed (wait, or fix it)." \
        "If master has that content but no run built it, run backend-ci via workflow_dispatch on master (docs/gke-cicd-runbook.md, Step 9)." >&2
      return 1
    fi
    _bsa_out+=(--set-string "${_bsa_svc}.image.tag=${_bsa_tag}")
  done
}

# Usage: require_bash_version <major> <minor>
# Returns 1 with a message when the running bash is older than <major>.<minor>.
require_bash_version() {
  local need_major=$1 need_minor=$2 have_major=${BASH_VERSINFO[0]} have_minor=${BASH_VERSINFO[1]}
  if ((have_major > need_major || (have_major == need_major && have_minor >= need_minor))); then
    return 0
  fi
  echo "deploy.sh needs bash ${need_major}.${need_minor} or newer, but this is bash ${BASH_VERSION}" >&2
  return 1
}

# Usage: require_helm_version <major> <minor> <patch>
# Returns 1 with a message unless `helm version` reports at least <major>.<minor>.<patch>. helm's
# own stderr stays visible, so a helm that can't even report its version shows why.
require_helm_version() {
  local need_major=$1 need_minor=$2 need_patch=$3
  local need_version="${need_major}.${need_minor}.${need_patch}" have_version
  if ! have_version=$(helm version --template '{{.Version}}') \
    || ! [[ $have_version =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
    echo "deploy.sh needs Helm ${need_version} or newer, but couldn't read the version of the helm on PATH" >&2
    return 1
  fi
  local have_major=${BASH_REMATCH[1]} have_minor=${BASH_REMATCH[2]} have_patch=${BASH_REMATCH[3]}
  if ((have_major > need_major || (have_major == need_major
    && (have_minor > need_minor || (have_minor == need_minor && have_patch >= need_patch))))); then
    return 0
  fi
  echo "deploy.sh needs Helm ${need_version} or newer, but this is Helm ${have_version}" >&2
  return 1
}

# Returns 1 with a hint unless the gcloud on PATH actually starts: being on PATH is not enough, as
# gcloud is a launcher that also needs a Python it can find. `gcloud info` rather than
# `gcloud --version`, which also prints an update notice on stderr on every call. gcloud's own
# stderr stays visible, so its real error shows.
require_gcloud_runs() {
  if ! gcloud info --format='value(basic.version)' > /dev/null; then
    printf '%s\n' \
      "gcloud is on PATH but fails to start (see its error above)." \
      "On Git Bash for Windows, set CLOUDSDK_PYTHON to the Cloud SDK's bundled Python - docs/gke-cicd-runbook.md, Prerequisites." >&2
    return 1
  fi
}

# Usage: require_tools <command>...
# Returns 1, naming every missing one, unless each command is on PATH - so a missing tool fails
# here by name instead of later as a misleading "image missing" or "context missing".
require_tools() {
  local tool missing=()
  for tool in "$@"; do
    command -v "$tool" > /dev/null || missing+=("$tool")
  done
  if ((${#missing[@]} > 0)); then
    echo "Not installed or not on PATH: ${missing[*]}" >&2
    return 1
  fi
}

# Returns 1 with a hint when the kube_context context doesn't exist. Only stdout is silenced:
# kubectl's own stderr names the real cause.
require_kube_context() {
  if ! kubectl config get-contexts "$kube_context" > /dev/null; then
    echo "If the $kube_context context is missing, run: gcloud container clusters get-credentials $cluster_name --zone=$cluster_zone --project=$gcp_project" >&2
    return 1
  fi
}

# Returns 1, listing the offending files, when backend/, charts/ or scripts/image-tag.sh has
# staged, unstaged or untracked changes (git status' own `XY` codes) or files git status is told
# to skip (assume-unchanged or skip-worktree; git ls-files -v's own `h`/`s`/`S` tags). It also
# returns 1 when charts/ holds git-ignored files (`!! `, git status' notation), or when git itself
# fails. A deploy must correspond to one commit: the images come from committed backend/ code, so
# local backend edits would silently not be deployed, while charts/ and image-tag.sh are used
# as-is from the working tree, so edits to them would be deployed without being recorded in any
# commit. Helm packages every file in a chart directory not excluded by its .helmignore,
# git-ignored or not; charts/cafe/charts/*.tgz is exempt from every check here, since
# `helm dependency build` regenerates it on every deploy. scripts/deploy.sh itself is exempt, so
# edits to the script can be tried before committing; release content belongs in the chart, not
# in deploy.sh's `helm` flags. Expects the repo root as the working directory.
require_committed_inputs() {
  local inputs=(backend charts scripts/image-tag.sh)
  local regenerated=':(exclude,glob)charts/cafe/charts/*.tgz'
  local uncommitted hidden ignored
  uncommitted=$(git status --porcelain --untracked-files=all -- "${inputs[@]}" "$regenerated") || return 1
  hidden=$(git ls-files -v -- "${inputs[@]}" "$regenerated" | sed -n '/^[[:lower:]S] /p') || return 1
  ignored=$(git ls-files --others --ignored --exclude-standard -- charts "$regenerated" | sed 's/^/!! /') || return 1
  if [[ -n $uncommitted || -n $hidden || -n $ignored ]]; then
    printf '%s\n' \
      "Uncommitted, git-hidden or git-ignored files below. deploy.sh deploys only what a commit describes:" \
      "images come from committed backend/ code; charts/ and image-tag.sh are used as-is from the working tree." \
      "Commit, stash or remove them (git-ignored files: move or delete them):" >&2
    [[ -z $uncommitted ]] || echo "$uncommitted" >&2
    if [[ -n $hidden ]]; then
      echo "$hidden" >&2
      echo "(h/s/S: git status skips these; clear with git update-index --no-assume-unchanged / --no-skip-worktree <file>)" >&2
    fi
    [[ -z $ignored ]] || echo "$ignored" >&2
    return 1
  fi
}

# Usage: require_on_master <commit>
# Returns 1 with a hint unless <commit> is on master: the commit origin/master points at, as last
# fetched, or one of its ancestors. CI builds images only from master, and charts/ is deployed
# from the working tree, so a commit that isn't on master would put chart changes no merge has
# recorded on the cluster. The full refs/remotes/origin/master name keeps a local branch that
# happens to be called origin/master from standing in for it. git's own stderr stays visible, so a
# failed check (the ref missing, for one) shows its real cause. Expects the repo root as the
# working directory.
require_on_master() {
  local commit=$1 rc=0
  git merge-base --is-ancestor "$commit" refs/remotes/origin/master || rc=$?
  case $rc in
    0) return 0 ;;
    1) echo "Commit $commit isn't on origin/master: merge it through a PR and deploy from master, or run git fetch if it already is" >&2 ;;
    *) echo "Couldn't check whether commit $commit is on origin/master (see git's error above); run git fetch and retry" >&2 ;;
  esac
  return 1
}

# Runs `kubectl --context <kube_context> <args>` to show cluster state. A failure only prints a
# warning, so reporting never changes the script's exit code.
report_cluster_state() {
  kubectl --context "$kube_context" "$@" \
    || echo "warning: could not run kubectl $* - the cluster may be unreachable" >&2
}

main() {
  # build_set_args needs a nameref (`local -n`).
  require_bash_version 4 3 || exit 1
  # sha256sum: image-tag.sh uses it, and unlike sed/cut it isn't everywhere (macOS before 15
  # (Sequoia) lacks it).
  require_tools gcloud helm kubectl gke-gcloud-auth-plugin git sha256sum || exit 1

  # An inherited GIT_DIR, GIT_INDEX_FILE, etc. would make every git call here - and image-tag.sh's
  # tag computation - read some other repository instead of this checkout.
  local git_env
  git_env=$(git rev-parse --local-env-vars) || exit 1
  # shellcheck disable=SC2086 # deliberately split into one variable name per word
  unset $git_env

  cd "$script_dir/.."

  # Repo state before environment state: a deploy that can't correspond to one commit is refused
  # whatever cluster it would target.
  require_committed_inputs || exit 1
  # Read once, so the master check, every image tag and the release's description all name the
  # same commit even if HEAD moves mid-run (charts/ is still read from the working tree). The
  # description lets `helm history` show which commit each deployed revision came from.
  local commit
  commit=$(git rev-parse HEAD) || exit 1
  require_on_master "$commit" || exit 1
  # helm_timeout relies on the watcher strategy failing a stalled Deployment early.
  require_helm_version 4 1 1 || exit 1
  require_gcloud_runs || exit 1
  require_kube_context || exit 1

  local set_args=()
  build_set_args set_args image_exists "$commit" "${services[@]}" || exit 1

  # `build`, not `update`: re-packages the file:// subchart from the current charts/cafe-service
  # (so a stale local .tgz never deploys) against the committed Chart.lock, without rewriting it.
  # `--skip-refresh`: the only dependency is file://, so there is no reason to contact any Helm
  # repository configured on this machine.
  helm dependency build --skip-refresh charts/cafe || exit 1

  if ! helm upgrade --install cafe charts/cafe -n "$namespace" --kube-context "$kube_context" \
    --wait=watcher --timeout "$helm_timeout" --description "commit $commit" "${set_args[@]}"; then
    report_cluster_state get pods -n "$namespace"
    echo "helm upgrade failed or timed out - see docs/gke-cicd-runbook.md, Step 8 Troubleshooting" >&2
    exit 1
  fi
  report_cluster_state get pods -n "$namespace"
  report_cluster_state get secret -n "$namespace"
}

# Only run main when executed directly; sourcing this file defines its functions and variables
# and applies its `set -euo pipefail` and `unset CDPATH` to the sourcing shell, without running
# main.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main
fi
