package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.ProductProjectEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface ProductProjectMapper extends BaseMapper<ProductProjectEntity> {
    @Select("SELECT * FROM product_projects WHERE id = #{id} FOR UPDATE")
    ProductProjectEntity lockById(@Param("id") String id);

    @Select("""
        SELECT * FROM product_projects
        WHERE user_id = #{userId}
          AND deleted = 0
          AND original_info->>'linked_from_project_id' = #{sourceProjectId}
        ORDER BY created_at DESC
        LIMIT 1
        """)
    ProductProjectEntity findLinkedAplusBySource(@Param("userId") String userId, @Param("sourceProjectId") String sourceProjectId);

    @Update("""
        UPDATE product_projects
        SET deleted = 1,
            updated_at = #{updatedAt}
        WHERE id = #{id}
          AND user_id = #{userId}
          AND deleted = 0
        """)
    int softDeleteById(
        @Param("id") String id,
        @Param("userId") String userId,
        @Param("updatedAt") OffsetDateTime updatedAt
    );
}
