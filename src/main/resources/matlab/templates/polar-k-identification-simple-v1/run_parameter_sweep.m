function results = run_parameter_sweep(spec, logFid, outputDirectory)
%RUN_PARAMETER_SWEEP Execute every N/BSC error-rate point independently.

NVec = spec.codeLengths;
errorVec = spec.errorRates;
totalPoints = numel(NVec) * numel(errorVec);

results.NVec = NVec;
results.errorVec = errorVec;
results.trueKVec = zeros(numel(NVec), 1);
results.accuracyMatrix = zeros(numel(NVec), numel(errorVec));
results.estimatedKCell = cell(numel(NVec), numel(errorVec));
results.rows = zeros(totalPoints, 12);
completedPoints = 0;

for nIdx = 1:numel(NVec)
    N = NVec(nIdx);
    trueK = 15 * N / 32;
    if abs(trueK - round(trueK)) > eps
        error('WavePilot:InvalidTrueK', '15*N/32 must be an integer for N=%d.', N);
    end
    trueK = round(trueK);
    results.trueKVec(nIdx) = trueK;

    G = polar_generator_matrix(N);
    reliabilityOrder = bec_reliability_order(N, 0.5);
    infoSet = reliabilityOrder(1:trueK);
    columnWeights = sum(G, 1);

    for eIdx = 1:numel(errorVec)
        epsilon = errorVec(eIdx);
        pointTimer = tic;
        [estimatedK, correctCount] = run_single_case(N, trueK, epsilon, ...
            spec.sampleCount, spec.monteCarloTimes, G, reliabilityOrder, ...
            infoSet, columnWeights);
        runtimeSeconds = toc(pointTimer);
        accuracy = correctCount / spec.monteCarloTimes;

        results.accuracyMatrix(nIdx, eIdx) = accuracy;
        results.estimatedKCell{nIdx, eIdx} = estimatedK;
        completedPoints = completedPoints + 1;
        results.rows(completedPoints, :) = [N, trueK, epsilon, correctCount, ...
            spec.monteCarloTimes, accuracy, spec.sampleCount, spec.randomSeed, ...
            mean(estimatedK), mean(abs(estimatedK - trueK)), ...
            mean(estimatedK - trueK), runtimeSeconds];

        fprintf(logFid, '[%d/%d] N=%d, trueK=%d, errorRate=%.6f, correct=%d/%d, accuracy=%.6f\n', ...
            completedPoints, totalPoints, N, trueK, epsilon, correctCount, ...
            spec.monteCarloTimes, accuracy);
        progress = struct('progress', floor(100 * completedPoints / totalPoints), ...
            'completedRuns', completedPoints, 'totalRuns', totalPoints, ...
            'message', sprintf('MATLAB completed parameter point %d/%d', ...
            completedPoints, totalPoints));
        write_progress(fullfile(outputDirectory, 'matlab-progress.json'), progress);
    end
end
results.completedPoints = completedPoints;
end

function write_progress(path, progress)
fileId = fopen(path, 'w');
if fileId < 0
    error('WavePilot:ProgressWriteFailed', 'Cannot write MATLAB progress.');
end
cleanup = onCleanup(@() fclose(fileId));
fwrite(fileId, jsonencode(progress), 'char');
end
