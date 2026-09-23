#!/usr/bin/env bash
set -euo pipefail

# Checks that the windows-jni-utils-lmcoursier version the sbt-coursier build
# depends on (jniUtilsVersion in build.sbt) is the windows-jni-utils version
# pulled by the coursier version it depends on. See the comment above
# jniUtilsVersion in build.sbt for why both need to be the same.
#
# The coursier modules are resolved from m2-repo, where
# scripts/publish-local-coursier.sh publishes them, so that script needs to
# have been run first.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2_REPO="${M2_REPO:-$ROOT/m2-repo}"

read_from_build() {
  local value
  value="$(sed -n "s/^\(def\|val\) $1 = \"\(.*\)\"$/\2/p" "$ROOT/build.sbt")"
  if [ "$(printf '%s\n' "$value" | grep -c .)" != "1" ]; then
    echo "Error: expected exactly one $1 definition in build.sbt, got:" 1>&2
    echo "$value" 1>&2
    exit 1
  fi
  echo "$value"
}

EXPECTED="$(read_from_build jniUtilsVersion)"
COURSIER_VERSION="$(read_from_build coursierVersion0)"

# sbt 1.x plugins are Scala 2.12 ones, hence the Scala 2.12 flavour of coursier
# (like in publish-local-coursier.sh). The transitive dependencies of coursier
# are resolved from the default repositories (Maven Central, …), like the build does.
ACTUAL="$(
  cs resolve -r "file://$M2_REPO" "io.get-coursier:coursier_2.12:$COURSIER_VERSION" \
    | sed -n 's/^io\.get-coursier\.jniutils:windows-jni-utils:\([^:]*\).*$/\1/p' \
    | sort -u
)"

case "$(printf '%s\n' "$ACTUAL" | grep -c .)" in
  1) ;;
  0)
    echo "Error: windows-jni-utils not found among the dependencies of coursier $COURSIER_VERSION" 1>&2
    exit 1
    ;;
  *)
    echo "Error: several windows-jni-utils versions among the dependencies of coursier $COURSIER_VERSION:" 1>&2
    echo "$ACTUAL" 1>&2
    exit 1
    ;;
esac

if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "Error: coursier $COURSIER_VERSION depends on windows-jni-utils $ACTUAL," 1>&2
  echo "but the sbt-coursier build depends on windows-jni-utils-lmcoursier $EXPECTED." 1>&2
  echo "Set jniUtilsVersion to \"$ACTUAL\" in build.sbt." 1>&2
  exit 1
fi

echo "coursier $COURSIER_VERSION depends on windows-jni-utils $ACTUAL, like the sbt-coursier build"
