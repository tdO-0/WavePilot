function spec = load_and_validate_spec(inputFile)
%LOAD_AND_VALIDATE_SPEC Read the Java-validated ExperimentSpec and normalize it.

if ~isfile(inputFile)
    error('WavePilot:MissingInput', 'ExperimentSpec JSON does not exist.');
end
spec = jsondecode(fileread(inputFile));
required = {'experimentType', 'codeLengths', 'errorRateStart', 'errorRateEnd', ...
    'errorRateStep', 'sampleCount', 'monteCarloTimes', 'randomSeed'};
for index = 1:numel(required)
    if ~isfield(spec, required{index})
        error('WavePilot:MissingField', 'Missing required field: %s', required{index});
    end
end

if ~strcmp(char(spec.experimentType), 'POLAR_CODE_K_IDENTIFICATION')
    error('WavePilot:InvalidExperimentType', ...
        'Only POLAR_CODE_K_IDENTIFICATION is supported.');
end

spec.codeLengths = double(spec.codeLengths(:).');
supportedN = [32, 64, 128, 256, 512];
if isempty(spec.codeLengths) || any(~ismember(spec.codeLengths, supportedN))
    error('WavePilot:InvalidCodeLength', ...
        'Supported code lengths are 32, 64, 128, 256 and 512.');
end
if numel(unique(spec.codeLengths)) ~= numel(spec.codeLengths)
    error('WavePilot:DuplicateCodeLength', 'codeLengths must not contain duplicates.');
end

spec.sampleCount = double(spec.sampleCount);
spec.monteCarloTimes = double(spec.monteCarloTimes);
spec.randomSeed = double(spec.randomSeed);
if spec.sampleCount <= 0 || spec.monteCarloTimes <= 0 ...
        || spec.sampleCount ~= floor(spec.sampleCount) ...
        || spec.monteCarloTimes ~= floor(spec.monteCarloTimes)
    error('WavePilot:InvalidCount', ...
        'sampleCount and monteCarloTimes must be positive integers.');
end
if spec.randomSeed < 0 || spec.randomSeed > 2^32 - 1 ...
        || spec.randomSeed ~= floor(spec.randomSeed)
    error('WavePilot:InvalidSeed', ...
        'randomSeed must be an integer from 0 through 2^32-1.');
end

startValue = double(spec.errorRateStart);
endValue = double(spec.errorRateEnd);
stepValue = double(spec.errorRateStep);
if startValue < 0 || endValue > 0.5 || startValue >= endValue || stepValue <= 0
    error('WavePilot:InvalidErrorRate', ...
        'errorRate is the BSC bit-flip probability and must satisfy 0 <= start < end <= 0.5.');
end
pointCount = floor((endValue - startValue) / stepValue + 1.0e-10) + 1;
spec.errorRates = startValue + (0:(pointCount - 1)) * stepValue;
end
