package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.AdminAuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLogEntity> {
}
