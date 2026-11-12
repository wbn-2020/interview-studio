package com.wenjie.studio.material;

import com.wenjie.studio.common.IdempotencyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Service
public class MaterialTransactions {
    private final IdempotencyMapper owners;
    private final TransactionTemplate transaction;

    public MaterialTransactions(IdempotencyMapper owners, PlatformTransactionManager manager) {
        this.owners = owners;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T locked(String owner, Supplier<T> action) {
        return transaction.execute(status -> {
            owners.ensureWorkspace(owner);
            if (owners.lockWorkspace(owner) == null) throw new IllegalStateException("无法取得资料工作区锁");
            // 文件 I/O 在调用方的两次短事务之间执行，认领令牌阻止超时写者再次确认结果。
            return action.get();
        });
    }
}
