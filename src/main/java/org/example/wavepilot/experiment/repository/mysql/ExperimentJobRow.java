package org.example.wavepilot.experiment.repository.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Persistence-only representation. JSON is decoded only inside the Repository. */
@TableName("experiment_job")
public class ExperimentJobRow {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String jobId;
    public String idempotencyKey;
    public String specJson;
    public String planJson;
    public boolean genericSpec;
    public String status;
    public String progress;
    public String externalJobId;
    public String sourceJobId;
    public String failureReason;
    public long version;
    public Instant createdAt;
    public Instant updatedAt;
}
