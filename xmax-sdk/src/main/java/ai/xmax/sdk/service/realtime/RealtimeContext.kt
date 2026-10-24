//
//  RealtimeContext.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The prompt and reference resources for one generation request.
 *
 * @param prompt The prompt describing the desired generation.
 * @param referencePath An accessible reference image URL, or null without a reference.
 */
public class RealtimeContext(
    prompt: String,
    referencePath: String? = null,

    /**
     * The server-read reference video and sampling configuration, or null without video input.
     */
    public val referenceVideo: RealtimeReferenceVideo? = null,
) {

    /**
     * The generation prompt.
     */
    public val prompt: String = prompt.trim()

    /**
     * The trimmed reference image URL; blank strings are treated as unset.
     */
    public val referencePath: String? = referencePath
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    /**
     * Returns the prompt for destructuring.
     *
     * @return The current prompt.
     */
    public operator fun component1(): String = prompt

    /**
     * Returns the reference image URL for destructuring.
     *
     * @return The current reference image URL, or null if unset.
     */
    public operator fun component2(): String? = referencePath

    /**
     * Returns the reference video for destructuring.
     *
     * @return The configured reference video, or null if unset.
     */
    public operator fun component3(): RealtimeReferenceVideo? = referenceVideo

    /**
     * Copies the context with supplied conditions, retaining unspecified fields.
     *
     * @param prompt The generation prompt; defaults to `this.prompt`.
     * @param referencePath An accessible reference image URL, or null without a reference.
     * @param referenceVideo The server-read video and sampling configuration, or null without video input.
     * @return A new context with the requested prompt and reference media.
     */
    public fun copy(
        prompt: String = this.prompt,
        referencePath: String? = this.referencePath,
        referenceVideo: RealtimeReferenceVideo? = this.referenceVideo,
    ): RealtimeContext = RealtimeContext(
        prompt,
        referencePath,
        referenceVideo,
    )

    /**
     * Compares objects by value.
     *
     * @param other The object to compare or combine.
     */
    override fun equals(
        other: Any?,
    ): Boolean = other is RealtimeContext &&
        prompt == other.prompt &&
        referencePath == other.referencePath && referenceVideo == other.referenceVideo

    /**
     * Computes a hash consistent with value equality.
     */
    override fun hashCode(): Int = 31 * (31 * prompt.hashCode() +
        (referencePath?.hashCode() ?: 0)) + (referenceVideo?.hashCode() ?: 0)

    /**
     * Describes the current prompt, image reference, and video reference.
     */
    override fun toString(): String =
        "RealtimeContext(prompt=$prompt, referencePath=$referencePath, referenceVideo=$referenceVideo)"
}
