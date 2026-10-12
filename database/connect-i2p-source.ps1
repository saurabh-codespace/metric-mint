<#
Registers the Toscana I2P SQL Server database as a governed data source and defines starter metrics, so Explore and Ask show
real Invoice-to-Pay insights. Idempotent: re-running reuses what exists. Needs a running backend.

  .\database\connect-i2p-source.ps1 -I2pPassword '<password for the new read-only login>'

Steps: (1) create a SELECT-only SQL login on dbo.AccountsPayable_External (uses Windows auth through sqlcmd -E),
(2) register it as a source with the password stored encrypted by the backend, (3) catalogue the table and hide user-name columns,
(4) create, approve metrics.
#>
param(
    [string]$Api = 'http://localhost:8080/api',
    [string]$AppUser = 'demo@toscana-insights.local',
    [Parameter(Mandatory)][string]$AppPassword,
    [string]$SqlServer = 'localhost',
    [string]$Port = '1433',
    [string]$Database = 'Toscana_I2P-SIT',
    [string]$Login = 'toscana_i2p_ro',
    [Parameter(Mandatory)][string]$I2pPassword
)
$ErrorActionPreference = 'Stop'
$table = 'dbo.AccountsPayable_External'

$sql = @"
IF SUSER_ID('$Login') IS NULL CREATE LOGIN [$Login] WITH PASSWORD = N'$($I2pPassword.Replace("'", "''"))', CHECK_POLICY = OFF;
USE [$Database];
IF USER_ID('$Login') IS NULL CREATE USER [$Login] FOR LOGIN [$Login];
GRANT SELECT ON OBJECT::$table TO [$Login];
"@
sqlcmd -S $SqlServer -E -C -b -Q $sql
if ($LASTEXITCODE -ne 0) { throw 'Could not create the read-only login' }

$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${AppUser}:${AppPassword}"))
function Call($method, $path, $body) {
    $args = @{ Method = $method; Uri = "$Api$path"; Headers = @{ Authorization = $auth }; ContentType = 'application/json' }
    if ($null -ne $body) { $args.Body = ($body | ConvertTo-Json -Depth 6) }
    Invoke-RestMethod @args
}

$name = 'Toscana I2P (SIT)'
$source = Call GET '/data-sources' | Where-Object { $_.name -eq $name } | Select-Object -First 1
if (-not $source) {
    $source = Call POST '/data-sources' @{
        name = $name; type = 'SQL_SERVER'; host = $SqlServer; port = [int]$Port; databaseName = $Database; username = $Login
        secret = $I2pPassword; config = @{ allowedTables = @($table); trustServerCertificate = $true }
    }
}
$test = Call POST "/data-sources/$($source.id)/test" $null
if (-not $test.ok) { throw "Connection test failed: $($test.message)" }

$cat = Call GET '/catalog/tables' | Where-Object { $_.sourceId -eq $source.id } | Select-Object -First 1
if (-not $cat) { $cat = Call POST "/catalog/sources/$($source.id)/tables" @{ table = $table } }
$restricted = 'CreatedBy', 'ModifiedBy', 'lockByUserName', 'assignedBy'
$cols = $cat.columns | Where-Object { $restricted -contains $_.name } | ForEach-Object { @{ name = $_.name; sensitivity = 'RESTRICTED' } }
$synRegion = @{ name = 'Region'; label = 'region'; synonyms = @('region') }
$cat = Call PUT "/catalog/tables/$($cat.id)" @{ description = 'Invoice-to-Pay work items (LOB 90)'; owner = 'I2P operations'; columns = @($cols) + @($synRegion) }

$existing = Call GET '/metrics'
$defs = @(
    @{ keyName = 'invoice_count'; name = 'Invoice work items'; description = 'Number of Accounts Payable work items'; aggregation = 'COUNT'; sourceField = '*'; synonyms = @('invoices', 'work items', 'invoice volume'); baselineValue = 215 },
    @{ keyName = 'avg_ageing_days'; name = 'Average ageing days'; description = 'Mean ageing of work items in days'; aggregation = 'AVG'; sourceField = 'Ageing'; synonyms = @('ageing', 'average ageing') },
    @{ keyName = 'distinct_invoices'; name = 'Distinct invoice numbers'; description = 'Unique invoice numbers'; aggregation = 'COUNT_DISTINCT'; sourceField = 'Invoice_Number'; synonyms = @('unique invoices') }
)
foreach ($d in $defs) {
    if ($existing | Where-Object { $_.keyName -eq $d.keyName }) { continue }
    $body = $d + @{ active = $true; tableId = $cat.id; dateColumn = 'CreatedDate' }
    $m = Call POST '/metrics' $body
    Call POST "/metrics/$($m.id)/approve" $null | Out-Null
    if ($d.baselineValue) {
        try { Call POST "/metrics/$($m.id)/verify" $null | Out-Null } catch { Write-Warning "$($d.keyName): baseline $($d.baselineValue) did not match current data; left approved but unverified." }
    }
}
Write-Host "Connected '$name'. Open Explore and try: 'invoice work items by region' or 'average ageing days by status'."
