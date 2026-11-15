@echo off
cd /d "%~dp0"
"dist\SedonaStudio\runtime\bin\javaw.exe" --add-modules javafx.controls,javafx.swing -jar "dist\SedonaStudio\panel-demo\studio-panel-demo.jar"
