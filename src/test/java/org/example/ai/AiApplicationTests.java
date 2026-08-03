package org.example.ai;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Initializr 模板生成的上下文冒烟测试。
 *
 * <p>默认禁用：加载完整上下文需要真实运行环境 ——
 * 内网 Chroma 向量库（application.properties 中的 172.21.10.248:8001，
 * 需先 docker compose up）、预置数据的 company_inventory.db、
 * DASHSCOPE_API_KEY / SILICONFLOW_API_KEY 等环境变量。
 * 任一缺失都会导致打包（package 含 test 阶段）超时失败。</p>
 *
 * <p>业务逻辑由其余单元测试覆盖。如需在完整环境中冒烟验证整栈，
 * 临时去掉类上的 {@code @Disabled} 再运行 {@code mvnw test -Dtest=AiApplicationTests}。</p>
 */
@SpringBootTest
@Disabled("需要完整运行环境（内网 Chroma / 预置 SQLite / API key），打包机上必然超时失败；业务逻辑由其余单元测试覆盖")
class AiApplicationTests {

    @Test
    void contextLoads() {
    }

}