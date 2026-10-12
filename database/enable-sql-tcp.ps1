# Run as administrator. Enables the existing default SQL Server instance on TCP 1433.
$ErrorActionPreference = 'Stop'
$tcpKey = 'HKLM:/SOFTWARE/Microsoft/Microsoft SQL Server/MSSQL17.MSSQLSERVER/MSSQLServer/SuperSocketNetLib/Tcp'
$log = Join-Path $PSScriptRoot 'sql-tcp-setup.log'
try {
    $current = Get-ItemProperty -LiteralPath $tcpKey
    $ports = Get-ItemProperty -LiteralPath "$tcpKey/IPAll"
    if ($current.Enabled -ne 1 -or $ports.TcpPort -ne '1433' -or $ports.TcpDynamicPorts -ne '') {
        Set-ItemProperty -LiteralPath $tcpKey -Name Enabled -Value 1
        Set-ItemProperty -LiteralPath "$tcpKey/IPAll" -Name TcpPort -Value '1433'
        Set-ItemProperty -LiteralPath "$tcpKey/IPAll" -Name TcpDynamicPorts -Value ''
        Restart-Service -Name MSSQLSERVER -Force
    }
    'SQL Server TCP enabled on port 1433.' | Set-Content -LiteralPath $log
} catch {
    $_.Exception.Message | Set-Content -LiteralPath $log
    throw
}
