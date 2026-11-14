//
//  XmaxCreditBalance.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The server-reported credit balance for the account owning the client's API key.
 *
 * @property currentBalance The remaining credits as a finite, nonnegative value, including fractional credits.
 */
@ConsistentCopyVisibility
public data class XmaxCreditBalance internal constructor(
    public val currentBalance: Double,
)
