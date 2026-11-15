$ErrorActionPreference='Stop'
$files=Get-ChildItem (Join-Path $PSScriptRoot '../src/main/java'),(Join-Path $PSScriptRoot '../modules') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName
& java --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -jar (Join-Path $PSScriptRoot 'google-java-format-1.13.0.jar') --replace $files
exit $LASTEXITCODE
