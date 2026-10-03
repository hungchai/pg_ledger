package io.zodia.pgledger.store.read;

import io.zodia.pgledger.store.RegistryCache;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;

/**
 * Plain-MyBatis glue for the core read mapper; pgledger-core stays Spring-free.
 * The store builds one factory on the DataSource it was given; the REST layer
 * keeps its own Spring-managed factory on the ShardingSphere DataSource.
 *
 * <p>Each query opens one auto-commit {@link SqlSession} and closes it, so
 * concurrent reads never share a connection or an executor. The per-call
 * service and mapper proxy are tiny short-lived objects; the connection pool
 * below MyBatis carries the real resources.
 */
public final class LedgerReadSql {

    private LedgerReadSql() {
    }

    public static SqlSessionFactory factory(DataSource dataSource) {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("pgledger-read", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setUseColumnLabel(true);
        configuration.addMapper(LedgerReadMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    public static <T> T query(SqlSessionFactory factory, RegistryCache registries, ReadQuery<T> query) {
        try (SqlSession session = factory.openSession(ExecutorType.SIMPLE, true)) {
            return query.run(new LedgerReadService(session.getMapper(LedgerReadMapper.class), registries));
        }
    }

    @FunctionalInterface
    public interface ReadQuery<T> {
        T run(LedgerReadService reads);
    }

    /**
     * Binds one factory; each query opens and closes its own SqlSession, so
     * concurrent readers never share a connection.
     */
    public static final class ReadQueryFactory {
        private final SqlSessionFactory factory;
        private final RegistryCache registries;

        ReadQueryFactory(SqlSessionFactory factory, RegistryCache registries) {
            this.factory = factory;
            this.registries = registries;
        }

        public <T> T query(ReadQuery<T> query) {
            return LedgerReadSql.query(factory, registries, query);
        }
    }

    public static ReadQueryFactory queries(SqlSessionFactory factory, RegistryCache registries) {
        return new ReadQueryFactory(factory, registries);
    }
}
