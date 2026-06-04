CREATE TABLE IF NOT EXISTS conversation_sessions (
  session_id VARCHAR(80) PRIMARY KEY,
  user_id VARCHAR(80) NOT NULL,
  current_requirement_id VARCHAR(80),
  current_plan_id VARCHAR(80),
  current_job_id VARCHAR(80),
  state_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_conversation_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_requirements (
  requirement_id VARCHAR(80) PRIMARY KEY,
  session_id VARCHAR(80) NOT NULL,
  user_id VARCHAR(80) NOT NULL,
  status VARCHAR(40) NOT NULL,
  original_message TEXT,
  spec_json JSON NOT NULL,
  validation_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_requirement_session_id (session_id),
  INDEX idx_requirement_user_id (user_id),
  INDEX idx_requirement_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_plans (
  plan_id VARCHAR(80) PRIMARY KEY,
  requirement_id VARCHAR(80),
  session_id VARCHAR(80) NOT NULL,
  user_id VARCHAR(80) NOT NULL,
  current_version INT NOT NULL DEFAULT 1,
  requirement_spec_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_plan_requirement_id (requirement_id),
  INDEX idx_plan_session_id (session_id),
  INDEX idx_plan_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_plan_versions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  plan_id VARCHAR(80) NOT NULL,
  version INT NOT NULL,
  final_answer MEDIUMTEXT,
  draft_json JSON,
  risk_assessment_json JSON,
  validation_issues_json JSON,
  modification_summary VARCHAR(500),
  user_instruction TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_plan_version (plan_id, version),
  INDEX idx_version_plan_id (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_memories (
  memory_id VARCHAR(80) PRIMARY KEY,
  user_id VARCHAR(80) NOT NULL,
  session_id VARCHAR(80),
  scope VARCHAR(30) NOT NULL,
  type VARCHAR(40) NOT NULL,
  memory_key VARCHAR(120) NOT NULL,
  memory_value TEXT NOT NULL,
  source VARCHAR(40) NOT NULL,
  confidence DECIMAL(4,3) NOT NULL DEFAULT 1.000,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  metadata_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_memory_user_scope (user_id, scope),
  INDEX idx_memory_user_active (user_id, active),
  INDEX idx_memory_key (memory_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS credit_accounts (
  user_id VARCHAR(80) PRIMARY KEY,
  remaining_credits INT NOT NULL DEFAULT 3,
  consumed_credits INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS generation_jobs (
  job_id VARCHAR(80) PRIMARY KEY,
  user_id VARCHAR(80) NOT NULL,
  session_id VARCHAR(80) NOT NULL,
  requirement_id VARCHAR(80),
  plan_id VARCHAR(80),
  status VARCHAR(40) NOT NULL,
  current_stage VARCHAR(80),
  request_json JSON,
  result_json JSON,
  error_message TEXT,
  credit_charged BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  finished_at TIMESTAMP NULL,
  INDEX idx_job_user_id (user_id),
  INDEX idx_job_session_id (session_id),
  INDEX idx_job_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
