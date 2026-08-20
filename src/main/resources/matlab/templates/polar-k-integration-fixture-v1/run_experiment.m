function run_experiment(inputFile, outputDirectory)
%RUN_EXPERIMENT Phase 4 energy-threshold integration fixture.
% This is not a polar-code research algorithm. It exists only to exercise
% the local MATLAB process, timeout, cancellation, log and Artifact chain.

if nargin ~= 2
    error('WavePilot:InvalidArguments', 'Expected input JSON and output directory.');
end
if ~isfile(inputFile)
    error('WavePilot:MissingInput', 'Input JSON does not exist.');
end
if ~isfolder(outputDirectory)
    mkdir(outputDirectory);
end

spec = jsondecode(fileread(inputFile));
codeLengths = double(spec.codeLengths(:));
errorRateStart = double(spec.errorRateStart);
errorRateEnd = double(spec.errorRateEnd);
errorRateStep = double(spec.errorRateStep);
sampleCount = double(spec.sampleCount);
monteCarloTimes = double(spec.monteCarloTimes);
randomSeed = mod(double(spec.randomSeed), 2^32 - 1);

ratePointCount = floor((errorRateEnd - errorRateStart) / errorRateStep + 1.0e-10) + 1;
errorRates = errorRateStart + (0:(ratePointCount - 1)) * errorRateStep;
totalRuns = numel(codeLengths) * numel(errorRates);

rng(randomSeed, 'twister');
fprintf('WAVEPILOT_MATLAB_VERSION=%s\n', version);
fprintf('WAVEPILOT_TEMPLATE=polar-k-integration-fixture-v1\n');
fprintf('WAVEPILOT_ALGORITHM_VALIDATED=false\n');

csvPath = fullfile(outputDirectory, 'accuracy.csv');
csvId = fopen(csvPath, 'w');
if csvId < 0
    error('WavePilot:CsvOpenFailed', 'Could not create accuracy.csv.');
end
csvCleanup = onCleanup(@() close_file(csvId));
fprintf(csvId, 'codeLength,errorRate,accuracy\n');

rowCodeLength = zeros(totalRuns, 1);
rowErrorRate = zeros(totalRuns, 1);
rowAccuracy = zeros(totalRuns, 1);
completedRuns = 0;

for lengthIndex = 1:numel(codeLengths)
    codeLength = codeLengths(lengthIndex);
    trueK = codeLength / 2;
    for rateIndex = 1:numel(errorRates)
        errorRate = errorRates(rateIndex);
        successes = 0;

        % Fixed, toolbox-free energy fixture. It does not encode a polar code.
        attenuation = max(0.05, 1.0 - 1.5 * errorRate);
        noiseSigma = 0.05 + 2.5 * errorRate;
        decisionThreshold = noiseSigma^2 + 0.5 * attenuation^2;

        for trial = 1:monteCarloTimes
            activeSymbols = 2.0 * (rand(sampleCount, trueK) > 0.5) - 1.0;
            activeObservations = attenuation * activeSymbols ...
                + noiseSigma * randn(sampleCount, trueK);
            inactiveObservations = noiseSigma ...
                * randn(sampleCount, codeLength - trueK);
            channelEnergy = mean([activeObservations, inactiveObservations].^2, 1);
            estimatedK = sum(channelEnergy > decisionThreshold);
            successes = successes + double(estimatedK == trueK);
        end

        accuracy = successes / monteCarloTimes;
        completedRuns = completedRuns + 1;
        rowCodeLength(completedRuns) = codeLength;
        rowErrorRate(completedRuns) = errorRate;
        rowAccuracy(completedRuns) = accuracy;
        fprintf(csvId, '%d,%.12g,%.12g\n', codeLength, errorRate, accuracy);

        progress = struct('progress', floor(100 * completedRuns / totalRuns), ...
            'completedRuns', completedRuns, 'totalRuns', totalRuns, ...
            'message', sprintf('Fixture completed result point %d/%d', ...
            completedRuns, totalRuns));
        write_json(fullfile(outputDirectory, 'matlab-progress.json'), progress);
        fprintf('WAVEPILOT_PROGRESS=%d/%d\n', completedRuns, totalRuns);
    end
end

fclose(csvId);
clear csvCleanup;

averageAccuracy = mean(rowAccuracy);
summary = struct('mock', false, 'runner', 'local-matlab', ...
    'runnerType', 'local-matlab', ...
    'templateVersion', 'polar-k-integration-fixture-v1', ...
    'algorithm', 'polar-active-channel-energy-baseline-v1', ...
    'algorithmName', 'polar-active-channel-energy-baseline-v1', ...
    'algorithmVersion', 'fixture-1.0.0', ...
    'algorithmValidated', false, 'classification', 'INTEGRATION_FIXTURE', ...
    'simulationBoundary', ['Real MATLAB integration fixture; no polar encoding; ', ...
    'not a research result'], 'matlabVersion', version, 'rowCount', totalRuns, ...
    'averageAccuracy', averageAccuracy, 'minAccuracy', min(rowAccuracy), ...
    'maxAccuracy', max(rowAccuracy), 'randomSeed', randomSeed);
write_json(fullfile(outputDirectory, 'summary.json'), summary);

save(fullfile(outputDirectory, 'result.mat'), 'spec', 'codeLengths', ...
    'errorRates', 'rowCodeLength', 'rowErrorRate', 'rowAccuracy', 'summary', '-v7');

figureHandle = figure('Visible', 'off', 'Color', 'white', ...
    'Position', [100, 100, 900, 600]);
figureCleanup = onCleanup(@() close(figureHandle));
hold on;
for lengthIndex = 1:numel(codeLengths)
    selected = rowCodeLength == codeLengths(lengthIndex);
    plot(rowErrorRate(selected), rowAccuracy(selected), '-o', ...
        'LineWidth', 1.5, 'DisplayName', sprintf('N=%d', codeLengths(lengthIndex)));
end
xlabel('Fixture error parameter');
ylabel('Fixture K identification accuracy');
title('WavePilot MATLAB integration fixture');
if min(errorRates) < max(errorRates)
    xlim([min(errorRates), max(errorRates)]);
else
    xPadding = max(0.01, abs(errorRates(1)) * 0.05);
    xlim([errorRates(1) - xPadding, errorRates(1) + xPadding]);
end
ylim([0, 1.05]);
grid on;
legend('Location', 'best');
set(gca, 'FontSize', 11);
print(figureHandle, fullfile(outputDirectory, 'accuracy-curve.png'), '-dpng', '-r150');
clear figureCleanup;

fprintf('WAVEPILOT_RESULT rows=%d averageAccuracy=%.12g mock=false algorithmValidated=false\n', ...
    totalRuns, averageAccuracy);
end

function write_json(path, value)
fileId = fopen(path, 'w');
if fileId < 0
    error('WavePilot:JsonOpenFailed', 'Could not create JSON output.');
end
cleanup = onCleanup(@() close_file(fileId));
fwrite(fileId, jsonencode(value), 'char');
fclose(fileId);
clear cleanup;
end

function close_file(fileId)
try
    fclose(fileId);
catch
    % File is already closed.
end
end
