<#
  Faz o Ranga Web ligar sozinho, escondido, quando voce entra no Windows.

  Uso (PowerShell normal, nao precisa ser Administrador):
    .\LigarComOPC.ps1            cria o atalho
    .\LigarComOPC.ps1 -Remover   desfaz

  Usa a pasta Inicializar do seu usuario, e nao o Agendador de Tarefas:
  nao pede Administrador e e facil de ver e apagar (Win+R, shell:startup).
#>
param([switch]$Remover)

$ErrorActionPreference = 'Stop'
$pasta = $PSScriptRoot
$startup = [Environment]::GetFolderPath('Startup')
$atalho = Join-Path $startup 'Ranga Web.lnk'

if ($Remover) {
    if (Test-Path $atalho) { Remove-Item $atalho; 'Pronto: o Ranga Web nao liga mais sozinho.' }
    else { 'Nao havia atalho para remover.' }
    return
}

# Compila uma vez agora, para o primeiro boot nao ficar 2 minutos sem Ranga.
if (-not (Test-Path (Join-Path $pasta 'build\install\ranga-web\bin\ranga-web.bat'))) {
    'Compilando o Ranga Web (1 ou 2 minutos)...'
    Push-Location $pasta
    try { & .\gradlew.bat installDist -q } finally { Pop-Location }
}

$sh = New-Object -ComObject WScript.Shell
$lnk = $sh.CreateShortcut($atalho)
$lnk.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
$lnk.Arguments = '"' + (Join-Path $pasta 'IniciarOculto.vbs') + '"'
$lnk.WorkingDirectory = $pasta
$lnk.Description = 'Liga o Ranga Web escondido'
$lnk.Save()

"Atalho criado em: $atalho"
'Ligando agora tambem, para voce nao precisar reiniciar...'
Start-Process -FilePath $lnk.TargetPath -ArgumentList $lnk.Arguments -WorkingDirectory $pasta
Start-Sleep -Seconds 6
try {
    Invoke-RestMethod 'http://127.0.0.1:8088/api/status' -TimeoutSec 5 | Out-Null
    'Ranga Web ligado em http://localhost:8088'
} catch {
    'Ainda nao respondeu. Veja o arquivo ranga-web.log nesta pasta.'
}
