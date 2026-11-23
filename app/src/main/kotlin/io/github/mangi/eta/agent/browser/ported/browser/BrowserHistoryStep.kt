// SPDX-License-Identifier: GPL-3.0-only
// Part of the OpenMinis browser port package (agent/browser/ported); provenance and
// adaptation scope are tracked in third_party/openminis-browser/PORT_STATUS.md.
package io.github.mangi.eta.agent.browser.ported.browser

/** Index arithmetic only; never synthesize a page load or discard forward history. */
internal object BrowserHistoryStep {
    fun targetIndex(current: Int, size: Int, delta: Int): Int? {
        require(delta == -1 || delta == 1)
        if (current !in 0 until size) return null
        return (current + delta).takeIf { it in 0 until size }
    }
}
