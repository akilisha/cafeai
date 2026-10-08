package io.cafeai.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.identity.Identity;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A {@link DataSource} whose connections carry the current caller's verified claims, for
 * PostgreSQL row-level security: {@code request.jwt.claims} holds them as JSON, as PostgREST and
 * Supabase set it, or is empty when there is no caller.
 *
 * <p>Pooled connections are shared between callers, so the setting is written every time a
 * connection is handed out, whoever it was handed to last, and cleared again when it is given
 * back. A connection never carries a previous caller's claims into another caller's query.
 */
final class CallerClaimsDataSource implements DataSource {

    static final String SETTING = "request.jwt.claims";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource delegate;

    CallerClaimsDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withClaims(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withClaims(delegate.getConnection(username, password));
    }

    private static Connection withClaims(Connection connection) throws SQLException {
        try {
            set(connection, claims());
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        try {
                            if (!connection.isClosed()) set(connection, "");
                        } finally {
                            connection.close();
                        }
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** The current caller's claims as JSON, or {@code ""} when there is none. */
    static String claims() {
        return Identity.current().map(id -> {
            Map<String, Object> claims = new LinkedHashMap<>(id.claims());
            claims.put("iss", id.issuer());
            claims.put("sub", id.subject());
            try {
                return JSON.writeValueAsString(claims);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not write the caller's claims as JSON", e);
            }
        }).orElse("");
    }

    private static void set(Connection connection, String value) throws SQLException {
        // Session-level (is_local = false): it must hold for every statement the query runs,
        // including in auto-commit mode, so it is explicitly reset on every hand-out and return.
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            ps.setString(1, SETTING);
            ps.setString(2, value);
            ps.execute();
        }
    }

    // -- plain delegation ---------------------------------------------------------------

    @Override public PrintWriter getLogWriter() throws SQLException       { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException             { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException      { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}
