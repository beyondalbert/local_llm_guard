package com.vllm.gateway.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vllm.gateway.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Select("SELECT COUNT(*) FROM chat_session")
    Long countAll();
}
