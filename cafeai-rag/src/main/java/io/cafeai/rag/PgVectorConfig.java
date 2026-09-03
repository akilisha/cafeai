package io.cafeai.rag;

import java.util.Objects;

/**
 * Connection and schema settings for a {@link PgVector}-backed {@link VectorStore}.
 *
 * <pre>{@code
 *   app.vectordb(VectorStore.pgVector(
 *       PgVectorConfig.builder()
 *           .host("postgres.internal")
 *           .database("cafeai")
 *           .user("cafeai")
 *           .password(System.getenv("PGPASSWORD"))
 *           .dimension(384)          // must match the registered EmbeddingModel
 *           .build()));
 * }</pre>
 *
 * <p>{@code dimension} must equal the dimensionality of vectors produced by the
 * {@code EmbeddingModel} registered with {@code app.embed(...)} —
 * {@code EmbeddingModel.local()} is 384, {@code EmbeddingModel.openAi()} is 1536.
 */
public final class PgVectorConfig {

    private final String  host;
    private final int     port;
    private final String  database;
    private final String  user;
    private final String  password;
    private final String  table;
    private final int     dimension;
    private final boolean useIndex;
    private final int     indexListSize;
    private final int     maxPoolSize;

    private PgVectorConfig(Builder b) {
        this.host          = Objects.requireNonNull(b.host, "host");
        this.port          = b.port;
        this.database      = Objects.requireNonNull(b.database, "database");
        this.user          = Objects.requireNonNull(b.user, "user");
        this.password      = b.password == null ? "" : b.password;
        this.table         = Objects.requireNonNull(b.table, "table");
        this.dimension     = b.dimension;
        this.useIndex      = b.useIndex;
        this.indexListSize = b.indexListSize;
        this.maxPoolSize   = b.maxPoolSize;
        if (dimension <= 0) {
            throw new IllegalArgumentException(
                "dimension must be set to the EmbeddingModel's vector size (e.g. 384 for local, 1536 for OpenAI)");
        }
    }

    public String  host()          { return host; }
    public int     port()          { return port; }
    public String  database()      { return database; }
    public String  user()          { return user; }
    public String  password()      { return password; }
    public String  table()         { return table; }
    public int     dimension()     { return dimension; }
    public boolean useIndex()      { return useIndex; }
    public int     indexListSize() { return indexListSize; }
    public int     maxPoolSize()   { return maxPoolSize; }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String  host          = "localhost";
        private int     port          = 5432;
        private String  database;
        private String  user          = "postgres";
        private String  password;
        private String  table         = "cafeai_chunks";
        private int     dimension;
        private boolean useIndex      = true;
        private int     indexListSize = 100;
        private int     maxPoolSize   = 8;

        public Builder host(String v)        { this.host = v; return this; }
        public Builder port(int v)           { this.port = v; return this; }
        public Builder database(String v)    { this.database = v; return this; }
        public Builder user(String v)        { this.user = v; return this; }
        public Builder password(String v)    { this.password = v; return this; }
        public Builder table(String v)       { this.table = v; return this; }
        public Builder dimension(int v)      { this.dimension = v; return this; }
        public Builder useIndex(boolean v)   { this.useIndex = v; return this; }
        public Builder indexListSize(int v)  { this.indexListSize = v; return this; }
        public Builder maxPoolSize(int v)    { this.maxPoolSize = v; return this; }

        public PgVectorConfig build() {
            return new PgVectorConfig(this);
        }
    }
}
