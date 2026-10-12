package com.toscana_insights.catalog;

import com.toscana_insights.common.BadRequestException;
import com.toscana_insights.datasource.DataSourceConnection;
import com.toscana_insights.datasource.DataSourceService;
import com.toscana_insights.datasource.JdbcSourceFactory;
import com.toscana_insights.ingestion.TableImporter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Opens a read-only connection to wherever a catalogue table lives: the customer database, or the analytics store for imports. */
@Component
public class SourceAccess {
    public static final class Handle implements AutoCloseable {
        private final Connection connection;
        private final boolean remote;
        private final boolean sqlServer;
        private final DataSourceConnection source;

        Handle(Connection connection, boolean remote, boolean sqlServer, DataSourceConnection source) {
            this.connection = connection; this.remote = remote; this.sqlServer = sqlServer; this.source = source;
        }
        public Connection connection() { return connection; }
        public boolean sqlServer() { return sqlServer; }
        public DataSourceConnection source() { return source; }

        /** Quoted, fully-qualified table name. Identifiers come from the catalogue, which only holds validated names. */
        public String qualify(CatalogTable table) {
            if (!remote) return TableImporter.qualified(table.getPhysicalName());
            return String.join(".", Arrays.stream(table.getPhysicalName().split("\\.")).map(p -> "\"" + p.replace("\"", "") + "\"").toList());
        }

        /** Month/day/year bucket for a date column, in the dialect of this database. */
        public String timeBucket(String quotedColumn, String grain) {
            String fmt = switch (grain) { case "year" -> "yyyy"; case "day" -> "yyyy-MM-dd"; default -> "yyyy-MM"; };
            return sqlServer ? "FORMAT(" + quotedColumn + ", '" + fmt + "')" : "TO_CHAR(" + quotedColumn + ", '" + fmt.toUpperCase() + "')";
        }

        @Override public void close() throws SQLException { connection.close(); }
    }

    private final DataSourceService sources;
    private final DataSource appDataSource;

    public SourceAccess(DataSourceService sources, DataSource appDataSource) { this.sources = sources; this.appDataSource = appDataSource; }

    public Handle open(UUID sourceId) throws SQLException {
        DataSourceConnection source = sources.get(sourceId);
        if (source.isPaused()) throw new BadRequestException("Source '" + source.getName() + "' is paused");
        if (JdbcSourceFactory.isJdbc(source.getType())) {
            return new Handle(sources.connect(source), true, "SQL_SERVER".equals(source.getType()), source);
        }
        Connection connection = appDataSource.getConnection();
        connection.setReadOnly(true);
        boolean sqlServer = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("microsoft");
        return new Handle(connection, false, sqlServer, source);
    }
}
