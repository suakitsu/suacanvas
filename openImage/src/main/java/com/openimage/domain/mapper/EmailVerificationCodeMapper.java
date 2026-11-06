package com.openimage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openimage.domain.entity.EmailVerificationCodeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmailVerificationCodeMapper extends BaseMapper<EmailVerificationCodeEntity> {
}
