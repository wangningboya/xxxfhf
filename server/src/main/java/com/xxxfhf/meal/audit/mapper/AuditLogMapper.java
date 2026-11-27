package com.xxxfhf.meal.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xxxfhf.meal.audit.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
