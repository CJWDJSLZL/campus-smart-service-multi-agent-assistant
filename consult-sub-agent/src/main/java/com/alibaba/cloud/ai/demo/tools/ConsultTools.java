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

package com.alibaba.cloud.ai.demo.tools;

import com.alibaba.cloud.ai.demo.entity.Product;
import com.alibaba.cloud.ai.demo.service.ConsultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 咨询知识库MCP工具类
 * 提供MCP协议下的知识库检索工具
 */
@Service
public class ConsultTools {

    @Autowired
    private ConsultService consultService;

    /**
     * 知识库检索工具
     */
    @Tool(name="consult-search-knowledge", description = "根据用户查询内容检索校园知识库，包括奖学金政策、事务办理流程、图书馆与场馆预约规则、校园卡、宿舍报修、反馈投诉指引等。支持模糊匹配。")
    public String searchKnowledge(
            @ToolParam(description = "查询内容，可以是政策名称、服务事项、流程关键词或校园资源名称，例如：奖学金申请、图书馆研讨间、校园卡补办、宿舍报修等") String query) {
        try {
            String result = consultService.searchKnowledge(query);
            return result;
        } catch (Exception e) {
            return "知识库检索失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取所有产品列表工具
     */
    @Tool(name="consult-get-products", description = "获取当前可办理或可预约的校园服务事项列表，包括事项名称、说明、费用/占位金额和剩余名额。帮助用户了解可选择的校园服务。")
    public String getProducts() {
        try {
            List<Product> products = consultService.getAllProducts();
            if (products.isEmpty()) {
                return "当前没有任何可用校园服务事项。";
            }
            
            StringBuilder result = new StringBuilder("可用校园服务事项列表:\n");
            for (Product product : products) {
                result.append(String.format("- %s: %s, 费用/占位金额: %.2f元, 剩余名额: %d\n",
                        product.getName(), product.getDescription(), product.getPrice(), product.getStock()));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "获取校园服务事项列表失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取产品详细信息工具
     */
    @Tool(name="consult-get-product-info", description = "获取指定校园服务事项的详细信息，包括事项说明、费用/占位金额、剩余名额、预计办理时间等。")
    public String getProductInfo(@ToolParam(description = "校园服务事项名称，例如：图书馆研讨间预约、心理咨询预约、在读证明办理、校园卡补办、体育馆预约、宿舍报修") String productName) {
        try {
            Product product = consultService.getProductByName(productName);
            if (product == null) {
                return "校园服务事项不存在或当前不可用: " + productName;
            }
            
            return String.format("校园服务事项信息:\n名称: %s\n说明: %s\n费用/占位金额: %.2f元\n剩余名额: %d\n有效期/开放时长: %d分钟\n预计办理时间: %d分钟",
                    product.getName(), product.getDescription(), product.getPrice(), 
                    product.getStock(), product.getShelfTime(), product.getPreparationTime());
        } catch (Exception e) {
            return "获取校园服务事项信息失败: " + e.getMessage();
        }
    }
    
    /**
     * 根据产品名称模糊搜索产品工具
     */
    @Tool(name="consult-search-products", description = "根据校园服务事项名称进行模糊搜索，返回匹配的服务事项列表。支持部分名称搜索，例如搜索'图书馆'、'证明'、'宿舍'。")
    public String searchProducts(@ToolParam(description = "校园服务事项关键词，支持模糊匹配，例如：图书馆、证明、宿舍、校园卡、心理咨询") String productName) {
        try {
            List<Product> products = consultService.searchProductsByName(productName);
            if (products.isEmpty()) {
                return "未找到匹配的校园服务事项: " + productName;
            }
            
            StringBuilder result = new StringBuilder("搜索结果 (" + products.size() + " 个校园服务事项):\n");
            for (Product product : products) {
                result.append(String.format("- %s: %s, 费用/占位金额: %.2f元, 剩余名额: %d\n",
                        product.getName(), product.getDescription(), product.getPrice(), product.getStock()));
            }
            
            return result.toString();
        } catch (Exception e) {
            return "搜索校园服务事项失败: " + e.getMessage();
        }
    }
}
