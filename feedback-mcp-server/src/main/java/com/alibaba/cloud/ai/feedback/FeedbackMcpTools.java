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

package com.alibaba.cloud.ai.feedback;

import com.alibaba.cloud.ai.feedback.entity.Feedback;
import com.alibaba.cloud.ai.feedback.service.FeedbackService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeedbackMcpTools {

    @Autowired
    private FeedbackService feedbackService;
    
    /**
     * 创建用户反馈
     */
    @Tool(name = "feedback-create-feedback", description = "创建校园服务反馈、投诉、建议或评价记录，userId 是必填项。适用于图书馆、教务、宿舍、场馆、校园卡、后勤等服务体验反馈。")
    public String createFeedback(
            @ToolParam(description = "用户ID，必填") Long userId,
            @ToolParam(description = "反馈类型：1-服务事项反馈，2-校园服务反馈，3-投诉，4-建议") Integer feedbackType,
            @ToolParam(description = "反馈内容，需包含用户遇到的问题、诉求或建议") String content,
            @ToolParam(description = "关联办理/预约记录编号，可选") String orderId,
            @ToolParam(description = "评分1-5星，可选") Integer rating) {
        
        try {
            Feedback feedback = new Feedback();
            feedback.setUserId(userId);
            feedback.setFeedbackType(feedbackType);
            feedback.setContent(content);
            if (orderId != null && !orderId.trim().isEmpty()) {
                feedback.setOrderId(orderId);
            }
            if (rating != null) {
                feedback.setRating(rating);
            }
            
            Feedback createdFeedback = feedbackService.createFeedback(feedback);
            return String.format("反馈记录创建成功！反馈ID: %d, 用户ID: %d, 反馈类型: %s, 内容: %s", 
                    createdFeedback.getId(), 
                    createdFeedback.getUserId(), 
                    createdFeedback.getFeedbackTypeText(),
                    createdFeedback.getContent());
        } catch (Exception e) {
            return "创建反馈记录失败: " + e.getMessage();
        }
    }
    
    /**
     * 根据用户ID查询反馈记录
     */
    @Tool(name = "feedback-get-feedback-by-user", description = "根据用户ID查询该用户提交过的校园反馈、投诉、建议或评价记录")
    public String getFeedbacksByUserId(@ToolParam(description = "用户ID") Long userId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByUserId(userId);
            if (feedbacks.isEmpty()) {
                return "该用户暂无反馈记录";
            }
            
            StringBuilder result = new StringBuilder();
            result.append(String.format("用户 %d 的反馈记录（共 %d 条）：\n", userId, feedbacks.size()));
            
            for (Feedback feedback : feedbacks) {
                result.append(String.format("- 反馈ID: %d, 类型: %s, 评分: %s, 内容: %s, 时间: %s\n",
                        feedback.getId(),
                        feedback.getFeedbackTypeText(),
                        feedback.getRatingText(),
                        feedback.getContent(),
                        feedback.getCreatedAt()));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "查询用户反馈记录失败: " + e.getMessage();
        }
    }
    
    /**
     * 根据办理/预约记录编号查询反馈记录
     */
    @Tool(name= "feedback-get-feedback-by-order", description = "根据办理/预约记录编号查询关联反馈记录")
    public String getFeedbacksByOrderId(@ToolParam(description = "办理/预约记录编号") String orderId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByOrderId(orderId);
            if (feedbacks.isEmpty()) {
                return "该办理/预约记录暂无反馈记录";
            }
            
            StringBuilder result = new StringBuilder();
            result.append(String.format("办理/预约记录 %s 的反馈记录（共 %d 条）：\n", orderId, feedbacks.size()));
            
            for (Feedback feedback : feedbacks) {
                result.append(String.format("- 反馈ID: %d, 用户ID: %d, 类型: %s, 评分: %s, 内容: %s, 时间: %s\n",
                        feedback.getId(),
                        feedback.getUserId(),
                        feedback.getFeedbackTypeText(),
                        feedback.getRatingText(),
                        feedback.getContent(),
                        feedback.getCreatedAt()));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "查询办理/预约记录反馈失败: " + e.getMessage();
        }
    }
    
    /**
     * 更新反馈解决方案
     */
    @Tool(name= "feedback-update-solution", description = "更新校园反馈或投诉的处理方案、回复口径或后续跟进说明")
    public String updateFeedbackSolution(
            @ToolParam(description = "反馈ID") Long feedbackId,
            @ToolParam(description = "解决方案") String solution) {
        try {
            boolean success = feedbackService.updateFeedbackSolution(feedbackId, solution);
            if (success) {
                return String.format("反馈ID %d 的解决方案更新成功：%s", feedbackId, solution);
            } else {
                return String.format("反馈ID %d 的解决方案更新失败", feedbackId);
            }
        } catch (Exception e) {
            return "更新反馈解决方案失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取反馈类型文本
     */
    private String getFeedbackTypeText(Integer feedbackType) {
        if (feedbackType == null) return "未知";
        switch (feedbackType) {
            case 1: return "服务事项反馈";
            case 2: return "校园服务反馈";
            case 3: return "投诉";
            case 4: return "建议";
            default: return "未知";
        }
    }
}
