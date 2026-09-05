package org.example.wavepilot.experiment.repository.mysql;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.*;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
@DependsOn("backendFlyway")
@ConditionalOnProperty(name = "wavepilot.job-repository", havingValue = "mysql")
public class MySqlExperimentJobRepository implements ExperimentJobRepository {
    private final ExperimentJobMapper mapper;
    private final ObjectMapper json;

    public MySqlExperimentJobRepository(ExperimentJobMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public ExperimentJob insertIfAbsent(ExperimentJob job) {
        try {
            mapper.insert(encode(job));
            return job;
        } catch (DuplicateKeyException conflict) {
            // Each INSERT commits separately. Read the committed winner after a conflict.
            return findByIdempotencyKey(job.getIdempotencyKey()).orElseThrow(() -> conflict);
        }
    }

    @Override
    public void attachSource(String jobId, String sourceJobId) {
        if (mapper.attachSource(jobId, sourceJobId) == 0 && !findById(jobId)
                .map(job -> sourceJobId.equals(job.getSourceJobId())).orElse(false))
            throw new IllegalStateException("Cannot attach replay source to " + jobId);
    }

    @Override
    public ExperimentJob save(ExperimentJob job) {
        if (mapper.updateVersioned(encode(job)) != 1) {
            // Preserve the Repository's save(newJob) contract as well as explicit insertIfAbsent.
            if (job.getVersion() == 0 && findById(job.getJobId()).isEmpty()) return insertIfAbsent(job);
            throw new OptimisticLockingFailureException("Job changed concurrently: " + job.getJobId());
        }
        job.setVersion(job.getVersion() + 1);
        return job;
    }

    @Override
    public boolean tryClaim(ExperimentJob job) {
        if (job.getStatus() != ExperimentStatus.QUEUED) return false;
        job.changeStatus(ExperimentStatus.RUNNING, "Worker claimed job");
        if (mapper.claim(encode(job)) != 1) return false;
        job.setVersion(job.getVersion() + 1);
        return true;
    }

    @Override
    public Optional<ExperimentJob> findById(String id) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ExperimentJobRow>().eq("job_id", id)))
                .map(this::decode);
    }

    @Override
    public Optional<ExperimentJob> findByIdempotencyKey(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ExperimentJobRow>().eq("idempotency_key", key)))
                .map(this::decode);
    }

    @Override
    public List<ExperimentJob> findAll() {
        return mapper.selectList(new QueryWrapper<ExperimentJobRow>().orderByDesc("created_at"))
                .stream().map(this::decode).toList();
    }

    private ExperimentJobRow encode(ExperimentJob job) {
        try {
            ExperimentJobRow row = new ExperimentJobRow();
            row.jobId = job.getJobId();
            row.idempotencyKey = job.getIdempotencyKey();
            row.genericSpec = job.getGenericSpec() != null;
            row.specJson = json.writeValueAsString(row.genericSpec ? job.getGenericSpec() : job.getSpec());
            row.planJson = json.writeValueAsString(job.getPlan());
            row.status = job.getStatus().name();
            row.progress = json.writeValueAsString(job.getProgress());
            row.externalJobId = job.getExternalJobId();
            row.sourceJobId = job.getSourceJobId();
            row.failureReason = job.getFailureReason();
            row.version = job.getVersion();
            row.createdAt = job.getCreatedAt();
            row.updatedAt = job.getUpdatedAt();
            return row;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize job " + job.getJobId(), e);
        }
    }

    private ExperimentJob decode(ExperimentJobRow row) {
        try {
            ExperimentPlan plan = json.readValue(row.planJson, ExperimentPlan.class);
            ExperimentProgress progress = json.readValue(row.progress, ExperimentProgress.class);
            ExperimentJob job = row.genericSpec
                    ? new ExperimentJob(row.jobId, json.readValue(row.specJson, GenericExperimentSpec.class), plan,
                        row.createdAt, row.updatedAt, ExperimentStatus.valueOf(row.status), progress,
                        row.externalJobId, row.sourceJobId, row.failureReason)
                    : new ExperimentJob(row.jobId, json.readValue(row.specJson, ExperimentSpec.class), plan,
                        row.createdAt, row.updatedAt, ExperimentStatus.valueOf(row.status), progress,
                        row.externalJobId, row.sourceJobId, row.failureReason);
            job.setIdempotencyKey(row.idempotencyKey);
            job.setVersion(row.version);
            return job;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot restore job " + row.jobId, e);
        }
    }
}
