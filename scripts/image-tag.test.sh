#!/usr/bin/env bash
# Behavioral tests for image-tag.sh. Run directly: `bash scripts/image-tag.test.sh`.
# Builds disconnected git commit objects via plumbing (hash-object/mktree/commit-tree) - none of
# them are pointed to by any ref, so this never touches the real branch, working tree, or HEAD;
# the loose objects it creates are ordinary git garbage, cleaned up by the next `git gc`.
set -euo pipefail
cd "$(dirname "$0")/.."

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

# backend/svc-a, backend/svc-b, backend/common-lib, backend/pom.xml as plain blobs - image-tag.sh
# only ever hashes the object id found at each path, so a blob stands in for a real directory tree
# just as well for this purpose.
mktree_commit() {
  local svc_a=$1 svc_b=$2 common_lib=$3 pom=$4
  local backend_tree root_tree
  backend_tree=$(printf '100644 blob %s\tsvc-a\n100644 blob %s\tsvc-b\n100644 blob %s\tcommon-lib\n100644 blob %s\tpom.xml\n' \
    "$svc_a" "$svc_b" "$common_lib" "$pom" | git mktree)
  root_tree=$(printf '040000 tree %s\tbackend\n' "$backend_tree" | git mktree)
  GIT_AUTHOR_NAME="image-tag-test" GIT_AUTHOR_EMAIL="image-tag-test@localhost" \
    GIT_COMMITTER_NAME="image-tag-test" GIT_COMMITTER_EMAIL="image-tag-test@localhost" \
    git commit-tree "$root_tree" -m "image-tag.sh test fixture" </dev/null
}

tag() { bash scripts/image-tag.sh "$1" "$2"; }

svc_a_1=$(blob "svc-a v1")
svc_a_2=$(blob "svc-a v2")
svc_b_1=$(blob "svc-b v1")
common_lib_1=$(blob "common-lib v1")
common_lib_2=$(blob "common-lib v2")
pom_1=$(blob "pom v1")
pom_2=$(blob "pom v2")

base_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_1")
svc_a_changed_ref=$(mktree_commit "$svc_a_2" "$svc_b_1" "$common_lib_1" "$pom_1")
common_lib_changed_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_2" "$pom_1")
pom_changed_ref=$(mktree_commit "$svc_a_1" "$svc_b_1" "$common_lib_1" "$pom_2")

base_a=$(tag svc-a "$base_ref")
base_b=$(tag svc-b "$base_ref")

[ "$(tag svc-a "$base_ref")" = "$base_a" ] && tag_is_deterministic=true || tag_is_deterministic=false
check "determinism: same content yields the same tag" "$tag_is_deterministic"

svc_a_after=$(tag svc-a "$svc_a_changed_ref")
[ "$base_a" != "$svc_a_after" ] && own_change_changes_tag=true || own_change_changes_tag=false
check "changing a service's own content changes its tag" "$own_change_changes_tag"

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

# Uses a real, always-present directory (common-lib) rather than the synthetic fixtures above,
# since exercising the default-ref fallback needs a ref that's actually reachable as HEAD.
default_ref_out=$(bash scripts/image-tag.sh common-lib)
explicit_head_out=$(bash scripts/image-tag.sh common-lib HEAD)
[ "$default_ref_out" = "$explicit_head_out" ] && default_matches_head=true || default_matches_head=false
check "omitting the git-ref argument defaults to HEAD" "$default_matches_head"

set +e
bad_out=$(bash scripts/image-tag.sh does-not-exist "$base_ref" 2>/dev/null)
bad_code=$?
set -e
[ "$bad_code" -ne 0 ] && unknown_svc_exits_nonzero=true || unknown_svc_exits_nonzero=false
check "an unknown service name exits non-zero" "$unknown_svc_exits_nonzero"
[ -z "$bad_out" ] && unknown_svc_prints_no_tag=true || unknown_svc_prints_no_tag=false
check "an unknown service name prints no tag to stdout" "$unknown_svc_prints_no_tag"

set +e
missing_out=$(bash scripts/image-tag.sh 2>/dev/null)
missing_code=$?
missing_err=$(bash scripts/image-tag.sh 2>&1 >/dev/null)
set -e
[ "$missing_code" -ne 0 ] && missing_arg_exits_nonzero=true || missing_arg_exits_nonzero=false
check "a missing service argument exits non-zero" "$missing_arg_exits_nonzero"
[ -z "$missing_out" ] && missing_arg_prints_no_tag=true || missing_arg_prints_no_tag=false
check "a missing service argument prints no tag to stdout" "$missing_arg_prints_no_tag"
case "$missing_err" in
*"usage: image-tag.sh"*) missing_arg_usage_documented=true ;;
*) missing_arg_usage_documented=false ;;
esac
check "a missing service argument's error message documents usage" "$missing_arg_usage_documented"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
