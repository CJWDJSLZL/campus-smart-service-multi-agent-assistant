package com.alibaba.cloud.ai.demo.golden;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden Set 结构校验测试（H-1 数据质量部分）。
 * 验证 30 条用例数量、字段契约、已知 Agent、工具/关键词非空。
 */
class GoldenSetValidationTest {

    private Path goldenDir() throws URISyntaxException {
        var url = getClass().getClassLoader().getResource("data/golden_set");
        Objects.requireNonNull(url, "golden_set 目录不存在于 classpath");
        return Paths.get(url.toURI());
    }

    @Test
    void 三十条用例全部通过结构校验() throws URISyntaxException {
        GoldenSetRunner.GoldenSetReport report = GoldenSetRunner.validate(goldenDir());

        assertThat(report.totalCases()).isEqualTo(30);
        assertThat(report.issueCount()).as("结构问题: %s", report.issues()).isEqualTo(0);
        assertThat(report.isValid()).isTrue();
    }

    @Test
    void 三类场景各十条且Agent分布正确() throws URISyntaxException {
        GoldenSetRunner.GoldenSetReport report = GoldenSetRunner.validate(goldenDir());

        assertThat(report.casesByAgent())
                .containsEntry("consult_agent", 10)
                .containsEntry("order_agent", 10)
                .containsEntry("feedback_agent", 10);
    }

    @Test
    void 工具与关键词符合契约() throws URISyntaxException {
        List<GoldenSetRunner.GoldenCase> cases = GoldenSetRunner.validate(goldenDir()).cases();
        assertThat(cases).isNotEmpty();
        for (GoldenSetRunner.GoldenCase c : cases) {
            assertThat(c.expectedKeywords()).as("%s 关键词列表", c.id()).isNotEmpty();
            if (!c.clarificationRequired()) {
                // 非澄清场景：信息完整，必须有预期工具调用序列
                assertThat(c.expectedTools()).as("%s 工具列表", c.id()).isNotEmpty();
            }
        }
        // 澄清场景存在（至少 1 条：CASE_O008 等），且允许空工具或查询类工具
        long clarificationCases = cases.stream().filter(GoldenSetRunner.GoldenCase::clarificationRequired).count();
        assertThat(clarificationCases).isGreaterThanOrEqualTo(1);
    }

    @Test
    void consult场景无userId而orderFeedback有() throws URISyntaxException {
        List<GoldenSetRunner.GoldenCase> cases = GoldenSetRunner.validate(goldenDir()).cases();
        for (GoldenSetRunner.GoldenCase c : cases) {
            if ("consult_agent".equals(c.expectedAgent())) {
                assertThat(c.userId()).as("%s 为 consult 场景应无 user_id", c.id()).isNull();
            } else {
                assertThat(c.userId()).as("%s 应含 user_id", c.id()).isNotNull();
            }
        }
    }
}
