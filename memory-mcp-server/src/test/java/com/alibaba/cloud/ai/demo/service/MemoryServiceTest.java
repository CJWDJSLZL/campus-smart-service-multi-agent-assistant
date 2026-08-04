package com.alibaba.cloud.ai.demo.service;

import com.alibaba.cloud.ai.demo.config.Mem0Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * L1 集成验证 V-11 / V-12：
 * 长期记忆「检索限制两周窗口」（MemoryService.searchMemory 请求体）
 * 与「写入异步无感」（storeMemory 立即返回，不阻塞）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ApplicationContext applicationContext;

    private Mem0Config config;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        config = new Mem0Config();
        config.getApi().setUrl("https://api.mem0.ai");
        config.getApi().setKey("test-key");
        memoryService = new MemoryService(restTemplate, config, applicationContext);
    }

    @Test
    void searchMemory_请求体包含两周时间窗口_V11() throws Exception {
        // 模拟 Mem0 返回空结果
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("[]"));

        String result = memoryService.searchMemory("10001", "用户偏好和历史习惯");

        assertThat(result).isEqualTo("未找到用户历史喜好");

        // 捕获请求体，验证 created_at 窗口 = [今天-14天, 明天]
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq("https://api.mem0.ai/v2/memories/search/"), captor.capture(), eq(String.class));

        HttpEntity<String> entity = captor.getValue();
        HttpHeaders headers = entity.getHeaders();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Token test-key");

        String body = entity.getBody();
        assertThat(body).contains("user_id").contains("10001");
        assertThat(body).contains("created_at").contains("gte").contains("lte");

        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String expectGte = today.minusWeeks(2).format(fmt);
        String expectLte = today.plusDays(1).format(fmt);
        assertThat(body).contains("\"gte\":\"" + expectGte + "\"");
        assertThat(body).contains("\"lte\":\"" + expectLte + "\"");
    }

    @Test
    void searchMemory_有结果时返回记忆内容() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("[{\"memory\":\"偏好晚上预约图书馆\"},{\"memory\":\"关注奖学金政策\"}]"));

        String result = memoryService.searchMemory("10001", "用户偏好和历史习惯");

        assertThat(result).isEqualTo("偏好晚上预约图书馆\n关注奖学金政策");
    }

    @Test
    void storeMemory_立即返回不阻塞_V12() {
        // storeMemory 返回"成功存储用户喜好"，不依赖外部调用结果
        MemoryService mockSelf = mock(MemoryService.class);
        when(applicationContext.getBean(MemoryService.class)).thenReturn(mockSelf);

        long start = System.currentTimeMillis();
        String result = memoryService.storeMemory("10001", "偏好晚上预约");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isEqualTo("成功存储用户喜好");
        // 同步路径下不等待外部 HTTP 调用（无 RestTemplate 调用）
        verify(restTemplate, never()).postForEntity(anyString(), any(HttpEntity.class), any(Class.class));
        assertThat(elapsed).isLessThan(1000);
    }

    @Test
    void storeMemoryAsync_请求体正确() throws Exception {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"mem-1\"}"));

        memoryService.storeMemoryAsync("10001", "偏好晚上预约");

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq("https://api.mem0.ai/v1/memories/"), captor.capture(), eq(String.class));
        String body = captor.getValue().getBody();
        assertThat(body).contains("user_id").contains("10001");
        assertThat(body).contains("偏好晚上预约");
    }
}
