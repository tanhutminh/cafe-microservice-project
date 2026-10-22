#!/usr/bin/env bash
# Prints the content-hash tag of a backend service's container image: 16 hex characters derived
# from git's own hashes of the inputs that shape it - the service's directory, backend/common-lib,
# the parent pom and backend/Dockerfile, the recipe every service's image is built from. Not
# covered: the other modules' poms (the build copies them so Maven can load the whole reactor and
# pre-fetch its dependencies; they can affect whether the build succeeds, not the jar),
# backend/.dockerignore (each entry is either gitignored, so absent from the clean checkout CI
# builds from, or, like src/test, never reaches the image anyway), and a base-image update behind
# an unchanged tag, which git cannot see (an edited FROM line is covered, as it changes
# backend/Dockerfile). backend/<service> and backend/common-lib are each hashed as one whole git
# tree, src/test included: a test-only change gives a new tag and a rebuild although nothing that
# reaches the image changed (backend/.dockerignore keeps src/test out of the build context).
# The same source content always yields the same tag, so CI can skip building an image that
# already exists, and a deploy can recompute the tag from any checkout.
#
# Usage: bash scripts/image-tag.sh <service> [git-ref, default HEAD]
set -euo pipefail

# Bump to give every service a new tag when the images change for a reason none of the hashed
# inputs show: a base-image update behind an unchanged tag, or a change to how CI builds them
# (e.g. its build arguments, labels or attestations, set in the workflow rather than in
# backend/Dockerfile).
salt=1

service="${1:?usage: image-tag.sh <service> [git-ref]}"
ref="${2:-HEAD}"

# One assignment per lookup so that a path missing at $ref aborts the script before anything is
# printed; a failure inside a shared $(...) group would be swallowed by the last command's status.
service_id="$(git rev-parse --verify "$ref:backend/$service")"
common_lib_id="$(git rev-parse --verify "$ref:backend/common-lib")"
parent_pom_id="$(git rev-parse --verify "$ref:backend/pom.xml")"
dockerfile_id="$(git rev-parse --verify "$ref:backend/Dockerfile")"

printf '%s\n' "$salt" "$service_id" "$common_lib_id" "$parent_pom_id" "$dockerfile_id" | sha256sum | cut -c1-16
