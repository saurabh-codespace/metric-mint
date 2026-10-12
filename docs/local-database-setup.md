# Local SQL Server Setup

## Prerequisites

- SQL Server reachable from the development machine.
- Microsoft `sqlcmd` installed and available on `PATH`.
- A SQL login allowed to create or access the target database.

## Initialize

Set the environment variables only in the current PowerShell session, then run the initializer:

```powershell
$env:SQLSERVER_HOST = 'localhost'
$env:SQLSERVER_PORT = '1433'
$env:SQLSERVER_USER = 'sa'
$env:SQLSERVER_PASSWORD = '<your-local-password>'
$env:SQLSERVER_DB = 'ToscanaInsightHub'

.\database\initialize.ps1
```

The script creates the database if needed, applies `database/001_mvp_schema.sql`, and applies `database/002_seed.sql`. It passes the password to `sqlcmd` through the temporary `SQLCMDPASSWORD` environment variable and does not write it to disk.

After setup, clear the session secret:

```powershell
Remove-Item Env:SQLSERVER_PASSWORD
```

The seeded user is metadata only: `admin@toscana-insights.local` has no password hash and cannot authenticate until the backend provisions an identity.

## Verify

```sql
SELECT name FROM sys.schemas WHERE name = 'toscana_insights';
SELECT name FROM sys.tables WHERE schema_id = SCHEMA_ID('toscana_insights') ORDER BY name;
```
