#!/bin/sh
# Starts the editor built in this tree (python3 build.py). Finds JDK 17 beside the tree when
# JAVA_HOME is not set, so it can be used from a desktop launcher.
here=$(cd "$(dirname "$0")" && pwd)
if [ -z "$JAVA_HOME" ]; then
 for d in "$here"/../tools/jdk-17* /usr/lib/jvm/*-17-* /usr/lib/jvm/*17*; do
  if [ -x "$d/bin/java" ]; then JAVA_HOME=$d; break; fi
 done
 [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi
if [ ! -f "$here/dist/SedonaStudio/sedona-studio.jar" ]; then
 echo "Not built yet: run python3 build.py in $here" >&2; exit 1
fi
exec "$here/dist/SedonaStudio/start.sh" "$@"
