package io.github.cpearl0.blockagent.game;

/** Counts only complete, newly confirmed cooking batches above the initial player holdings. */
final class SmeltingProgress {
    private final int baseline;
    private final int wanted;
    private int previous;

    SmeltingProgress(int baseline, int wanted) {
        if (baseline < 0 || wanted < 1 || wanted > 64) {
            throw new IllegalArgumentException("Invalid smelting baseline/request");
        }
        this.baseline = this.previous = baseline;
        this.wanted = wanted;
    }

    boolean confirm(int holdings, int batchSize) {
        if (batchSize < 1 || holdings != previous + batchSize) {
            throw new IllegalStateException(
                    "Actual matching inventory did not grow by the complete output batch");
        }
        previous = holdings;
        return produced() >= wanted;
    }

    int produced() {
        return previous - baseline;
    }
}
