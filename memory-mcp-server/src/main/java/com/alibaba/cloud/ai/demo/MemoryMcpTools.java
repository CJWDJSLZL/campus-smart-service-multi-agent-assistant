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

package com.alibaba.cloud.ai.demo;

import com.alibaba.cloud.ai.demo.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemoryMcpTools {
    private static final Logger logger = LoggerFactory.getLogger(MemoryMcpTools.class);

    @Autowired
    private MemoryService memoryService;
    
    /**
     * 存储用户记忆
     */
    @Tool(name = "memory-store", description = "存储用户在校园服务中的长期偏好和习惯信息，包括回答形式偏好、常用校园服务、预约时间偏好、材料准备习惯、关注事项、情绪反馈等，为个性化咨询、事务办理和反馈处理提供长期记忆支持")
    public String storeMemory(
            @ToolParam(description = "用户唯一标识符，用于关联用户的所有记忆信息") String userId,
            @ToolParam(description = "用户偏好和习惯的详细描述，例如：喜欢流程图式回答、常预约图书馆研讨间、偏好下午办理、经常咨询奖学金政策、关注宿舍维修效率、希望回复更简洁等") String content ) {
        return memoryService.storeMemory(userId, content);
    }
    
    /**
     * 搜索用户历史记忆
     */
    @Tool(name = "memory-search", description = "检索用户在校园服务中的历史偏好、办理习惯、关注事项和反馈信息，支持个性化政策咨询、事务办理和投诉反馈处理")
    public String searchMemory(@ToolParam(description = "用户唯一标识符，用于检索该用户的所有记忆信息") String userId,
                               @ToolParam(description = "检索查询语句，可以是具体偏好类型（如'回答形式偏好'、'预约时间偏好'）、校园服务事项（如'图书馆研讨间'、'奖学金'）、行为模式（如'办理习惯'）或情感关键词（如'满意'、'不满意'）") String query) {
        return memoryService.searchMemory(userId, query);
    }
}
