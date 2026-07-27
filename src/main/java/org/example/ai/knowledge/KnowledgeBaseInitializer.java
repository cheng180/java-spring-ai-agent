package org.example.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库初始化器 —— 应用启动时把 knowledge/ 下的语料写入 Chroma 向量库
 *
 * 处理策略（按《RAG知识库构建指南》）：
 * - .md  → MarkdownDocumentReader，按标题层级天然成块，不做二次切分，保住语义完整
 * - .txt → TextReader 整读 + TokenTextSplitter(500 token) 滑动窗口切分（长文话术适用）
 * - metadata 打标：source=文件名，type=百科/话术（便于溯源和按类过滤检索）
 *
 * 幂等：启动时先做一次相似度检索，collection 已有数据则跳过，避免重启重复入库。
 * 如需重建索引：清空 Chroma collection 后重启即可。
 *
 * @Order(2)：先于 CarSkuVectorIndexer(3) 执行（不依赖数据库，只读 classpath 文件）
 */
@Component
@Order(2)
public class KnowledgeBaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    /** txt 切分参数：500 token/块，最小块350字符，丢弃5字符以下的碎块 */
    private static final TokenTextSplitter TXT_SPLITTER = TokenTextSplitter.builder()
            .withChunkSize(500)
            .withMinChunkSizeChars(350)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();

    /** 百科类文件（车型参数资料），其余归为话术类 */
    private static final String ENCYCLOPEDIA_FILE = "汽车百科知识库.md";

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourceResolver;

    public KnowledgeBaseInitializer(VectorStore vectorStore,
                                    ResourcePatternResolver resourceResolver) {
        this.vectorStore = vectorStore;
        this.resourceResolver = resourceResolver;
    }

    @Override
    public void run(String... args) throws IOException {
        log.info("=== 开始初始化向量知识库 ===");

        // ---- 幂等检查：collection 已有数据则跳过 ----
        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder().query("汽车").topK(1).build());
        if (!existing.isEmpty()) {
            log.info("向量库已有数据（示例块来源：{}），跳过知识库初始化。" +
                            "如需重建请先清空 Chroma collection: car-sales-kb",
                    existing.get(0).getMetadata().get("source"));
            return;
        }

        // ---- 读取 knowledge/ 下所有 md / txt 文件 ----
        Resource[] mdFiles = resourceResolver.getResources("classpath:knowledge/*.md");
        Resource[] txtFiles = resourceResolver.getResources("classpath:knowledge/*.txt");
        log.info("发现知识库文件：{} 个 md，{} 个 txt", mdFiles.length, txtFiles.length);

        List<Document> chunks = new ArrayList<>();

        // md：按标题成块
        for (Resource file : mdFiles) {
            List<Document> docs = new MarkdownDocumentReader(
                    file, MarkdownDocumentReaderConfig.defaultConfig()).get();
            tag(docs, file.getFilename());
            chunks.addAll(docs);
            log.info("解析 {} → {} 块", file.getFilename(), docs.size());
        }

        // txt：整读后按 token 窗口切分
        for (Resource file : txtFiles) {
            TextReader reader = new TextReader(file);
            reader.getCustomMetadata().put("charset", StandardCharsets.UTF_8.name());
            List<Document> docs = reader.get();
            List<Document> split = TXT_SPLITTER.apply(docs);
            tag(split, file.getFilename());
            chunks.addAll(split);
            log.info("解析 {} → 1 个文档切分为 {} 块", file.getFilename(), split.size());
        }

        if (chunks.isEmpty()) {
            log.warn("没有解析出任何知识块，请检查 src/main/resources/knowledge/ 目录");
            return;
        }

        // ---- embedding + 写入 Chroma（框架自动分批） ----
        log.info("开始向量化并写入 Chroma，共 {} 块...", chunks.size());
        vectorStore.add(chunks);
        log.info("=== 知识库初始化完成，共入库 {} 块 ===", chunks.size());
    }

    /** 给每个块打上来源和类型标签 */
    private void tag(List<Document> docs, String filename) {
        String type = ENCYCLOPEDIA_FILE.equals(filename) ? "百科" : "话术";
        for (Document doc : docs) {
            doc.getMetadata().put("source", filename);
            doc.getMetadata().put("type", type);
        }
    }
}