function order = bec_reliability_order(N, designErasure)
%BEC_RELIABILITY_ORDER Rank polarized bit channels by BEC Bhattacharyya value.

z = designErasure;
for index = 1:log2(N)
    next = zeros(1, 2 * numel(z));
    next(1:2:end) = 2 .* z - z .^ 2;
    next(2:2:end) = z .^ 2;
    z = next;
end
[~, order] = sort(z, 'ascend');
end
