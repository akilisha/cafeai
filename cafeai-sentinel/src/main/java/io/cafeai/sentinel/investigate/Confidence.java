package io.cafeai.sentinel.investigate;

/** How firmly the evidence supports an {@link Investigation}'s conclusion. */
public enum Confidence {

    /** A guess — evidence was thin or contradictory. */
    LOW,

    /** Plausible, consistent with the evidence, but not proven. */
    MEDIUM,

    /** The evidence points clearly at one cause. */
    HIGH
}
