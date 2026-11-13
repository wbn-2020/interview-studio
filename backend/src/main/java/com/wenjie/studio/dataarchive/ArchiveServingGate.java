package com.wenjie.studio.dataarchive;

import com.wenjie.studio.common.ApiException;
import com.wenjie.studio.ai.AiProperties;
import com.wenjie.studio.material.MaterialProperties;
import com.wenjie.studio.search.SearchProperties;
import com.wenjie.studio.security.OwnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.nio.file.Path;

/** 恢复 CLI 不启动应用；误启动已封锁的目标库也必须在 worker 调度前拒绝启动。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ArchiveServingGate extends OncePerRequestFilter implements SmartInitializingSingleton {
    private final DataSource database;
    private final AiProperties aiProperties;
    private final MaterialProperties materialProperties;
    private final SearchProperties searchProperties;
    private final String configuredOwner;
    public ArchiveServingGate(DataSource database, AiProperties aiProperties,
                               MaterialProperties materialProperties, SearchProperties searchProperties,
                               OwnerProperties ownerProperties) {
        this.database = database; this.aiProperties = aiProperties;
        this.materialProperties = materialProperties; this.searchProperties = searchProperties;
        this.configuredOwner = ownerProperties.ownerId();
    }

    @Override public void afterSingletonsInstantiated() { requireOpen(); }

    public void requireOpen() { check(false); }

    /** AI worker 在认领／发送前调用；完整恢复后的旧配置不能自动恢复模型发送。 */
    public void requireAiAllowed() { check(true); }

    private void check(boolean ai) {
        Connection connection = DataSourceUtils.getConnection(database);
        try (var query = connection.prepareStatement("SELECT state,ai_blocked,archive_id,ack_ai_config_fingerprint,"
                + "restored_material_directory,restored_index_directory,owner_id FROM data_restore_gate WHERE singleton_id=1")) {
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                if (!result.next() || !"OPEN".equals(result.getString(1)))
                    throw new ApiException(503, "RESTORE_ENVIRONMENT_SEALED", "恢复环境尚未完成最新隐私账本与索引核验，业务保持封闭。");
                if (result.getString(3) != null && (!sameDirectory(materialProperties.directory(), result.getString(5))
                        || !sameDirectory(searchProperties.directory(), result.getString(6))
                        || !configuredOwner.equals(result.getString(7))))
                    throw new ApiException(503, "RESTORE_RUNTIME_CONFIGURATION_CHANGED", "运行目录或工作区身份与已核验的恢复环境不一致，请先核对服务器配置。");
                if (ai && (result.getBoolean(2) || result.getString(3) != null
                        && !aiProperties.fingerprint().equals(result.getString(4))))
                    throw new ApiException(503, "RESTORED_AI_CONFIRMATION_REQUIRED", "恢复后的模型发送保持关闭，请重新确认服务器模型配置与用途授权。");
            }
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) {
            throw new ApiException(503, "RESTORE_GUARD_UNAVAILABLE", "恢复守卫不可用，业务保持封闭。");
        } finally { DataSourceUtils.releaseConnection(connection, database); }
    }

    private static boolean sameDirectory(String configured, String restored) {
        if (configured == null || configured.isBlank() || restored == null) return false;
        Path path = Path.of(configured);
        return path.isAbsolute() && path.equals(path.normalize()) && path.equals(Path.of(restored));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { requireOpen(); }
        catch (RuntimeException exception) {
            response.setStatus(503);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"status\":503,\"code\":\"RESTORE_ENVIRONMENT_SEALED\",\"detail\":\"恢复环境尚未通过账本与索引核验。\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
