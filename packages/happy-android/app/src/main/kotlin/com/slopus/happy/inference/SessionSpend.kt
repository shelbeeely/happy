package com.slopus.happy.inference

import androidx.compose.runtime.Immutable
import com.slopus.happy.sync.SessionUsage

/** How close a session is to its limits. */
enum class BudgetLevel { None, Warning, Exceeded }

/**
 * A session's spend as the chat shows it. For an OpenRouter session it is
 * the computer's running total from its acknowledgement, labelled with its
 * source. Otherwise it is the last reply's usage report, whose cost Happy
 * CLI estimates from Anthropic list prices, so it is always "estimated".
 */
@Immutable
data class SessionSpend(
    val totalTokens: Long?,
    val costUsd: Double?,
    val source: CostSource,
    /** True when the figures cover the whole session; false for the last reply only. */
    val cumulative: Boolean,
    val webSearches: Long = 0,
    val budgetUsd: Double? = null,
    val warnAtUsd: Double? = null,
    val level: BudgetLevel = BudgetLevel.None,
) {
    companion object {
        fun of(ack: InferenceAck?, lastUsage: SessionUsage?, warnAtUsd: Double?): SessionSpend? {
            if (ack != null) {
                val usage = ack.usage
                val spent = usage?.costUsd ?: ack.spentUsd
                return SessionSpend(
                    totalTokens = usage?.totalTokens,
                    costUsd = spent.takeIf { usage?.costSource != CostSource.Unavailable },
                    source = usage?.costSource ?: if (ack.reportedCost) CostSource.Reported else CostSource.Unavailable,
                    cumulative = true,
                    webSearches = usage?.webSearchRequests ?: 0,
                    budgetUsd = ack.budgetUsd,
                    warnAtUsd = warnAtUsd,
                    level = level(spent, warnAtUsd, ack.budgetUsd, ack.budgetExceeded),
                )
            }
            val last = lastUsage ?: return null
            return SessionSpend(
                totalTokens = last.tokens.total.toLong(),
                costUsd = last.cost.total.takeIf { it > 0 },
                source = if (last.cost.total > 0) CostSource.Estimated else CostSource.Unavailable,
                cumulative = false,
            )
        }

        fun level(spent: Double?, warnAt: Double?, budget: Double?, exceeded: Boolean): BudgetLevel = when {
            exceeded || (spent != null && budget != null && spent >= budget) -> BudgetLevel.Exceeded
            spent != null && warnAt != null && spent >= warnAt -> BudgetLevel.Warning
            spent != null && budget != null && spent >= budget * 0.8 -> BudgetLevel.Warning
            else -> BudgetLevel.None
        }
    }
}
