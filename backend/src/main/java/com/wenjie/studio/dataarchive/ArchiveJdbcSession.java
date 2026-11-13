package com.wenjie.studio.dataarchive;

import com.wenjie.studio.common.IdempotencyMapper;
import com.wenjie.studio.dataprivacy.PrivacyContentMapper;
import com.wenjie.studio.material.MaterialLifecycleMapper;
import com.wenjie.studio.search.SearchMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/** 离线复用现有 mapper；提交、回滚和连接生命周期全部属于恢复引擎。 */
final class ArchiveJdbcSession {
    private ArchiveJdbcSession() { }

    private static final class Factory {
        private static final SqlSessionFactory INSTANCE = create();
        private static SqlSessionFactory create() {
            var transactions = new ManagedTransactionFactory();
            var properties = new Properties();
            properties.setProperty("closeConnection", "false");
            transactions.setProperties(properties);
            var configuration = new Configuration(new Environment("archive-offline",
                    transactions, new UnpooledDataSource()));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setArgNameBasedConstructorAutoMapping(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setDefaultStatementTimeout(10);
            configuration.addMapper(PrivacyContentMapper.class);
            for (String resource : new String[]{"mapper/dataprivacy/PrivacyMapper.xml",
                    "mapper/common/IdempotencyMapper.xml", "mapper/search/SearchMapper.xml",
                    "mapper/material/MaterialLifecycleMapper.xml"}) {
                try (var input = ArchiveJdbcSession.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("RESTORE_MAPPER_MISSING");
                    new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
                } catch (IOException exception) {
                    throw new IllegalStateException("离线恢复 mapper 资源不完整", exception);
                }
            }
            if (!configuration.hasMapper(IdempotencyMapper.class) || !configuration.hasMapper(SearchMapper.class)
                    || !configuration.hasMapper(MaterialLifecycleMapper.class))
                throw new IllegalStateException("离线恢复 mapper 未登记");
            return new SqlSessionFactoryBuilder().build(configuration);
        }
    }

    static SqlSession open(Connection connection) throws SQLException {
        if (connection == null || connection.isClosed() || connection.getAutoCommit())
            throw new SQLException("RESTORE_TRANSACTION_REQUIRED");
        return Factory.INSTANCE.openSession(connection);
    }
}
