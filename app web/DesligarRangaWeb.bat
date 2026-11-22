@echo off
rem Desliga o Ranga Web que esta rodando escondido.
rem O .bat existe porque o Windows bloqueia .ps1 com dois cliques por padrao.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0DesligarRangaWeb.ps1" %*
pause
