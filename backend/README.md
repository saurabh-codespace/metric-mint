# Metric Mint Backend MVP

Small Spring Boot 3 / Java 21 API for the Metric Mint MVP.

## Run locally

Create a SQL Server database named `ToscanaInsightHub`, then provide credentials through environment variables:

```powershell
$env:DB_URL='jdbc:sqlserver://localhost:1433;databaseName=ToscanaInsightHub;encrypt=true;trustServerCertificate=true'
$env:DB_USERNAME='sa'
$env:DB_PASSWORD='<your-local-password>'
$env:DEMO_USERNAME='demo@toscana-insights.local'
$env:DEMO_PASSWORD='<choose-a-demo-password>'
mvn spring-boot:run
```

Flyway creates and owns the `toscana_insights` schema. Datasource records hold a secret-manager reference only; they never accept or persist a database password.

The MVP uses HTTP Basic demo authentication. Replace it with your organization identity provider before production use.

## Verify

```powershell
mvn test
```

Health is available at `GET /actuator/health`. All `/api/**` endpoints require authentication.
