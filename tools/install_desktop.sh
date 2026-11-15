#!/bin/sh
# Puts a "Sedona Studio" launcher for this tree into the application menu and on the desktop
# of the current Linux user. Run it again after moving the tree.
root=$(cd "$(dirname "$0")/.." && pwd)
entry="[Desktop Entry]
Type=Application
Name=Sedona Studio
Comment=Sedona diagram editor
Exec=$root/studio.sh
Icon=$root/src/main/resources/icon.png
Terminal=false
Categories=Development;
StartupWMClass=ru.bccontrol.sedonastudio.ui.StudioApp
"
apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$apps"
printf '%s' "$entry" > "$apps/sedona-studio.desktop"
echo "menu: $apps/sedona-studio.desktop"
desk=$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")
if [ -d "$desk" ]; then
 printf '%s' "$entry" > "$desk/sedona-studio.desktop"
 chmod +x "$desk/sedona-studio.desktop"
 # GNOME shows a desktop launcher as runnable only when it is marked trusted.
 gio set "$desk/sedona-studio.desktop" metadata::trusted true 2>/dev/null
 echo "desktop: $desk/sedona-studio.desktop"
fi
update-desktop-database "$apps" 2>/dev/null
exit 0
