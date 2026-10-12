package com.toscana_insights.datasource;

import com.toscana_insights.common.BadRequestException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Opens read-only JDBC connections to customer databases. The URL is built from validated parts, never taken from user input. */
@Component
public class JdbcSourceFactory {
    static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    static final Pattern SAFE_DB = Pattern.compile("[A-Za-z0-9._ -]{1,120}");

    public static boolean isJdbc(String type) { return "POSTGRESQL".equals(type) || "SQL_SERVER".equals(type); }

    public Connection open(DataSourceConnection source, String password, boolean trustServerCertificate) throws SQLException {
        if (!SAFE_HOST.matcher(source.getHost()).matches() || !SAFE_DB.matcher(source.getDatabaseName()).matches())
            throw new BadRequestException("Host or database name contains unsupported characters");
        String url = switch (source.getType()) {
            case "POSTGRESQL" -> "jdbc:postgresql://" + source.getHost() + ":" + source.getPort() + "/" + source.getDatabaseName()
                    + "?connectTimeout=10&options=-c%20default_transaction_read_only=on";
            case "SQL_SERVER" -> "jdbc:sqlserver://" + source.getHost() + ":" + source.getPort() + ";databaseName=" + source.getDatabaseName()
                    + ";encrypt=true;trustServerCertificate=" + trustServerCertificate + ";applicationIntent=ReadOnly;loginTimeout=10";
            default -> throw new BadRequestException("Not a JDBC source type: " + source.getType());
        };
        Properties props = new Properties();
        props.setProperty("user", source.getUsername());
        props.setProperty("password", password);
        Connection connection = DriverManager.getConnection(url, props);
        connection.setReadOnly(true);
        return connection;
    }
}
