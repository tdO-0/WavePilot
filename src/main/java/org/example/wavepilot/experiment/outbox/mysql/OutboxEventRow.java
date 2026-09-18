package org.example.wavepilot.experiment.outbox.mysql;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** Persistence-only representation; JSON is decoded only inside the relay/store. */
@TableName("outbox_event")
public class OutboxEventRow {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String jobId;
    public String eventType;
    public String payloadJson;
    public String status;
    public int attemptCount;
    public Instant nextRetryAt;
    public Instant createdAt;
    public Instant sentAt;
    public long version;
}
