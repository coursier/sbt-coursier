#!/usr/bin/env bash
set -euo pipefail

# Builds coursier from sources, and publishes locally the coursier modules that
# the sbt-coursier build depends on.
#
# The coursier sources are checked out at the tag below, and an empty commit,
# tagged "vSNAPSHOT", is added on top of it. That way, coursier's build computes
# "SNAPSHOT" as version, rather than the version of the release tag. As snapshots
# are not published on Maven Central, this ensures that the modules the
# sbt-coursier build resolves are the ones published by this script in m2-repo,
# and not artifacts fetched from Maven Central.
#
# COURSIER_TAG below is meant to be bumped when a newer coursier version is
# needed. The version the sbt-coursier build depends on (coursierVersion0 in
# build.sbt) stays "SNAPSHOT".

COURSIER_GIT_URL="${COURSIER_GIT_URL:-https://github.com/coursier/coursier.git}"
COURSIER_TAG="${COURSIER_TAG:-v2.1.25-M26}"
SCALA_VERSION="${SCALA_VERSION:-2.12.20}"

# coursier's build strips the leading "v" of the tag of the current commit, and
# uses that as version
SNAPSHOT_TAG="vSNAPSHOT"
COURSIER_VERSION="${SNAPSHOT_TAG#v}"

# coursier modules needed to build sbt-coursier: the ones the sbt-coursier build
# depends on (coursier, coursier-sbt-maven-repository), and their dependencies
# (mill only publishes the modules it's asked to publish).
MODULES=(
  "util.jvm[$SCALA_VERSION]"
  "core.jvm[$SCALA_VERSION]"
  "cache-util"
  "paths"
  "cache.jvm[$SCALA_VERSION]"
  "proxy-setup"
  "coursier.jvm[$SCALA_VERSION]"
  "sbt-maven-repository.jvm[$SCALA_VERSION]"
)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# under target/, so that it's ignored by git
WORK_DIR="${COURSIER_WORK_DIR:-$ROOT/target/coursier-sources}"
# the maven repository the coursier modules are published to, and that the
# sbt-coursier build resolves them from (see build.sbt)
M2_REPO="${M2_REPO:-$ROOT/m2-repo}"

if [ -d "$WORK_DIR/.git" ]; then
  echo "Re-using coursier checkout in $WORK_DIR"
else
  rm -rf "$WORK_DIR"
  mkdir -p "$(dirname "$WORK_DIR")"
  git clone \
    --depth 1 \
    --branch "$COURSIER_TAG" \
    --recurse-submodules \
    --shallow-submodules \
    "$COURSIER_GIT_URL" \
    "$WORK_DIR"

  # user.name / user.email are passed explicitly, as git might not be configured
  # at all where this runs (CI…)
  git -C "$WORK_DIR" \
    -c "user.name=sbt-coursier" \
    -c "user.email=sbt-coursier@localhost" \
    commit --allow-empty -m "Empty commit, so that a snapshot version is computed"
  git -C "$WORK_DIR" tag "$SNAPSHOT_TAG"
fi

EXPECTED_VERSION="$(sed -n 's/^def coursierVersion0 = "\(.*\)"$/\1/p' "$ROOT/build.sbt")"
if [ "$COURSIER_VERSION" != "$EXPECTED_VERSION" ]; then
  echo "Warning: the sbt-coursier build uses coursier $EXPECTED_VERSION," 1>&2
  echo "but this script publishes $COURSIER_VERSION." 1>&2
  echo "Update coursierVersion0 in build.sbt to \"$COURSIER_VERSION\"." 1>&2
fi

# the mill launcher script only supports Linux and macOS, Windows needs mill.bat
case "$(uname -s)" in
  Linux*|Darwin*) MILL="./mill" ;;
  *)              MILL="./mill.bat" ;;
esac

cd "$WORK_DIR"
for module in "${MODULES[@]}"; do
  "$MILL" "$module.publishM2Local" --m2RepoPath "$M2_REPO"
done

echo "Published coursier $COURSIER_VERSION to $M2_REPO"
