#!/usr/bin/env bash
# Behavioral tests for image-tag.sh. Run directly: `bash scripts/image-tag.test.sh`.
# Every case runs image-tag.sh inside a throwaway git repository in a temporary directory, on
# fixture commits built via plumbing (hash-object/mktree/commit-tree), so the suite never writes
# to this repository: no objects, refs or files. The temporary directory is removed on exit.
set -euo pipefail

# Inherited git variables (set, for example, inside a git hook) would point the git commands
# below, which write objects and move HEAD, at the repository they name, not the throwaway one.
git_env=$(git rev-parse --local-env-vars) || exit 1
# shellcheck disable=SC2086 # deliberately split into one variable name per word
unset $git_env
# GIT_TEMPLATE_DIR isn't in that list, but an inherited one would make `git init` below copy its
# hooks into the throwaway repo, where git would run them (update-ref runs reference-transaction).
unset GIT_TEMPLATE_DIR

# An inherited CDPATH would make the relative `cd` below resolve against it instead of here.
unset CDPATH
script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/image-tag.sh"

# Everything the suite writes lives under $work. The trap is set before the mktemp, so it is
# already in place once work is assigned; until then work is empty and the trap removes nothing.
work=""
cleanup() {
  rm -rf -- "$work"
}
trap cleanup EXIT
work=$(mktemp -d)
# Neither the system's nor the user's git config applies, so a setting such as commit.gpgSign
# can't change how the fixtures are built.
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
git init -q "$work/repo"
cd "$work/repo"

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

blob() { git hash-object -w --stdin <<<"$1"; }

# backend/svc-a, backend/svc-b, backend/common-lib, backend/pom.xml, backend/Dockerfile and,
# optionally, backend/.dockerignore as plain blobs - image-tag.sh only ever hashes the object id
# found at each path, so a blob stands in for a real directory tree just as well for this
# purpose. An empty <common_lib>, <pom> or <dockerfile> leaves that path out of the tree, to test
# a ref that lacks it; so does an empty or omitted <dockerignore>.
mktree_commit() {
  local svc_a=$1 svc_b=$2 common_lib=$3 pom=$4 dockerfile=$5 dockerignore=${6:-}
  local backend_tree root_tree
  backend_tree=$(
    {
      printf '100644 blob %s\tsvc-a\n100644 blob %s\tsvc-b\n' "$svc_a" "$svc_b"
      [ -z "$common_lib" ] || printf '100644 blob %s\tcommon-lib\n' "$common_lib"
      [ -z "$pom" ] || printf '100644 blob %s\tpom.xml\n' "$pom"
      [ -z "$dockerfile" ] || printf '100644 blob %s\tDockerfile\n' "$dockerfile"
      [ -z "$dockerignore" ] || printf '100644 blob %s\t.dockerignore\n' "$dockerignore"
    } | git mktree
  )
  root_tree=$(printf '040000 tree %s\tbackend\n' "$backend_tree" | git mktree)
  GIT_AUTHOR_NAME="image-tag-test" GIT_AUTHOR_EMAIL="image-tag-test@localhost" \
    GIT_COMMITTER_NAME="image-tag-test" GIT_COMMITTER_EMAIL="image-tag-test@localhost" \
    git commit-tree "$root_tree" -m "image-tag.sh test fixture" </dev/null
}

tag() { bash "$script" "$1" "$2"; }

# Runs image-tag.sh with the given arguments and checks that it fails cleanly: a non-zero exit
# and nothing on stdout, which a caller would read as the tag. <subject> begins the description
# of both checks.
check_fails() {
  local subject=$1 out code=0 exits_nonzero prints_no_tag
  shift
  out=$(bash "$script" "$@" 2>/dev/null) || code=$?
  [ "$code" -ne 0 ] && exits_nonzero=true || exits_nonzero=false
  check "$subject exits non-zero" "$exits_nonzero"
  [ -z "$out" ] && prints_no_tag=true || prints_no_tag=false
  check "$subject prints no tag to stdout" "$prints_no_tag"
}

svc_a_1=$(blob "svc-a v1")
svc_a_2=$(blob "svc-a v2")
svc_b_1=$(blob "svc-b v1")
common_lib_1=$(blob "common-lib v1")
common_lib_2=$(blob "common-lib v2")
pom_1=$(blob "pom v1")
pom_2=$(blob "pom v2")
dockerfile_1=$(blob "Dockerfile v1")
dockerfile_2=$(blob "Dockerfile v2")
dockerignore_1=$(blob "dockerignore v1")

base_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_1" "$dockerfile_1")
svc_a_changed_ref=$(mktree_commit "$svc_a_2" "$svc_b_1" "$common_lib_1" "$pom_1" "$dockerfile_1")
common_lib_changed_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_2" "$pom_1" "$dockerfile_1")
pom_changed_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_2" "$dockerfile_1")
dockerfile_changed_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_1" "$dockerfile_2")
dockerignore_added_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_1" "$dockerfile_1" "$dockerignore_1")
no_common_lib_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "" "$pom_1" "$dockerfile_1")
no_pom_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "" "$dockerfile_1")
no_dockerfile_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_1" "")

base_a=$(tag svc-a "$base_ref")
base_b=$(tag svc-b "$base_ref")

[ "$(tag svc-a "$base_ref")" = "$base_a" ] && tag_is_deterministic=true || tag_is_deterministic=false
check "determinism: same content yields the same tag" "$tag_is_deterministic"

# The other checks only compare tags with each other, so they would also pass with a tag of the
# wrong length or case, or with sha256sum's whole line ("<hash>  -"), which Docker rejects.
[[ $base_a =~ ^[0-9a-f]{16}$ ]] && tag_is_16_lowercase_hex=true || tag_is_16_lowercase_hex=false
check "the tag is 16 lowercase hex characters" "$tag_is_16_lowercase_hex"

svc_a_after=$(tag svc-a "$svc_a_changed_ref")
[ "$base_a" != "$svc_a_after" ] && own_change_changes_tag=true || own_change_changes_tag=false
check "changing a service's own content changes its tag" "$own_change_changes_tag"

# svc-a's blob stands for a whole module directory, its pom included, so this also checks that
# one module's pom is not part of another service's tag.
svc_b_after=$(tag svc-b "$svc_a_changed_ref")
[ "$base_b" = "$svc_b_after" ] && other_svc_tag_unchanged=true || other_svc_tag_unchanged=false
check "changing one service's content leaves an unrelated service's tag unchanged" "$other_svc_tag_unchanged"

svc_a_lib_changed=$(tag svc-a "$common_lib_changed_ref")
[ "$base_a" != "$svc_a_lib_changed" ] && lib_change_changes_svc_a_tag=true || lib_change_changes_svc_a_tag=false
check "changing common-lib changes a service's tag" "$lib_change_changes_svc_a_tag"

svc_b_lib_changed=$(tag svc-b "$common_lib_changed_ref")
[ "$base_b" != "$svc_b_lib_changed" ] && lib_change_changes_svc_b_tag=true || lib_change_changes_svc_b_tag=false
check "changing common-lib changes a different service's tag too" "$lib_change_changes_svc_b_tag"

svc_a_pom_changed=$(tag svc-a "$pom_changed_ref")
[ "$base_a" != "$svc_a_pom_changed" ] && pom_change_changes_svc_a_tag=true || pom_change_changes_svc_a_tag=false
check "changing the parent pom changes a service's tag" "$pom_change_changes_svc_a_tag"

svc_b_pom_changed=$(tag svc-b "$pom_changed_ref")
[ "$base_b" != "$svc_b_pom_changed" ] && pom_change_changes_svc_b_tag=true || pom_change_changes_svc_b_tag=false
check "changing the parent pom changes a different service's tag too" "$pom_change_changes_svc_b_tag"

svc_a_dockerfile_changed=$(tag svc-a "$dockerfile_changed_ref")
[ "$base_a" != "$svc_a_dockerfile_changed" ] && dockerfile_change_changes_svc_a_tag=true || dockerfile_change_changes_svc_a_tag=false
check "changing the Dockerfile changes a service's tag" "$dockerfile_change_changes_svc_a_tag"

svc_b_dockerfile_changed=$(tag svc-b "$dockerfile_changed_ref")
[ "$base_b" != "$svc_b_dockerfile_changed" ] && dockerfile_change_changes_svc_b_tag=true || dockerfile_change_changes_svc_b_tag=false
check "changing the Dockerfile changes a different service's tag too" "$dockerfile_change_changes_svc_b_tag"

svc_a_dockerignore_added=$(tag svc-a "$dockerignore_added_ref")
[ "$base_a" = "$svc_a_dockerignore_added" ] && dockerignore_leaves_tag=true || dockerignore_leaves_tag=false
check "adding backend/.dockerignore leaves a service's tag unchanged" "$dockerignore_leaves_tag"

# salt is a literal in image-tag.sh, so a copy with another value stands in for a bump; comparing
# the copy with the original makes the checks fail if the edit no longer finds the salt line.
sed 's/^salt=.*/&-bumped/' "$script" >"$work/image-tag-salted.sh"
cmp -s "$script" "$work/image-tag-salted.sh" && salt_line_found=false || salt_line_found=true
salted_a=$(bash "$work/image-tag-salted.sh" svc-a "$base_ref")
salted_b=$(bash "$work/image-tag-salted.sh" svc-b "$base_ref")
[ "$salt_line_found" = true ] && [ "$base_a" != "$salted_a" ] && salt_changes_svc_a_tag=true || salt_changes_svc_a_tag=false
check "bumping the salt changes a service's tag" "$salt_changes_svc_a_tag"
[ "$salt_line_found" = true ] && [ "$base_b" != "$salted_b" ] && salt_changes_svc_b_tag=true || salt_changes_svc_b_tag=false
check "bumping the salt changes a different service's tag too" "$salt_changes_svc_b_tag"

git update-ref HEAD "$svc_a_changed_ref"
default_ref_out=$(bash "$script" svc-a)
[ "$default_ref_out" = "$svc_a_after" ] && default_matches_head=true || default_matches_head=false
check "omitting the git-ref argument defaults to HEAD" "$default_matches_head"

check_fails "an unknown service name" does-not-exist "$base_ref"

# svc-a exists at HEAD, so a script that fell back to HEAD on a bad ref would print a tag.
# Git refuses a space in any branch or tag name, so the bad ref below can never resolve.
check_fails "an unknown git ref" svc-a 'no such ref'

check_fails "a ref without backend/common-lib" svc-a "$no_common_lib_ref"
check_fails "a ref without backend/pom.xml" svc-a "$no_pom_ref"
check_fails "a ref without backend/Dockerfile" svc-a "$no_dockerfile_ref"

check_fails "a missing service argument"
missing_err=$(bash "$script" 2>&1 >/dev/null) || true
case "$missing_err" in
*"usage: image-tag.sh"*) missing_arg_usage_documented=true ;;
*) missing_arg_usage_documented=false ;;
esac
check "a missing service argument's error message documents usage" "$missing_arg_usage_documented"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
