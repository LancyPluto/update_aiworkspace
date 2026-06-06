package com.aiminilab.aitoolmarket.agent.mapper;

import com.aiminilab.aitoolmarket.agent.entity.ModelProviderMetadata;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ModelProviderMetadataMapper extends BaseMapper<ModelProviderMetadata> {

    @Select("""
            SELECT *
            FROM model_provider_metadata
            WHERE COALESCE(enabled, 1) = 1
            ORDER BY id ASC
            """)
    List<ModelProviderMetadata> findEnabled();

    @Select("""
            SELECT *
            FROM model_provider_metadata
            WHERE provider_code = #{providerCode}
              AND COALESCE(enabled, 1) = 1
            LIMIT 1
            """)
    ModelProviderMetadata findEnabledByCode(@Param("providerCode") String providerCode);
}
