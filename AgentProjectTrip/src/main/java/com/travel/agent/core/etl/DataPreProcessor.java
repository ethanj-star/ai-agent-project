package com.travel.agent.core.etl;

/**
 * 数据预处理策略顶层接口（Core 层 - ETL 清洗契约）。
 *
 * <p>系统架构位置：EtlController / 后台任务 -> <b>DataPreProcessor</b> -> 具体平台清洗实现</p>
 *
 * <p>职责：
 * <ul>
 *   <li>定义 JSONL 原始数据清洗的统一入口。</li>
 *   <li>允许不同平台或不同数据结构实现自己的过滤、字段瘦身和格式规范化规则。</li>
 *   <li>让 Web / Job 层只依赖接口，不需要知道小红书、评论、主帖等具体清洗细节。</li>
 * </ul>
 * </p>
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
