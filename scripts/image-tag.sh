#!/usr/bin/env bash
# Prints the content-hash tag of a backend service's container image: 16 hex characters derived
# from git's own hashes of the source inputs its Dockerfile copies - the service's directory,
# backend/common-lib and the parent pom. Not covered: the other modules' poms (they can only
# affect whether the build succeeds, not the jar), and the base images and Maven Central, which
# git cannot see. The same source content always yields the same tag, so CI can skip building an
# image that already exists, and a deploy can recompute the tag from any checkout.
#
# Usage: bash scripts/image-tag.sh <service> [git-ref, default HEAD]
set -euo pipefail

# Bump to give every service a new tag, e.g. to rebuild after a base-image security update
# (a base-image change alone does not alter any hashed source).
salt=1

service="${1:?usage: image-tag.sh <service> [git-ref]}"
ref="${2:-HEAD}"

# One assignment per lookup so that a path missing at $ref aborts the script before anything is
# printed; a failure inside a shared $(...) group would be swallowed by the last command's status.
service_id="$(git rev-parse --verify "$ref:backend/$service")"
common_lib_id="$(git rev-parse --verify "$ref:backend/common-lib")"
parent_pom_id="$(git rev-parse --verify "$ref:backend/pom.xml")"

printf '%s\n' "$salt" "$service_id" "$common_lib_id" "$parent_pom_id" | sha256sum | cut -c1-16
