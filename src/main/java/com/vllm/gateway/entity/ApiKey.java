package com.vllm.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String keyHash;
    private String keyPrefix;
    private String remark;
    private Integer enabled;
    private String expireTime;
    private String allowModels;
    private String createTime;
}
