# Compila ranga_pad.dll e a poe em resources/windows-x64/ranga_pad.dll.
# Usa o MinGW e o SDL 2 estatico que o Ranga EMU ja compila (C:\projetos\sos-ps5).
# Se o SDL de la mudar de lugar, troque $sdl abaixo.
$ErrorActionPreference = 'Stop'
$toolchain = 'C:\projetos\sos-ps5\toolchain\mingw64\bin'
$sdl = 'C:\projetos\sos-ps5\SoS-PS5\build\3rdparty\SDL2'
$aqui = $PSScriptRoot
$saida = Join-Path $aqui '..\resources\windows-x64\ranga_pad.dll'
$env:PATH = "$toolchain;$env:PATH"
& gcc -O2 -shared -o $saida (Join-Path $aqui 'ranga_pad.c') `
    "-I$sdl\include\SDL2" "-I$sdl\include-config-release\SDL2" `
    "-L$sdl" -lSDL2 `
    -lm -lkernel32 -luser32 -lgdi32 -lwinmm -limm32 -lole32 -loleaut32 -lversion -luuid -ladvapi32 -lsetupapi -lshell32 -ldinput8 -lcfgmgr32 `
    -static -static-libgcc -s
if ($LASTEXITCODE -ne 0) { throw "gcc falhou ($LASTEXITCODE)" }
Write-Host "ok: $saida"
