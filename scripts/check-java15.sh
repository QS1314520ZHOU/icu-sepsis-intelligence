#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 15 installation}"
version="$($JAVA_HOME/bin/javac -version 2>&1)"
[[ "$version" == javac\ 15.* ]] || { echo 'JDK 15 compiler required'; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
java -version
