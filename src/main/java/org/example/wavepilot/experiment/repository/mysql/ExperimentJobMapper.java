package org.example.wavepilot.experiment.repository.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ExperimentJobMapper extends BaseMapper<ExperimentJobRow> {
    // The version predicate also protects cancellation from stale Worker updates.
    // The expected-status, worker and fencing predicates pin the transition source so a
    // stale in-memory writer can never flip a state that changed underneath it. The
    // null-safe <=> operator keeps unclaimed rows (worker_id IS NULL) writable by the
    // api role, whose in-memory job has no worker identity.
    @Update("""
        UPDATE experiment_job SET status=#{r.status}, progress=#{r.progress},
            external_job_id=#{r.externalJobId},
            failure_reason=#{r.failureReason}, updated_at=#{r.updatedAt}, version=version+1
        WHERE job_id=#{r.jobId} AND version=#{r.version} AND status=#{expectedStatus}
          AND worker_id <=> #{r.workerId} AND fencing_token=#{r.fencingToken}
        """)
    int updateVersioned(@Param("r") ExperimentJobRow row, @Param("expectedStatus") String expectedStatus);

    // Atomic claim: only the winner may submit to the Runner. Each claim binds the winner's
    // worker id, renews the lease deadline and bumps the fencing token, so a previous owner
    // can never write to the row again (fencing_token + version are checked on every write).
    @Update("""
        UPDATE experiment_job SET status='RUNNING', worker_id=#{r.workerId},
            lease_until=#{r.leaseUntil}, heartbeat_at=#{r.heartbeatAt},
            attempt=attempt+1, fencing_token=fencing_token+1,
            progress=#{r.progress}, updated_at=#{r.updatedAt}, version=version+1
        WHERE job_id=#{r.jobId} AND status='QUEUED' AND version=#{r.version}
        """)
    int claim(@Param("r") ExperimentJobRow row);

    // Heartbeat renews the lease without bumping version: progress writes (which do bump
    // version) must never fail because a concurrent heartbeat advanced the row.
    @Update("""
        UPDATE experiment_job SET heartbeat_at=#{heartbeatAt}, lease_until=#{leaseUntil}
        WHERE job_id=#{jobId} AND worker_id=#{workerId} AND fencing_token=#{fencingToken}
          AND status IN ('RUNNING', 'VALIDATING_RESULT')
        """)
    int heartbeat(@Param("jobId") String jobId, @Param("workerId") String workerId,
                  @Param("fencingToken") long fencingToken,
                  @Param("heartbeatAt") Instant heartbeatAt, @Param("leaseUntil") Instant leaseUntil);

    // Lease recovery: only the row still carrying the observed fencing token and version
    // can be requeued; the token bump fences off the dead owner's in-flight writes.
    @Update("""
        UPDATE experiment_job SET status='QUEUED', worker_id=NULL, lease_until=NULL,
            heartbeat_at=NULL, fencing_token=fencing_token+1, failure_reason=NULL,
            progress=#{r.progress}, updated_at=#{r.updatedAt}, version=version+1
        WHERE job_id=#{r.jobId} AND status=#{expectedStatus}
          AND fencing_token=#{r.fencingToken} AND version=#{r.version}
        """)
    int requeue(@Param("r") ExperimentJobRow row, @Param("expectedStatus") String expectedStatus);

    @Select("""
        SELECT * FROM experiment_job
        WHERE status IN ('RUNNING', 'VALIDATING_RESULT')
          AND lease_until IS NOT NULL AND lease_until < #{now}
        ORDER BY lease_until ASC
        LIMIT #{limit}
        """)
    List<ExperimentJobRow> selectExpiredLeases(@Param("now") Instant now, @Param("limit") int limit);

    @Select("SELECT status AS status, COUNT(*) AS cnt FROM experiment_job GROUP BY status")
    List<java.util.Map<String, Object>> countByStatus();

    // Independent immutable provenance link: do not race with the Worker's progress version.
    @Update("UPDATE experiment_job SET source_job_id=#{source} WHERE job_id=#{job} AND source_job_id IS NULL")
    int attachSource(@Param("job") String jobId, @Param("source") String sourceJobId);
}
