function summary = export_results(spec, results, outputDirectory, startedAt, ...
    totalRuntimeSeconds, algorithmName, algorithmVersion, templateVersion)
%EXPORT_RESULTS Export the strict Phase 4.5 CSV, JSON and MAT contracts.

resultTable = array2table(results.rows, 'VariableNames', { ...
    'codeLength', 'trueK', 'errorRate', 'correctCount', ...
    'monteCarloTimes', 'accuracy', 'sampleCount', 'randomSeed', ...
    'meanEstimatedK', 'mae', 'bias', 'runtimeSeconds'});
resultTable.algorithmVersion = repmat(string(algorithmVersion), height(resultTable), 1);
writetable(resultTable, fullfile(outputDirectory, 'accuracy.csv'));

summary = struct();
summary.experimentType = 'POLAR_CODE_K_IDENTIFICATION';
summary.algorithmName = algorithmName;
summary.algorithmVersion = algorithmVersion;
summary.templateVersion = templateVersion;
summary.runnerType = 'local-matlab';
summary.mock = false;
summary.algorithmValidated = false;
summary.classification = 'SIMPLIFIED_BASELINE';
summary.validationNote = ['Teaching/integration baseline with real polar encoding ', ...
    'and statistical K estimation; not a paper reproduction or standardized algorithm.'];
summary.errorRateMeaning = 'BSC_BIT_FLIP_PROBABILITY';
summary.sampleCountMeaning = 'INTERCEPTED_COMPLETE_CODEWORDS_PER_TRIAL';
summary.monteCarloTimesMeaning = 'INDEPENDENT_TRIALS_PER_PARAMETER_POINT';
summary.trueKRule = '15N/32';
summary.randomSeed = spec.randomSeed;
summary.totalPoints = numel(results.NVec) * numel(results.errorVec);
summary.completedPoints = results.completedPoints;
summary.minAccuracy = min(resultTable.accuracy);
summary.maxAccuracy = max(resultTable.accuracy);
summary.meanAccuracy = mean(resultTable.accuracy);
summary.averageAccuracy = summary.meanAccuracy;
summary.rowCount = height(resultTable);
summary.totalRuntimeSeconds = totalRuntimeSeconds;
summary.matlabVersion = version;
summary.startedAt = char(startedAt);
summary.finishedAt = char(datetime('now', 'TimeZone', 'UTC'));
summary.success = true;
write_json(fullfile(outputDirectory, 'summary.json'), summary);

NVec = results.NVec;
errorVec = results.errorVec;
trueKVec = results.trueKVec;
accuracyMatrix = results.accuracyMatrix;
estimatedKMatrix = zeros(numel(NVec), numel(errorVec), spec.monteCarloTimes);
for nIdx = 1:numel(NVec)
    for eIdx = 1:numel(errorVec)
        estimatedKMatrix(nIdx, eIdx, :) = results.estimatedKCell{nIdx, eIdx};
    end
end
% Use uncompressed Level-5 MAT so Java can deterministically verify the
% required numeric variable names without a MATLAB parser dependency.
save(fullfile(outputDirectory, 'result.mat'), 'NVec', 'errorVec', ...
    'trueKVec', 'accuracyMatrix', 'estimatedKMatrix', '-v6');
end

function write_json(path, value)
fileId = fopen(path, 'w');
if fileId < 0
    error('WavePilot:JsonWriteFailed', 'Cannot open %s for writing.', path);
end
cleanup = onCleanup(@() fclose(fileId));
fwrite(fileId, jsonencode(value, 'PrettyPrint', true), 'char');
end
