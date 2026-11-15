#!/bin/sh
cd "$(dirname "$0")" || exit 1
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}/sedona-studio"
# A development tree keeps the Linux JavaFX jars two folders up (tools/fetch_linux_deps.py).
if [ -z "$JAVAFX_LIB" ] && [ -d ../../lib/javafx-linux ]; then JAVAFX_LIB=../../lib/javafx-linux; fi
if [ -n "$JAVAFX_LIB" ]; then
 exec "$JAVA" --module-path "$JAVAFX_LIB" --add-modules javafx.controls,javafx.swing -Dsedona.home="$DATA/cache" -Dstudio.data="$DATA" -jar sedona-studio.jar "$@"
fi
exec "$JAVA" -Dsedona.home="$DATA/cache" -Dstudio.data="$DATA" -jar sedona-studio.jar "$@"
