package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.RiskEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RiskEventMapper extends BaseMapper<RiskEventEntity> {
    @Update("UPDATE risk_events SET deleted = 1 WHERE user_id = #{userId} AND deleted = 0")
    int softDeleteByUserId(@Param("userId") String userId);
}
