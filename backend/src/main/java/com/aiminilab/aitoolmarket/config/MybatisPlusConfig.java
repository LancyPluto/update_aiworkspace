package com.aiminilab.aitoolmarket.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
        "com.aiminilab.aitoolmarket.admin.mapper",
        "com.aiminilab.aitoolmarket.agent.mapper",
        "com.aiminilab.aitoolmarket.credit.mapper",
        "com.aiminilab.aitoolmarket.task.mapper",
        "com.aiminilab.aitoolmarket.tool.mapper",
        "com.aiminilab.aitoolmarket.user.mapper"
})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
