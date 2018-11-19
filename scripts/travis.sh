#!/usr/bin/env bash
set -euvx

downloadInstallSbtExtras() {
  mkdir -p bin
  curl -L -o bin/sbt https://github.com/paulp/sbt-extras/raw/9ade5fa54914ca8aded44105bf4b9a60966f3ccd/sbt
  chmod +x bin/sbt
}

lmCoursier() {
  [ "${LM_COURSIER:-""}" = 1 ]
}

sbtPgpCoursier() {
  [ "${SBT_PGP_COURSIER:-""}" = 1 ]
}

sbtShading() {
  [ "${SBT_SHADING:-""}" = 1 ]
}

runLmCoursierTests() {

  # There's kind of a circular dependency here… we need an sbt version for
  # the lm-coursier scripted tests, that itself depends on lm-coursier…
  # Luckily, in the future, it should just be a matter of bumping the
  # version of lm-coursier in the sbt repository, and binary compatibility
  # should allow us to change things here, before they're picked up by sbt.

  # first, publish lm-coursier that we can depend on from sbt
  sbt \
    ++$TRAVIS_SCALA_VERSION \
    lm-coursier/test \
    sbt-shared/publishLocal \
    lm-coursier/publishLocal

  # second, publish a version of sbt that depends on lm-coursier
  TMP_SBT_VERSION="1.2.3-lm-coursier-SNAPSHOT"
  git clone https://github.com/alexarchambault/sbt.git -b topic/lm-coursier
  cd sbt
  sbt \
    "set version.in(ThisBuild) := \"$TMP_SBT_VERSION\"" \
    publishLocal
  cd ..

  # lastly, run scripted tests using the custom sbt version published above
  sbt \
    -Dlmcoursier.sbt.version="$TMP_SBT_VERSION" \
    ++$TRAVIS_SCALA_VERSION \
    "lm-coursier-tests/scripted sbt-coursier-group-2/simple"
}

runSbtCoursierTests() {
  ./metadata/scripts/with-test-repo.sh sbt ++$TRAVIS_SCALA_VERSION sbt-coursier/test "sbt-coursier/scripted sbt-coursier-group-$SBT_COURSIER_TEST_GROUP/*"
}

runSbtShadingTests() {
  sbt ++$TRAVIS_SCALA_VERSION sbt-shading/scripted
}

runSbtPgpCoursierTests() {
  addPgpKeys
  sbt ++$TRAVIS_SCALA_VERSION sbt-pgp-coursier/scripted
}

addPgpKeys() {
  for key in b41f2bce 9fa47a44 ae548ced b4493b94 53a97466 36ee59d9 dc426429 3b80305d 69e0a56c fdd5c0cd 35543c27 70173ee5 111557de 39c263a9; do
    gpg --keyserver keyserver.ubuntu.com --recv "$key"
  done
}


downloadInstallSbtExtras

if sbtShading; then
  runSbtShadingTests
elif sbtPgpCoursier; then
  runSbtPgpCoursierTests
elif lmCoursier; then
  runLmCoursierTests
else
  runSbtCoursierTests
fi

