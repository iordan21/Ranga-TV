@echo off
rem Faz o Ranga Web ligar sozinho com o Windows. Para desfazer: LigarComOPC.bat -Remover
rem O .bat existe porque o Windows bloqueia .ps1 com dois cliques por padrao.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0LigarComOPC.ps1" %*
pause
