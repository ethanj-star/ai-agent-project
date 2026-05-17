package com.travel.agent.core.etl;

/**
 * Top-level contract for all data pre-processing (ETL cleaning) strategies.
 * Each implementation targets a specific platform / data shape.
 */
public interface DataPreProcessor {

    /**
     * Clean an input JSONL file and write the filtered result to an output file.
     *
     * @param inputFilePath  absolute path to the raw JSONL source file
     * @param outputFilePath absolute path where the cleaned JSONL file will be written
     * @param dataType       logical type of the records, e.g. "POST" or "COMMENT"
     * @return a human-readable summary: total lines read, lines kept, lines discarded
     */
    String process(String inputFilePath, String outputFilePath, String dataType);
}
