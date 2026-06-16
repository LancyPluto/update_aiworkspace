package com.aiminilab.aitoolmarket.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
@MapperScan({
        "com.aiminilab.aitoolmarket.admin.mapper",
        "com.aiminilab.aitoolmarket.agent.mapper",
        "com.aiminilab.aitoolmarket.credit.mapper",
        "com.aiminilab.aitoolmarket.community.mapper",
        "com.aiminilab.aitoolmarket.task.mapper",
        "com.aiminilab.aitoolmarket.tool.mapper",
        "com.aiminilab.aitoolmarket.user.mapper",
        "com.aiminilab.aitoolmarket.market.mapper",
        "com.aiminilab.aitoolmarket.ppt.mapper",
        "com.aiminilab.aitoolmarket.workflow.mapper"
})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 自动填充 @TableField(fill=...) 标注的 createdAt/updatedAt。项目此前未注册该 handler，
     * 导致 ToolWorkflow / ToolWorkflowVersion 这类带 FieldFill 的实体在 insert 时把时间字段写为
     * NULL（列为 NOT NULL）→ "Column 'created_at' cannot be null"，配置包导入工作流即因此失败。
     */
    @Bean
    public MetaObjectHandler timestampMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
