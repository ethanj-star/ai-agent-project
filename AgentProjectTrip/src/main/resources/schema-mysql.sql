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

CREATE TABLE IF NOT EXISTS travel_countries (
  country_code VARCHAR(10) PRIMARY KEY,
  country_name VARCHAR(120) NOT NULL,
  local_name VARCHAR(120),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_country_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS travel_pois (
  poi_id VARCHAR(80) PRIMARY KEY,
  country_code VARCHAR(10) NOT NULL,
  city_name VARCHAR(120) NOT NULL,
  poi_name VARCHAR(200) NOT NULL,
  local_name VARCHAR(200),
  tags_json JSON,
  popularity_level INT NOT NULL DEFAULT 3,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  rag_coverage_status VARCHAR(40) NOT NULL DEFAULT 'MISSING',
  notes TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_poi_country (country_code),
  INDEX idx_poi_city (city_name),
  INDEX idx_poi_enabled (enabled),
  INDEX idx_poi_coverage (rag_coverage_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS poi_collection_profiles (
  profile_id VARCHAR(80) PRIMARY KEY,
  poi_id VARCHAR(80) NOT NULL,
  style_tag VARCHAR(80) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  coverage_status VARCHAR(40) NOT NULL DEFAULT 'MISSING',
  last_collected_at TIMESTAMP NULL,
  notes TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_poi_style (poi_id, style_tag),
  INDEX idx_profile_poi (poi_id),
  INDEX idx_profile_enabled (enabled),
  INDEX idx_profile_coverage (coverage_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS crawl_tasks (
  task_id VARCHAR(80) PRIMARY KEY,
  status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
  country_codes_json JSON,
  style_tags_json JSON,
  keyword_count INT NOT NULL DEFAULT 0,
  base_config_path VARCHAR(500),
  config_preview MEDIUMTEXT,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_crawl_task_status (status),
  INDEX idx_crawl_task_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS crawl_task_keywords (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id VARCHAR(80) NOT NULL,
  keyword_order INT NOT NULL,
  keyword_text VARCHAR(300) NOT NULL,
  selected BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_keyword_order (task_id, keyword_order),
  INDEX idx_task_keyword_task_id (task_id),
  INDEX idx_task_keyword_selected (selected)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO travel_countries (country_code, country_name, local_name, enabled) VALUES
('FR', 'France', '法国', TRUE),
('IT', 'Italy', '意大利', TRUE),
('CH', 'Switzerland', '瑞士', TRUE);

INSERT IGNORE INTO travel_pois
(poi_id, country_code, city_name, poi_name, local_name, tags_json, popularity_level, enabled, rag_coverage_status, notes)
VALUES
('poi-fr-paris-louvre', 'FR', 'Paris', 'Louvre Museum', '卢浮宫', JSON_ARRAY('museum', 'classic', 'ticket', 'crowd'), 5, TRUE, 'MISSING', '巴黎核心博物馆，后续需要门票、闭馆日和避峰攻略。'),
('poi-fr-paris-montmartre', 'FR', 'Paris', 'Montmartre', '蒙马特', JSON_ARRAY('art', 'walk', 'photo', 'classic'), 4, TRUE, 'MISSING', '适合城市漫步和拍照，但热门时段人多。'),
('poi-fr-nice-old-town', 'FR', 'Nice', 'Nice Old Town', '尼斯老城', JSON_ARRAY('old-town', 'food', 'walk', 'coast'), 4, TRUE, 'MISSING', '南法海岸城市体验样板。'),
('poi-fr-annecy-lake', 'FR', 'Annecy', 'Lake Annecy', '安纳西湖', JSON_ARRAY('lake', 'nature', 'slow-travel', 'photo'), 4, TRUE, 'MISSING', '小众慢游和自然风景内容优先补充。'),
('poi-it-rome-colosseum', 'IT', 'Rome', 'Colosseum', '罗马斗兽场', JSON_ARRAY('history', 'classic', 'ticket', 'crowd'), 5, TRUE, 'MISSING', '罗马核心景点，需要预约、排队和避坑内容。'),
('poi-it-rome-vatican', 'IT', 'Rome', 'Vatican Museums', '梵蒂冈博物馆', JSON_ARRAY('museum', 'religion', 'ticket', 'crowd'), 5, TRUE, 'MISSING', '强预约属性，后续交给事实核查确认开放时间。'),
('poi-it-florence-uffizi', 'IT', 'Florence', 'Uffizi Gallery', '乌菲兹美术馆', JSON_ARRAY('museum', 'renaissance', 'ticket', 'classic'), 5, TRUE, 'MISSING', '佛罗伦萨核心美术馆，适合文艺复兴主题内容。'),
('poi-it-cinque-terre', 'IT', 'Liguria', 'Cinque Terre', '五渔村', JSON_ARRAY('coast', 'hiking', 'photo', 'crowd'), 4, TRUE, 'MISSING', '热门但可做避峰和徒步路线攻略。'),
('poi-ch-zurich-old-town', 'CH', 'Zurich', 'Zurich Old Town', '苏黎世老城', JSON_ARRAY('old-town', 'walk', 'transit', 'first-time'), 4, TRUE, 'MISSING', '瑞士入门城市与交通枢纽。'),
('poi-ch-interlaken', 'CH', 'Interlaken', 'Interlaken', '因特拉肯', JSON_ARRAY('nature', 'mountain', 'transit', 'classic'), 5, TRUE, 'MISSING', '瑞士山地路线核心节点。'),
('poi-ch-jungfrau', 'CH', 'Jungfrau Region', 'Jungfrau Region', '少女峰地区', JSON_ARRAY('mountain', 'train', 'weather-risk', 'budget'), 5, TRUE, 'MISSING', '高预算、高天气敏感度，后续需要事实核查和预算提醒。'),
('poi-ch-lucerne', 'CH', 'Lucerne', 'Lucerne Old Town', '卢塞恩老城', JSON_ARRAY('lake', 'old-town', 'classic', 'walk'), 4, TRUE, 'MISSING', '瑞士湖区和老城慢游样板。');
