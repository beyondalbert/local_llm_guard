package com.vllm.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("chat_session")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private Long apiKeyId;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private String createTime;
    private String updateTime;
}
