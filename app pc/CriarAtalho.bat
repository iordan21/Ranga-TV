@echo off
chcp 65001 >nul
setlocal

rem ---------------------------------------------------------------------------
rem Cria o atalho do Ranga na Area de Trabalho.
rem
rem Duas decisoes que nao sao obvias:
rem
rem 1. O alvo e resolvido a partir de %~dp0 (a pasta ONDE ESTE .bat ESTA), e nao
rem    de um caminho fixo. Assim voce pode mover a pasta Ranga-PC para onde
rem    quiser, ou rodar de um pendrive, e rodar este arquivo de novo para
rem    consertar o atalho.
rem
rem 2. A Area de Trabalho e descoberta com GetFolderPath('Desktop'), e nao com
rem    %USERPROFILE%\Desktop. Se o OneDrive estiver ligado (padrao no Windows 11
rem    de fabrica), a Area de Trabalho real fica dentro do OneDrive e o caminho
rem    montado na mao criaria o atalho numa pasta que ninguem ve.
rem ---------------------------------------------------------------------------

set "APP=%~dp0Ranga\Ranga.exe"

if not exist "%APP%" (
    echo.
    echo  Nao encontrei o Ranga.exe em:
    echo    %APP%
    echo.
    echo  Este arquivo precisa ficar na mesma pasta que a pasta "Ranga".
    echo.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
 "$desktop = [Environment]::GetFolderPath('Desktop');" ^
 "$link = Join-Path $desktop 'Ranga.lnk';" ^
 "$shell = New-Object -ComObject WScript.Shell;" ^
 "$s = $shell.CreateShortcut($link);" ^
 "$s.TargetPath = '%APP%';" ^
 "$s.WorkingDirectory = '%~dp0Ranga';" ^
 "$s.IconLocation = '%APP%,0';" ^
 "$s.Description = 'Ranga - player de IPTV';" ^
 "$s.Save();" ^
 "Write-Host '';" ^
 "Write-Host ('  Atalho criado em: ' + $link);"

if errorlevel 1 (
    echo.
    echo  Falhou ao criar o atalho.
    pause
    exit /b 1
)

echo.
echo  Pronto. Procure o icone "Ranga" na Area de Trabalho.
echo.
pause
