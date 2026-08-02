/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

/**
 * 用户评价分类结构化输出模型。
 * 配合 BeanOutputConverter<EvaluationResult> 使用，替代原始 JSON 字符串处理，
 * 实现类型安全的 LLM 输出解析。
 *
 * 字段说明：
 * - user: 用户ID
 * - time: 评价时间（格式：yyyy-MM-dd HH:mm:ss）
 * - complaint: 是否为产品投诉（"yes" | "no"）
 * - satisfaction: 客户满意度评分（0-5，0最不满意，5最满意）
 * - summary: 评价摘要
 */
public record EvaluationResult(
        String user,
        String time,
        String complaint,
        int satisfaction,
        String summary
) {}
