package org.example.wavepilot.experiment.repository.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ExperimentJobMapper extends BaseMapper<ExperimentJobRow> {
    // The version predicate also protects cancellation from stale Worker updates.
    @Update("""
        UPDATE experiment_job SET status=#{r.status}, progress=#{r.progress},
            external_job_id=#{r.externalJobId},
            failure_reason=#{r.failureReason}, updated_at=#{r.updatedAt}, version=version+1
        WHERE job_id=#{r.jobId} AND version=#{r.version}
        """)
    int updateVersioned(@Param("r") ExperimentJobRow row);

    @Update("""
        UPDATE experiment_job SET status='RUNNING', progress=#{r.progress},
            updated_at=#{r.updatedAt}, version=version+1
        WHERE job_id=#{r.jobId} AND status='QUEUED' AND version=#{r.version}
        """)
    int claim(@Param("r") ExperimentJobRow row);

    // Independent immutable provenance link: do not race with the Worker's progress version.
    @Update("UPDATE experiment_job SET source_job_id=#{source} WHERE job_id=#{job} AND source_job_id IS NULL")
    int attachSource(@Param("job") String jobId, @Param("source") String sourceJobId);
}
