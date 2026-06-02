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

package com.alibaba.cloud.ai.order;

import com.alibaba.cloud.ai.order.model.OrderCreateRequest;
import com.alibaba.cloud.ai.order.model.OrderQueryRequest;
import com.alibaba.cloud.ai.order.model.OrderResponse;
import com.alibaba.cloud.ai.order.entity.Order;
import com.alibaba.cloud.ai.order.entity.Product;
import com.alibaba.cloud.ai.order.service.OrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 校园事务办理 MCP 工具类。
 * 兼容底层 orders/products 表结构，对 Agent 暴露校园服务事项和办理记录语义。
 */
@Service
public class OrderMcpTools {

    @Autowired
    private OrderService orderService;
    /**
     * 创建校园事务办理/预约记录工具（兼容原订单表结构）。
     */
    @Tool(name = "campus-create-service-record", description = "为指定用户创建校园事务办理或预约记录。适用于图书馆研讨间预约、心理咨询预约、证明材料办理、场馆预约、校园卡补办等服务事项。系统会校验服务事项是否可用以及剩余名额是否充足。")
    public String createOrderWithUser(
            @ToolParam(description = "用户ID，必须为正整数") Long userId,
            @ToolParam(description = "校园服务事项名称，例如：图书馆研讨间预约、心理咨询预约、在读证明办理、校园卡补办、体育馆预约") String productName,
            @ToolParam(description = "办理方式，可选值：线上、线下、自助、窗口、加急") String serviceMode,
            @ToolParam(description = "优先级或时间偏好，可选值：普通、优先、加急、上午、下午、晚上") String priority,
            @ToolParam(description = "预约名额或办理数量，必须为正整数，默认为1") int quantity,
            @ToolParam(description = "办理备注，可填写预约时间、地点偏好、材料说明等") String remark) {
        try {
            Integer serviceModeLevel = convertServiceModeToNumber(serviceMode);
            Integer priorityLevel = convertPriorityToNumber(priority);
            
            OrderCreateRequest request = new OrderCreateRequest(userId, null, productName, 
                    serviceModeLevel, priorityLevel, quantity, remark);
            
            OrderResponse order = orderService.createOrder(request);
            return String.format("办理记录创建成功！记录编号: %s, 用户ID: %d, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元",
                    order.getOrderId(), order.getUserId(), order.getProductName(), 
                    order.getSweetnessText(), order.getIceLevelText(), order.getQuantity(), order.getTotalPrice());
        } catch (Exception e) {
            return "创建办理记录失败: " + e.getMessage();
        }
    }

    /**
     * 查询办理记录工具（兼容原有接口）。
     */
    @Tool(name = "campus-get-service-record", description = "根据记录编号查询校园事务办理/预约记录详情。")
    public String getOrder(@ToolParam(description = "记录编号，兼容 CAMPUS_ 开头的唯一标识符，例如：CAMPUS_20260601001") String orderId) {
        try {
            Order order = orderService.getOrder(orderId);
            if (order == null) {
                return "办理记录不存在: " + orderId;
            }
            
            return String.format("办理记录 - 编号: %s, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元, 创建时间: %s",
                    order.getOrderId(), order.getProductName(), order.getSweetnessText(),
                    order.getIceLevelText(), order.getQuantity(), order.getTotalPrice(), 
                    order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return "查询办理记录失败: " + e.getMessage();
        }
    }
    
    /**
     * 根据用户ID和记录编号查询办理记录工具。
     */
    @Tool(name = "campus-get-service-record-by-user", description = "根据用户ID和记录编号查询该用户自己的校园事务办理/预约记录。只能查询属于该用户的记录。")
    public String getOrderByUser(
            @ToolParam(description = "用户ID，必须为正整数") Long userId,
            @ToolParam(description = "记录编号，兼容 CAMPUS_ 开头的唯一标识符，例如：CAMPUS_20260601001") String orderId) {
        try {
            OrderResponse order = orderService.getOrderByUserIdAndOrderId(userId, orderId);
            if (order == null) {
                return "办理记录不存在: " + orderId + " (用户ID: " + userId + ")";
            }
            
            return String.format("办理记录 - 编号: %s, 用户ID: %d, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元, 创建时间: %s",
                    order.getOrderId(), order.getUserId(), order.getProductName(), 
                    order.getSweetnessText(), order.getIceLevelText(), order.getQuantity(), 
                    order.getTotalPrice(), order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return "查询办理记录失败: " + e.getMessage();
        }
    }

    /**
     * 检查服务事项余量工具。
     */
    @Tool(name = "campus-check-service-capacity", description = "检查指定校园服务事项或资源的剩余名额是否充足，适用于预约前确认资源可用性。")
    public String checkStock(
            @ToolParam(description = "校园服务事项或资源名称") String productName, 
            @ToolParam(description = "需要检查的预约名额或办理数量，必须为正整数") int quantity) {
        try {
            boolean available = orderService.checkStock(productName, quantity);
            return available ? 
                    String.format("服务事项 %s 剩余名额充足，可提供 %d 个名额/数量", productName, quantity) :
                    String.format("服务事项 %s 剩余名额不足，无法提供 %d 个名额/数量", productName, quantity);
        } catch (Exception e) {
            return "检查服务事项余量失败: " + e.getMessage();
        }
    }

    /**
     * 获取所有办理记录工具（兼容原有接口）。
     */
    @Tool(name = "campus-get-service-records", description = "获取系统中所有校园事务办理/预约记录列表。主要用于管理端统计，不应用于普通用户越权查询。")
    public String getAllOrders() {
        try {
            List<Order> orders = orderService.getAllOrders();
            if (orders.isEmpty()) {
                return "当前没有任何办理记录。";
            }
            
            StringBuilder result = new StringBuilder("所有办理记录列表:\n");
            for (Order order : orders) {
                result.append(String.format("- 记录编号: %s, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元, 创建时间: %s\n",
                        order.getOrderId(), order.getProductName(), order.getSweetnessText(),
                        order.getIceLevelText(), order.getQuantity(), order.getTotalPrice(), 
                        order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "获取办理记录列表失败: " + e.getMessage();
        }
    }
    
    /**
     * 根据用户ID获取办理记录列表工具。
     */
    @Tool(name = "campus-get-service-records-by-user", description = "根据用户ID获取该用户自己的校园事务办理/预约记录列表。")
    public String getOrdersByUser(@ToolParam(description = "用户ID，必须为正整数") Long userId) {
        try {
            List<OrderResponse> orders = orderService.getOrdersByUserId(userId);
            if (orders.isEmpty()) {
                return "用户 " + userId + " 当前没有任何办理记录。";
            }
            
            StringBuilder result = new StringBuilder("用户 " + userId + " 的办理记录列表:\n");
            for (OrderResponse order : orders) {
                result.append(String.format("- 记录编号: %s, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元, 创建时间: %s\n",
                        order.getOrderId(), order.getProductName(), order.getSweetnessText(),
                        order.getIceLevelText(), order.getQuantity(), order.getTotalPrice(), 
                        order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "获取用户办理记录列表失败: " + e.getMessage();
        }
    }

    @Tool(name = "campus-get-latest-service-record-by-user", description = "根据用户ID查询该用户最近一次校园事务办理/预约记录。适用于用户询问“上次办的是什么”“按上次预约一样”“和上次一样”等场景。只能查询该用户自己的记录；查询后应先向用户确认，再创建新记录。")
    public String getLatestOrderByUser(@ToolParam(description = "用户ID，必须为正整数") Long userId) {
        try {
            OrderResponse order = orderService.getLatestOrderByUserId(userId);
            if (order == null) {
                return "该用户暂无历史办理记录。";
            }

            return String.format("用户最近一次办理记录 - 记录编号: %s, 用户ID: %d, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元, 创建时间: %s",
                    order.getOrderId(), order.getUserId(), order.getProductName(),
                    order.getSweetnessText(), order.getIceLevelText(), order.getQuantity(),
                    order.getTotalPrice(), order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return "查询用户最近一次办理记录失败: " + e.getMessage();
        }
    }
    
    /**
     * 多维度查询用户办理记录工具。
     */
    @Tool(name = "campus-query-service-records", description = "根据多个条件查询用户自己的校园事务办理/预约记录，支持按服务事项名称、办理方式、优先级/时间偏好、时间范围等条件筛选。")
    public String queryOrders(
            @ToolParam(description = "用户ID，必须为正整数") Long userId,
            @ToolParam(description = "服务事项名称，可选，支持模糊匹配") String productName,
            @ToolParam(description = "办理方式编码，可选，1-线上，2-线下，3-自助，4-窗口，5-加急") Integer sweetness,
            @ToolParam(description = "优先级或时间偏好编码，可选，1-普通，2-优先，3-加急，4-上午，5-下午/晚上") Integer iceLevel,
            @ToolParam(description = "开始时间，可选，格式：yyyy-MM-dd HH:mm:ss") String startTime,
            @ToolParam(description = "结束时间，可选，格式：yyyy-MM-dd HH:mm:ss") String endTime) {
        try {
            OrderQueryRequest request = new OrderQueryRequest(userId);
            request.setProductName(productName);
            request.setSweetness(sweetness);
            request.setIceLevel(iceLevel);
            
            if (startTime != null && !startTime.trim().isEmpty()) {
                request.setStartTime(LocalDateTime.parse(startTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            if (endTime != null && !endTime.trim().isEmpty()) {
                request.setEndTime(LocalDateTime.parse(endTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            
            List<OrderResponse> orders = orderService.queryOrders(request);
            if (orders.isEmpty()) {
                return "未找到符合条件的办理记录。";
            }
            
            StringBuilder result = new StringBuilder("查询结果 (" + orders.size() + " 条记录):\n");
            for (OrderResponse order : orders) {
                result.append(String.format("- 记录编号: %s, 服务事项: %s, 办理方式: %s, 优先级/时间偏好: %s, 数量/名额: %d, 费用: %.2f元, 创建时间: %s\n",
                        order.getOrderId(), order.getProductName(), order.getSweetnessText(),
                        order.getIceLevelText(), order.getQuantity(), order.getTotalPrice(), 
                        order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "查询办理记录失败: " + e.getMessage();
        }
    }
    
    /**
     * 取消办理记录工具。
     */
    @Tool(name = "campus-cancel-service-record", description = "根据用户ID和记录编号取消校园事务办理/预约记录。只能取消属于该用户的记录。")
    public String deleteOrder(
            @ToolParam(description = "用户ID，必须为正整数") Long userId,
            @ToolParam(description = "记录编号，兼容 CAMPUS_ 开头的唯一标识符") String orderId) {
        try {
            boolean deleted = orderService.deleteOrder(userId, orderId);
            if (deleted) {
                return "办理记录取消成功: " + orderId;
            } else {
                return "办理记录取消失败，记录不存在或无权限: " + orderId;
            }
        } catch (Exception e) {
            return "取消办理记录失败: " + e.getMessage();
        }
    }
    
    /**
     * 更新办理记录备注工具。
     */
    @Tool(name = "campus-update-service-record-remark", description = "根据用户ID和记录编号更新校园事务办理/预约记录备注。只能更新属于该用户的记录。")
    public String updateOrderRemark(
            @ToolParam(description = "用户ID，必须为正整数") Long userId,
            @ToolParam(description = "记录编号，兼容 CAMPUS_ 开头的唯一标识符") String orderId,
            @ToolParam(description = "新的备注内容") String remark) {
        try {
            OrderResponse order = orderService.updateOrderRemark(userId, orderId, remark);
            if (order != null) {
                return "办理记录备注更新成功: " + orderId + ", 新备注: " + remark;
            } else {
                return "办理记录备注更新失败，记录不存在或无权限: " + orderId;
            }
        } catch (Exception e) {
            return "更新办理记录备注失败: " + e.getMessage();
        }
    }
    
    /**
     * 验证服务事项是否存在工具。
     */
    @Tool(name = "campus-validate-service-item", description = "验证指定校园服务事项是否存在且可用。")
    public String validateProduct(@ToolParam(description = "校园服务事项名称") String productName) {
        try {
            boolean exists = orderService.validateProduct(productName);
            return exists ? 
                    String.format("服务事项 %s 存在且可用", productName) :
                    String.format("服务事项 %s 不存在或当前不可用", productName);
        } catch (Exception e) {
            return "验证服务事项失败: " + e.getMessage();
        }
    }
    
    /**
     * 办理方式字符串转数字。底层复用 orders.sweetness 字段保存。
     */
    private Integer convertServiceModeToNumber(String serviceMode) {
        if (serviceMode == null) return 1;
        switch (serviceMode.toLowerCase()) {
            case "线上": return 1;
            case "线下": return 2;
            case "自助": return 3;
            case "窗口": return 4;
            case "加急": return 5;
            default: return 1;
        }
    }
    
    /**
     * 优先级或时间偏好字符串转数字。底层复用 orders.ice_level 字段保存。
     */
    private Integer convertPriorityToNumber(String priority) {
        if (priority == null) return 1;
        switch (priority.toLowerCase()) {
            case "普通": return 1;
            case "优先": return 2;
            case "加急": return 3;
            case "上午": return 4;
            case "下午":
            case "晚上": return 5;
            default: return 1;
        }
    }
}
