package dev.rusteq.audio

import kotlin.random.Random

/** One-sided binomial test: probability of at least [correct] hits in [trials] by pure guessing. */
object AbxStats {
    fun pValue(correct: Int, trials: Int): Double {
        require(trials >= 0 && correct in 0..trials)
        if (correct == 0) return 1.0
        var term = 1.0 // C(n,0) accumulated in doubles; fine for the small n used here
        var sum = 0.0
        for (k in 0..trials) {
            if (k > 0) term = term * (trials - k + 1) / k
            if (k >= correct) sum += term
        }
        return sum / Math.pow(2.0, trials.toDouble())
    }
}

/**
 * ABX state machine: each trial hides X = A or B at random; the listener guesses which.
 * The hidden assignment is exposed only so the player can apply it; the UI must not display it.
 */
class AbxSession(private val random: Random = Random.Default, val totalTrials: Int = 10) {
    init { require(totalTrials in 1..30) }
    var done = 0; private set
    var correct = 0; private set
    var hiddenIsA: Boolean = random.nextBoolean(); private set
    val finished get() = done >= totalTrials
    val pValue get() = AbxStats.pValue(correct, done)

    /** Records the guess for the current trial and starts the next. Returns whether it was right. */
    fun answer(guessA: Boolean): Boolean {
        check(!finished) { "ABX session already finished" }
        val right = guessA == hiddenIsA
        if (right) correct++
        done++
        if (!finished) hiddenIsA = random.nextBoolean()
        return right
    }
}
