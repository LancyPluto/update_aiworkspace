package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptEngineBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PptEngineBindingMapper extends BaseMapper<PptEngineBinding> {

    @Select("""
            SELECT *
            FROM ppt_engine_bindings
            WHERE project_id = #{projectId} AND engine_code = #{engineCode}
            LIMIT 1
            """)
    PptEngineBinding findByProjectAndEngine(@Param("projectId") Long projectId,
                                            @Param("engineCode") String engineCode);

    @Insert("""
            INSERT INTO ppt_engine_bindings (
              project_id, engine_code, external_project_id, engine_metadata_json, created_at, updated_at
            ) VALUES (
              #{binding.projectId}, #{binding.engineCode}, #{binding.externalProjectId},
              #{binding.engineMetadataJson}, #{binding.createdAt}, #{binding.updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "binding.id")
    int insertBinding(@Param("binding") PptEngineBinding binding);
}
