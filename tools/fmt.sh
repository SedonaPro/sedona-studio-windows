#!/bin/sh
# Format the given Java files in place with the project's google-java-format (needs JDK 17 on PATH).
exec java --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -jar "$(dirname "$0")/google-java-format-1.13.0.jar" --replace "$@"
