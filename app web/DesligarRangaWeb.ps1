<#
  Desliga o Ranga Web que esta rodando escondido.
  So encerra o Java que estiver servindo o Ranga (porta 8088 a 8097);
  outros programas Java do PC (Minecraft, por exemplo) ficam intactos.
#>
$achou = $false
8088..8097 | ForEach-Object {
    $con = Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $con) { return }
    $proc = Get-Process -Id $con.OwningProcess -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -eq 'java') {
        try {
            $st = Invoke-WebRequest "http://127.0.0.1:$_/api/status" -UseBasicParsing -TimeoutSec 3
            if ($st.Content -match 'needsSetup') {
                Stop-Process -Id $proc.Id -Force
                "Ranga Web desligado (porta $_)."
                $script:achou = $true
            }
        } catch { }
    }
}
if (-not $achou) { 'O Ranga Web nao estava ligado.' }
