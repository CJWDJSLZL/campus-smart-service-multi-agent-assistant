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

package com.alibaba.cloud.ai.demo.golden;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Golden Set 解析与结构校验执行器。
 *
 * <p>解析 {@code data/golden_set/*.txt}（格式：{@code --- CASE_xxx ---} + {@code 字段: 值} 行），
 * 并按测试报告 V-01/V-01b 确立的字段契约做结构校验：
 * <ul>
 *   <li>order/feedback 场景：每条含 input / user_id / expected_agent / expected_tools / expected_output_keywords 5 字段</li>
 *   <li>consult 场景：无 user_id，仅 4 字段</li>
 *   <li>expected_agent 必须是已知子 Agent；expected_tools / expected_output_keywords 非空</li>
 * </ul>
 *
 * <p>说明：本执行器完成 Golden Set 的<strong>数据质量校验</strong>（H-1 的解析与契约部分）；
 * 运行 Agent 产出"路由/工具/RAG 三维准确率"需在应用运行时调用（见测试方案 §6）。
 *
 * <p>用法：CLI 运行 {@code java ...GoldenSetRunner [goldenDir]}；或调用 {@link #validate(Path)}。
 */
public class GoldenSetRunner {

    private static final Logger logger = LoggerFactory.getLogger(GoldenSetRunner.class);

    /** 已知子 Agent 名称 */
    public static final Set<String> KNOWN_AGENTS = Set.of("consult_agent", "order_agent", "feedback_agent");

    public record GoldenCase(String id, String input, String userId, String expectedAgent,
                             List<String> expectedTools, List<String> expectedKeywords,
                             boolean clarificationRequired) {}

    public record Issue(String caseId, String field, String message) {}

    public record GoldenSetReport(int totalCases, int issueCount, Map<String, Integer> casesByAgent,
                                  List<GoldenCase> cases, List<Issue> issues) {
        public boolean isValid() {
            return issueCount == 0;
        }
    }

    /** 解析单个 golden set 文件 */
    public static List<GoldenCase> parseFile(Path path) throws IOException {
        List<GoldenCase> cases = new ArrayList<>();
        List<String> lines = Files.readAllLines(path);
        String caseId = null;
        Map<String, String> fields = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("--- CASE")) {
                if (caseId != null) {
                    cases.add(buildCase(caseId, fields));
                }
                caseId = line.replace("---", "").trim();
                fields = new LinkedHashMap<>();
            } else if (caseId != null && line.contains(":")) {
                int idx = line.indexOf(':');
                fields.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            } else if (caseId != null && line.equals("---")) {
                cases.add(buildCase(caseId, fields));
                caseId = null;
                fields = null;
            }
        }
        if (caseId != null && fields != null) {
            cases.add(buildCase(caseId, fields));
        }
        return cases;
    }

    private static GoldenCase buildCase(String id, Map<String, String> fields) {
        return new GoldenCase(
                id,
                fields.getOrDefault("input", ""),
                fields.get("user_id"),
                fields.getOrDefault("expected_agent", ""),
                parseList(fields.get("expected_tools")),
                parseList(fields.get("expected_output_keywords")),
                "true".equalsIgnoreCase(fields.getOrDefault("clarification_required", "false")));
    }

    /** 解析形如 [a, b, c] 的列表字段 */
    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String inner = value.replace("[", "").replace("]", "");
        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 校验一个用例，返回问题列表（空 = 合法） */
    public static List<Issue> validateCase(GoldenCase c) {
        List<Issue> issues = new ArrayList<>();
        if (c.input() == null || c.input().isBlank()) {
            issues.add(new Issue(c.id(), "input", "input 为空"));
        }
        if (!KNOWN_AGENTS.contains(c.expectedAgent())) {
            issues.add(new Issue(c.id(), "expected_agent", "未知子 Agent: " + c.expectedAgent()));
        }
        // 字段契约：consult 场景无 user_id；order/feedback 必须有
        boolean consult = "consult_agent".equals(c.expectedAgent());
        if (!consult && (c.userId() == null || c.userId().isBlank())) {
            issues.add(new Issue(c.id(), "user_id", "order/feedback 场景缺少 user_id"));
        }
        if (c.expectedKeywords().isEmpty()) {
            issues.add(new Issue(c.id(), "expected_output_keywords", "expected_output_keywords 为空"));
        }
        // 工具契约：
        // - 非澄清场景：信息完整，必须给出预期工具调用序列（缺失则数据不完整）
        // - 澄清场景：可仅追问（tools 为空，如"帮我预约"），也可先查再确认（tools 含查询工具，如"按上次一样再预约"）
        if (!c.clarificationRequired() && c.expectedTools().isEmpty()) {
            issues.add(new Issue(c.id(), "expected_tools", "非澄清场景缺少 expected_tools"));
        }
        return issues;
    }

    /** 校验整个 golden set 目录，返回结构化报告 */
    public static GoldenSetReport validate(Path goldenDir) {
        List<GoldenCase> all = new ArrayList<>();
        List<Issue> allIssues = new ArrayList<>();
        Map<String, Integer> byAgent = new LinkedHashMap<>();

        if (!Files.isDirectory(goldenDir)) {
            return new GoldenSetReport(0, 1, byAgent, List.of(),
                    List.of(new Issue("-", "-", "目录不存在: " + goldenDir)));
        }

        try (Stream<Path> stream = Files.list(goldenDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
            for (Path f : files) {
                List<GoldenCase> cases = parseFile(f);
                logger.info("GoldenSetRunner: {} -> {} 条", f.getFileName(), cases.size());
                for (GoldenCase c : cases) {
                    all.add(c);
                    byAgent.merge(c.expectedAgent(), 1, Integer::sum);
                    allIssues.addAll(validateCase(c));
                }
            }
        } catch (IOException e) {
            logger.error("GoldenSetRunner: 读取失败", e);
            return new GoldenSetReport(all.size(), 1, byAgent, all,
                    List.of(new Issue("-", "-", "读取失败: " + e.getMessage())));
        }
        return new GoldenSetReport(all.size(), allIssues.size(), byAgent, all, allIssues);
    }

    public static void main(String[] args) throws IOException {
        Path dir = args.length > 0 ? Paths.get(args[0])
                : Paths.get("src/main/resources/data/golden_set");
        GoldenSetReport report = validate(dir);
        System.out.println("========== Golden Set 结构校验报告 ==========");
        System.out.println("总用例数: " + report.totalCases());
        System.out.println("按子 Agent 分布: " + report.casesByAgent());
        System.out.println("问题数: " + report.issueCount());
        if (!report.issues().isEmpty()) {
            report.issues().forEach(i -> System.out.println("  [ISSUE] " + i));
        } else {
            System.out.println("全部用例通过结构校验 ✅");
        }
    }

    public static String summarize(Path goldenDir) {
        GoldenSetReport report = validate(goldenDir);
        return report.casesByAgent().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
