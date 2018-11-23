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
  if [ "${SBT_SCRIPTED:-""}" = "" ]; then
    ./metadata/scripts/with-test-repo.sh sbt \
      ++$TRAVIS_SCALA_VERSION \
      lm-coursier/test \
      "sbt-lm-coursier/scripted shared-$TEST_GROUP/*"
  else
    sbt ++$TRAVIS_SCALA_VERSION "sbt-lm-coursier-sbt/scripted $SBT_SCRIPTED/*"
  fi
}

runSbtCoursierTests() {
  if [ "$TEST_GROUP" = 1 ]; then
    SCRIPTED_EXTRA="sbt-coursier/*"
  else
    SCRIPTED_EXTRA=""
  fi

  ./metadata/scripts/with-test-repo.sh sbt \
    ++$TRAVIS_SCALA_VERSION \
    sbt-coursier-shared/test \
    "sbt-coursier/scripted shared-$TEST_GROUP/* $SCRIPTED_EXTRA"
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

