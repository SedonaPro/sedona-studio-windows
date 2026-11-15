param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PSScriptRoot)
$info = Get-Content -LiteralPath (Join-Path $root 'SEDONA_STUDIO_CURRENT.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$release = Join-Path $root ('releases\' + $info.version + '\SedonaStudioWindows\SedonaStudioWindows.exe')
$exe = [IO.Path]::GetFullPath($release)
if (-not $exe.StartsWith(($root + '\releases\'), [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw 'Не найдена текущая сборка Sedona Studio. Проверьте SEDONA_STUDIO_CURRENT.json.'
}
if ($CheckOnly) { Write-Output $exe; return }
Start-Process -FilePath $exe -WorkingDirectory (Split-Path -Parent $exe)
