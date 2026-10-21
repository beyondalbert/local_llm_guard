package com.vllm.gateway.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vllm.gateway.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
}
