@echo off
chcp 65001 >nul
setlocal

rem ---------------------------------------------------------------------------
rem Gera o instalador Ranga-1.1.0.msi em dist\
rem
rem Existe como .bat, e nao como uma task normal do Gradle, por um motivo:
rem o jpackage so encontra as ferramentas do WiX (candle.exe e light.exe) pelo
rem PATH do processo. Nao da para configurar isso de dentro do build.gradle.kts
rem — quem precisa enxergar o WiX e o processo que roda o jpackage, ou seja, o
rem daemon do Gradle. Por isso o PATH e ajustado aqui, antes de chamar.
rem
rem O --no-daemon vem pelo mesmo motivo: um daemon que ja estivesse rodando
rem teria sido iniciado com o PATH antigo, sem o WiX, e a task falharia com uma
rem mensagem que nao explica nada.
rem ---------------------------------------------------------------------------

set "WIX=%~dp0..\tools\wix314"

if not exist "%WIX%\candle.exe" (
    echo.
    echo  WiX nao encontrado em: %WIX%
    echo.
    echo  Baixe wix314-binaries.zip da pagina oficial e extraia nessa pasta:
    echo    https://github.com/wixtoolset/wix3/releases
    echo.
    echo  Sao apenas os binarios, nao precisa instalar nada nem ser
    echo  administrador. O .NET 3.5 que o instalador do WiX pede NAO e
    echo  necessario para estes binarios.
    echo.
    pause
    exit /b 1
)

set "PATH=%WIX%;%PATH%"

echo  Gerando o instalador. Isso leva um minuto...
echo.

call "%~dp0gradlew.bat" --no-daemon -p "%~dp0." packageReleaseMsi
if errorlevel 1 (
    echo.
    echo  Falhou. Veja o erro acima.
    pause
    exit /b 1
)

if not exist "%~dp0dist" mkdir "%~dp0dist"
copy /y "%~dp0build\compose\binaries\main-release\msi\*.msi" "%~dp0dist\" >nul

echo.
echo  Pronto:
for %%f in ("%~dp0dist\*.msi") do echo    %%~ff  (%%~zf bytes)
echo.
pause
