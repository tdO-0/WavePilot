package org.example.wavepilot.experiment.outbox.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface OutboxEventMapper extends BaseMapper<OutboxEventRow> {
    /**
     * Optimistic claim: push {@code nextRetryAt} into the future so no other relay can
     * grab the row while this one publishes. If the relay dies mid-publish, the pushed
     * timestamp expires and any relay may re-claim — at-least-once, never lost.
     */
    @Update("""
        UPDATE outbox_event SET next_retry_at=#{until}, version=version+1
        WHERE id=#{id} AND status='PENDING' AND next_retry_at<=#{now} AND version=#{version}
        """)
    int claim(@Param("id") Long id, @Param("version") long version,
              @Param("now") java.time.Instant now, @Param("until") java.time.Instant until);

    @Update("""
        UPDATE outbox_event SET status='SENT', sent_at=#{sentAt}, version=version+1
        WHERE id=#{id} AND status='PENDING' AND version=#{version}
        """)
    int markSent(@Param("id") Long id, @Param("version") long version,
                 @Param("sentAt") java.time.Instant sentAt);

    @Update("""
        UPDATE outbox_event SET status='PENDING', attempt_count=attempt_count+1,
            next_retry_at=#{retryAt}, version=version+1
        WHERE id=#{id} AND status='PENDING' AND version=#{version}
        """)
    int markFailed(@Param("id") Long id, @Param("version") long version,
                   @Param("retryAt") java.time.Instant retryAt);
}
