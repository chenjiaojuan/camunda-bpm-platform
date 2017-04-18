
-- add historic external task log
create table ACT_HI_EXT_TASK_LOG (
    ID_ NVARCHAR2(64) not null,
    TIMESTAMP_ TIMESTAMP(6) not null,
    EXT_TASK_ID_ NVARCHAR2(64) not null,
    RETRIES_ integer,
    TOPIC_NAME_ NVARCHAR2(255),
    WORKER_ID_ NVARCHAR2(255),
    PRIORITY_ NUMBER(19,0) DEFAULT 0 NOT NULL,
    ERROR_MSG_ NVARCHAR2(2000),
    ERROR_DETAILS_ID_ NVARCHAR2(64),
    ACT_ID_ NVARCHAR2(255),
    ACT_INST_ID_ NVARCHAR2(64),
    EXECUTION_ID_ NVARCHAR2(64),
    PROC_INST_ID_ NVARCHAR2(64),
    PROC_DEF_ID_ NVARCHAR2(64),
    PROC_DEF_KEY_ NVARCHAR2(255),
    TENANT_ID_ NVARCHAR2(64),
    STATE_ INTEGER,
    primary key (ID_)
);

create index ACT_HI_EXT_TASK_LOG_PROCINST on ACT_HI_EXT_TASK_LOG(PROC_INST_ID_);
create index ACT_HI_EXT_TASK_LOG_PROCDEF on ACT_HI_EXT_TASK_LOG(PROC_DEF_ID_);
create index ACT_HI_EXT_TASK_LOG_PROC_KEY on ACT_HI_EXT_TASK_LOG(PROC_DEF_KEY_);
create index ACT_HI_EXT_TASK_LOG_TENANT_ID on ACT_HI_EXT_TASK_LOG(TENANT_ID_);

-- salt for password hashing
ALTER TABLE ACT_ID_USER
  ADD SALT_ NVARCHAR2(255);

-- operationId column to link records with those from ACT_HI_OP_LOG
ALTER TABLE ACT_HI_DETAIL
  ADD OPERATION_ID_ NVARCHAR2(64);

-- insert history.cleanup.job.lock in property table
insert into ACT_GE_PROPERTY
values ('history.cleanup.job.lock', '0', 1);

-- timeToLive column for history cleanup
ALTER TABLE ACT_RE_PROCDEF
  ADD TTL_ INTEGER;