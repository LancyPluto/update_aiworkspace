package com.aiminilab.aitoolmarket.ppt.mapper;

import com.aiminilab.aitoolmarket.ppt.entity.PptExport;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PptExportMapper extends BaseMapper<PptExport> {

    @Insert("""
            INSERT INTO ppt_exports (
              user_id, project_id, deck_version_id, job_id, export_type, status,
              file_name, content_type, file_size, storage_url, checksum_sha256,
              created_at, updated_at
            ) VALUES (
              #{export.userId}, #{export.projectId}, #{export.deckVersionId}, #{export.jobId},
              #{export.exportType}, #{export.status}, #{export.fileName}, #{export.contentType},
              #{export.fileSize}, #{export.storageUrl}, #{export.checksumSha256},
              #{export.createdAt}, #{export.updatedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "export.id")
    int insertExport(@Param("export") PptExport export);

    @Select("""
            SELECT *
            FROM ppt_exports
            WHERE project_id = #{projectId} AND user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    List<PptExport> findByProjectAndUser(@Param("projectId") Long projectId,
                                         @Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM ppt_exports
            WHERE id = #{id} AND project_id = #{projectId} AND user_id = #{userId}
            LIMIT 1
            """)
    PptExport findOwned(@Param("id") Long id,
                        @Param("projectId") Long projectId,
                        @Param("userId") Long userId);
}
