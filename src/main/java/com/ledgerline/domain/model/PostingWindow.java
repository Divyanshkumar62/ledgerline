package com.ledgerline.domain.model;

/**
 * Postings of one account after a checkpoint, as seen in one consistent snapshot.
 *
 * @param deltaSinceCheckpoint signed sum of ALL visible postings after the checkpoint
 * @param horizonSeq           highest posting seq old enough to checkpoint, or null if none
 * @param deltaToHorizon       signed sum of the postings after the checkpoint up to {@code horizonSeq}
 */
public record PostingWindow(long deltaSinceCheckpoint, Long horizonSeq, long deltaToHorizon) {
}
