package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    UserEntity lockById(@Param("id") String id);
}
