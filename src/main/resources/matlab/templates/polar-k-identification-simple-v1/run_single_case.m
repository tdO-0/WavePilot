function [estimatedK, correctCount] = run_single_case(N, trueK, epsilon, ...
    sampleCount, monteCarloTimes, G, reliabilityOrder, infoSet, columnWeights)
%RUN_SINGLE_CASE Preserve the supplied encoding/channel/K-estimation logic.

estimatedK = zeros(monteCarloTimes, 1);
correctCount = 0;
for mc = 1:monteCarloTimes
    U = zeros(sampleCount, N);
    U(:, infoSet) = randi([0, 1], sampleCount, trueK);
    X = mod(U * G, 2);
    E = rand(sampleCount, N) < epsilon;
    Y = mod(X + E, 2);

    kHat = estimate_k_binomial(Y, G, reliabilityOrder, columnWeights, epsilon);
    estimatedK(mc) = kHat;
    correctCount = correctCount + (kHat == trueK);
end
end
