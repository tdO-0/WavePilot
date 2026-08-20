function kHat = estimate_k_binomial(Y, G, reliabilityOrder, columnWeights, epsilon)
%ESTIMATE_K_BINOMIAL Maximum-likelihood K estimate from column zero counts.

M = size(Y, 1);
N = size(Y, 2);

Uhat = mod(Y * G, 2);
zeroCount = sum(Uhat == 0, 1);

effectiveFlip = (1 - (1 - 2 * epsilon) .^ columnWeights) / 2;
pZeroFrozen = 1 - effectiveFlip;
pZeroFrozen = min(max(pZeroFrozen, 1.0e-12), 1 - 1.0e-12);
pZeroInfo = 0.5;

logFrozen = zeroCount .* log(pZeroFrozen) + ...
    (M - zeroCount) .* log(1 - pZeroFrozen);
logInfo = zeroCount .* log(pZeroInfo) + ...
    (M - zeroCount) .* log(1 - pZeroInfo);

delta = logInfo - logFrozen;
cumulativeScore = sum(logFrozen) + cumsum(delta(reliabilityOrder));

% K=0 and K=N are excluded for this first baseline.
[~, kHat] = max(cumulativeScore(1:N-1));
end
