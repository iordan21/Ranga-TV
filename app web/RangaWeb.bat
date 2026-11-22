@echo off
rem Liga o Ranga Web. Na primeira vez ele compila, e isso leva 1 ou 2 minutos.
rem Para trocar a porta: RangaWeb.bat 9000
cd /d "%~dp0"
if not exist "build\install\ranga-web\bin\ranga-web.bat" call gradlew.bat installDist -q
call "build\install\ranga-web\bin\ranga-web.bat" %*
pause
