param([switch]$Once)
$ErrorActionPreference = 'Stop'
$pocRoot = Split-Path -Parent $PSScriptRoot
$pocPidFile = Join-Path $pocRoot 'secrets/tunnel-supervisor.pid'
$pocLogFile = Join-Path $pocRoot 'secrets/tunnel.log'
Set-Content -LiteralPath $pocPidFile -Value $PID
try {
    do {
        # Refuse to replace another program listening on the requested local port.
        if (Get-NetTCPConnection -LocalPort 18090 -State Listen -ErrorAction SilentlyContinue) {
            throw 'Port 18090 is already in use; keep the existing connection.'
        }
        if ((Test-Path -LiteralPath $pocLogFile) -and (Get-Item -LiteralPath $pocLogFile).Length -gt 16384) {
            Clear-Content -LiteralPath $pocLogFile
        }
        & ssh.exe -N -L 127.0.0.1:18090:127.0.0.1:18090 -p 22333 `
            -i C:\Users\east\OneDrive\files\SyncFile\mima\key\east-nopw `
            -o BatchMode=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=15 `
            -o ServerAliveInterval=15 -o ServerAliveCountMax=2 root@5.231.253.4 2>> $pocLogFile
        if (-not $Once) { Start-Sleep -Seconds 5 }
    } while (-not $Once)
} finally {
    if ((Get-Content -LiteralPath $pocPidFile -ErrorAction SilentlyContinue) -eq "$PID") {
        Remove-Item -LiteralPath $pocPidFile -ErrorAction SilentlyContinue
    }
}
