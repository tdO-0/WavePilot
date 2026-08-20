function plot_results(results, outputDirectory)
%PLOT_RESULTS Plot the same Accuracy matrix exported to CSV/MAT.

figureHandle = figure('Visible', 'off', 'Color', 'white', ...
    'Position', [100, 100, 900, 600]);
figureCleanup = onCleanup(@() close(figureHandle));
hold on;
for nIdx = 1:numel(results.NVec)
    plot(results.errorVec, results.accuracyMatrix(nIdx, :), '-o', ...
        'LineWidth', 1.5, 'DisplayName', sprintf('N=%d', results.NVec(nIdx)));
end
xlabel('BSC bit-flip probability');
ylabel('K identification accuracy');
title('Simple Polar-Code Dimension Identification Baseline');
legend('Location', 'best');
grid on;
ylim([0, 1.05]);
if min(results.errorVec) < max(results.errorVec)
    xlim([min(results.errorVec), max(results.errorVec)]);
else
    padding = max(0.01, abs(results.errorVec(1)) * 0.05);
    xlim([results.errorVec(1) - padding, results.errorVec(1) + padding]);
end
set(gca, 'FontSize', 11);
print(figureHandle, fullfile(outputDirectory, 'accuracy-curve.png'), '-dpng', '-r150');
end
