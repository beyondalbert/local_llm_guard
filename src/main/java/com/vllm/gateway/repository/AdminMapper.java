package com.vllm.gateway.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vllm.gateway.entity.AdminInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMapper extends BaseMapper<AdminInfo> {
}
