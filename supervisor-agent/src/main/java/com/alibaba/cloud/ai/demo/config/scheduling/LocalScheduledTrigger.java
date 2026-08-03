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

import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 本地定时调度触发器（@Scheduled 替代 XxlJob）。
 *
 * <p>当 {@code xxl.job.enabled=false}（默认值）时自动激活，
 * 无需部署外部 XxlJob Admin 服务即可演示定时 Loop 能力：
 * <ul>
 *   <li>每天 09:00 触发运营日报 Agent（数据分析 + 钉钉推送）</li>
 *   <li>每周一 10:00 触发用户评价分析 Agent（批量评测 + 满意度统计）</li>
 * </ul>
 *
 * <p>与 XxlJob 的差异：
 * <ul>
 *   <li>优点：零依赖，启动即可演示，适合本地开发和 Demo 环境</li>
 *   <li>劣势：无分布式调度、无任务历史记录、无手动触发界面</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "false", matchIfMissing = true)
public class LocalScheduledTrigger {

    private static final Logger logger = LoggerFactory.getLogger(LocalScheduledTrigger.class);

    @Autowired(required = false)
    @Qualifier("operationAnalysisAgent")
    private CompiledGraph dailyReportGraph;

    @Autowired(required = false)
    @Qualifier("evaluationAnalysisAgent")
    private CompiledGraph evaluationGraph;

    /**
     * 每天 09:00 运行运营日报 Agent。
     * 自动拉取最新数据、LLM 分析、推送钉钉日报。
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void runDailyReport() {
        if (dailyReportGraph == null) {
            logger.warn("LocalScheduledTrigger: dailyReportGraph not found, skipping");
            return;
        }
        logger.info("LocalScheduledTrigger: triggering daily report agent");
        try {
            dailyReportGraph.invoke(Map.of());
            logger.info("LocalScheduledTrigger: daily report completed");
        } catch (Exception e) {
            logger.error("LocalScheduledTrigger: daily report failed", e);
        }
    }

    /**
     * 每周一 10:00 运行用户评价分析 Agent。
     * 批量读取 feedback 数据、分类评分、推送投诉分析报告。
     */
    @Scheduled(cron = "0 0 10 ? * MON")
    public void runEvaluation() {
        if (evaluationGraph == null) {
            logger.warn("LocalScheduledTrigger: evaluationGraph not found, skipping");
            return;
        }
        logger.info("LocalScheduledTrigger: triggering evaluation analysis agent");
        try {
            evaluationGraph.invoke(Map.of());
            logger.info("LocalScheduledTrigger: evaluation analysis completed");
        } catch (Exception e) {
            logger.error("LocalScheduledTrigger: evaluation analysis failed", e);
        }
    }
}
