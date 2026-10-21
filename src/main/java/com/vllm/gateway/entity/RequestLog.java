package com.vllm.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("request_log")
public class RequestLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long apiKeyId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private String requestIp;
    private Long costMs;
    private Integer status;
    private String errorMsg;
    private String requestTime;
}
