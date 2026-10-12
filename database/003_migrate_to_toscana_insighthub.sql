USE [master];
GO

IF DB_ID(N'zenlytics') IS NOT NULL AND DB_ID(N'ToscanaInsightHub') IS NOT NULL
    THROW 51000, 'Both legacy and renamed application databases exist; resolve this manually before migration.', 1;

IF DB_ID(N'zenlytics') IS NOT NULL AND DB_ID(N'ToscanaInsightHub') IS NULL
BEGIN
    ALTER DATABASE [zenlytics] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    ALTER DATABASE [zenlytics] MODIFY NAME = [ToscanaInsightHub];
    ALTER DATABASE [ToscanaInsightHub] SET MULTI_USER;
END;
GO

IF DB_ID(N'ToscanaInsightHub') IS NULL
    THROW 51001, 'Application database ToscanaInsightHub was not found.', 1;
GO

USE [ToscanaInsightHub];
GO

IF SCHEMA_ID(N'toscana_insights') IS NULL
    EXEC(N'CREATE SCHEMA toscana_insights AUTHORIZATION dbo;');
GO

IF SCHEMA_ID(N'zenlytics') IS NOT NULL
BEGIN
    DECLARE @objectName sysname;
    DECLARE objects_to_move CURSOR LOCAL FAST_FORWARD FOR
        SELECT o.name
        FROM sys.objects o
        WHERE o.schema_id = SCHEMA_ID(N'zenlytics')
          AND o.is_ms_shipped = 0
          AND o.type IN ('U', 'V', 'P', 'FN', 'IF', 'TF', 'SO', 'SN');

    OPEN objects_to_move;
    FETCH NEXT FROM objects_to_move INTO @objectName;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        DECLARE @transferSql nvarchar(max) = N'ALTER SCHEMA toscana_insights TRANSFER zenlytics.' + QUOTENAME(@objectName) + N';';
        EXEC sys.sp_executesql @transferSql;
        FETCH NEXT FROM objects_to_move INTO @objectName;
    END;
    CLOSE objects_to_move;
    DEALLOCATE objects_to_move;

    IF NOT EXISTS (SELECT 1 FROM sys.objects WHERE schema_id = SCHEMA_ID(N'zenlytics'))
        EXEC(N'DROP SCHEMA zenlytics;');
END;
GO
