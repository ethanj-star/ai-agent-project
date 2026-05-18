package com.travel.agent.core.etl;

/**
 * ── 数据预处理策略顶层接口 ───────────────────────────────────────
 * 所有数据预处理（ETL 清洗）策略的顶层契约。
 * 采用了策略模式（Strategy Pattern）的设计思想，每个具体的实现类都将针对特定的平台或特定的数据结构进行定制化处理。
 */
public interface DataPreProcessor {

    /**
     * 清洗输入的 JSONL 文件，并将过滤后的干净数据写入到指定的输出文件中。
     *
     * @param inputFilePath  原始 JSONL 源文件的绝对路径（待清洗的数据源）
     * @param outputFilePath 清洗后生成的新 JSONL 文件的绝对路径（清洗结果落盘位置）
     * @param dataType       记录的逻辑类型（例如："POST" 代表主帖，"COMMENT" 代表评论），方便具体实现类根据类型应用不同的清洗规则
     * @return 返回一段便于人类阅读的处理结果摘要文本：通常包含总读取行数、保留行数、丢弃/过滤的行数等信息，方便上层调用者打印日志或发送监控告警
     */
    String process(String inputFilePath, String outputFilePath, String dataType);
}