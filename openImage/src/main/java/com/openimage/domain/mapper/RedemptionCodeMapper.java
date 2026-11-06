package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.RedemptionCodeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RedemptionCodeMapper extends BaseMapper<RedemptionCodeEntity> {
    @Update("""
        UPDATE redemption_codes
        SET status = 'expired',
            updated_at = now()
        WHERE deleted = 0
          AND enabled = true
          AND status <> 'expired'
          AND expires_at IS NOT NULL
          AND expires_at <= now()
        """)
    int expireOverdueCodes();

    @Update("""
        UPDATE redemption_codes
        SET used_count = used_count + 1,
            status = CASE WHEN used_count + 1 >= max_uses THEN 'used_up' ELSE 'active' END,
            updated_at = now()
        WHERE id = #{id}
          AND deleted = 0
          AND enabled = true
          AND used_count < max_uses
          AND (expires_at IS NULL OR expires_at > now())
        """)
    int consumeIfAvailable(@Param("id") String id);
}
