package com.lcz.yuaiagent.model;

import java.util.UUID;
// model层放实体、DTO、枚举、值对象、VO 这些"描述数据/领域概念"的类
public record UserRow(UUID userId, String passwordHash) {
}
