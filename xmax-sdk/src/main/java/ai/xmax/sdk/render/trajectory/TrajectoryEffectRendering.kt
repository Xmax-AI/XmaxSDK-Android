//
//  TrajectoryEffectRendering.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.graphics.PointF
import android.view.View
import java.util.UUID

/**
 * A stable identifier for an active trajectory.
 */
public class TrajectoryID internal constructor(
    /**
     * The underlying value represented by this object.
     */
    internal val rawValue: UUID = UUID.randomUUID(),
) {

    /**
     * Compares objects by value.
     *
     * @param other The object to compare or combine.
     */
    override fun equals(
        other: Any?,
    ): Boolean = other is TrajectoryID && rawValue == other.rawValue

    /**
     * Computes a hash consistent with value equality.
     */
    override fun hashCode(): Int = rawValue.hashCode()
}

/**
 * A touch sample delivered to a trajectory renderer.
 */
public class TrajectoryPoint internal constructor(
    /**
     * The stable identifier for one continuous drag.
     */
    public val id: TrajectoryID,

    /**
     * The touch position in view pixels.
     */
    public val location: PointF,

    /**
     * The normalized touch position within the complete resolved media area.
     */
    public val normalizedLocation: PointF,

    /**
     * The monotonic touch timestamp, in seconds.
     */
    public val timestamp: Double,
)

/**
 * An interface for custom trajectory effects.
 */
public interface TrajectoryEffectRendering {

    /**
     * The trajectory effect view hosted by the container.
     */
    public val view: View

    /**
     * Begins trajectories for new touches.
     *
     * @param points Trajectory points with positions and timestamps.
     */
    public fun renderBegan(
        points: List<TrajectoryPoint>,
    )

    /**
     * Updates active trajectory positions.
     *
     * @param points Trajectory points with positions and timestamps.
     */
    public fun renderMoved(
        points: List<TrajectoryPoint>,
    )

    /**
     * Ends active trajectories.
     *
     * @param identifiers The trajectory identifiers to end.
     */
    public fun renderEnded(
        identifiers: List<TrajectoryID>,
    )

    /**
     * Clears all trajectories and animation resources.
     */
    public fun reset()
}
