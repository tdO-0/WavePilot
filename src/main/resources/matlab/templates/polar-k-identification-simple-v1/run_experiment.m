function run_experiment(inputFile, outputDirectory)
%RUN_EXPERIMENT Fixed orchestration entry point for the simple polar K baseline.

arguments
    inputFile (1,:) char
    outputDirectory (1,:) char
end

if ~isfolder(outputDirectory)
    mkdir(outputDirectory);
end

templateRoot = fileparts(mfilename('fullpath'));
addpath(fullfile(templateRoot, 'algorithm'));
logPath = fullfile(outputDirectory, 'run.log');
logFid = fopen(logPath, 'a');
if logFid < 0
    error('WavePilot:LogOpenFailed', 'Cannot open run.log for writing.');
end
logCleanup = onCleanup(@() close_file(logFid));

algorithmName = 'polar-bsc-binomial-k-baseline';
algorithmVersion = '1.0.0';
templateVersion = 'polar-k-identification-simple-v1';
startedAt = datetime('now', 'TimeZone', 'UTC');

try
    spec = load_and_validate_spec(inputFile);
    rng(spec.randomSeed, 'twister');

    fprintf(logFid, 'algorithm=%s\n', algorithmName);
    fprintf(logFid, 'algorithmVersion=%s\n', algorithmVersion);
    fprintf(logFid, 'templateVersion=%s\n', templateVersion);
    fprintf(logFid, 'WAVEPILOT_MATLAB_VERSION=%s\n', version);
    fprintf(logFid, 'algorithmValidated=false\n');
    fprintf(logFid, 'errorRateMeaning=BSC_BIT_FLIP_PROBABILITY\n');
    fprintf(logFid, 'startedAt=%s\n', char(startedAt));
    fprintf(logFid, 'sampleCount=%d, monteCarloTimes=%d, randomSeed=%d\n', ...
        spec.sampleCount, spec.monteCarloTimes, spec.randomSeed);

    totalTimer = tic;
    results = run_parameter_sweep(spec, logFid, outputDirectory);
    totalRuntimeSeconds = toc(totalTimer);
    summary = export_results(spec, results, outputDirectory, startedAt, ...
        totalRuntimeSeconds, algorithmName, algorithmVersion, templateVersion);
    plot_results(results, outputDirectory);

    fprintf(logFid, 'finishedAt=%s\n', summary.finishedAt);
    fprintf(logFid, 'WAVEPILOT_RESULT points=%d meanAccuracy=%.12g mock=false algorithmValidated=false\n', ...
        summary.completedPoints, summary.meanAccuracy);
    fprintf(logFid, 'success=true\n');
catch ME
    fprintf(logFid, 'success=false\n');
    fprintf(logFid, 'identifier=%s\n', ME.identifier);
    fprintf(logFid, 'message=%s\n', strrep(ME.message, newline, ' '));
    rethrow(ME);
end
end

function close_file(fileId)
try
    fclose(fileId);
catch
    % File is already closed.
end
end
