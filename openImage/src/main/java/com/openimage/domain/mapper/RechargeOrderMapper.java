package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.RechargeOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrderEntity> {
    @Select("SELECT * FROM recharge_orders WHERE id = #{id} FOR UPDATE")
    RechargeOrderEntity lockById(@Param("id") String id);
}
