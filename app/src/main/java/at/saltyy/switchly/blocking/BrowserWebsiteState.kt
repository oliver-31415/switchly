/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.blocking

import at.saltyy.switchly.data.prefs.DomainBlockStore

internal class BrowserWebsiteState {

    private var currentPkg: String? = null
    private var currentDomain: String? = null
    private var currentDomainAt: Long = 0L
    private var candidateDomain: String? = null
    private var candidateSince: Long = 0L
    private var confirmedPkg: String? = null
    private var confirmedDomain: String? = null
    private val addressEditGraceUntilByPkg = HashMap<String, Long>()
    private val pendingDomainByPkg = HashMap<String, String>()
    private val pendingDomainAtByPkg = HashMap<String, Long>()

    fun clearCurrent() {
        currentPkg = null
        currentDomain = null
        currentDomainAt = 0L
        confirmedPkg = null
        confirmedDomain = null
        resetCandidate()
    }

    fun clearCurrentFor(pkg: String) {
        currentPkg = pkg
        currentDomain = null
        currentDomainAt = 0L
        confirmedPkg = null
        confirmedDomain = null
        resetCandidate()
    }

    fun needsDomainProbe(pkg: String): Boolean {
        return currentDomain.isNullOrBlank() || currentPkg != pkg
    }

    fun currentTrackedDomain(pkg: String, now: Long, isFirefox: Boolean): String? {
        if (currentPkg != pkg) {
            return null
        }

        val host = currentDomain ?: return null
        if (isFirefox && now - currentDomainAt > FIREFOX_LAST_DOMAIN_TTL_MS) {
            currentDomain = null
            currentDomainAt = 0L
            return null
        }

        return host
    }

    fun hasRecentDomainSignal(pkg: String, now: Long): Boolean {
        if (currentPkg == pkg && !currentDomain.isNullOrBlank() && now - currentDomainAt <= STICKY_DOMAIN_SIGNAL_TTL_MS) {
            return true
        }
        if (!candidateDomain.isNullOrBlank() && now - candidateSince <= STICKY_DOMAIN_SIGNAL_TTL_MS) {
            return true
        }
        return false
    }

    fun updateCurrentDomain(pkg: String, host: String, now: Long) {
        currentPkg = pkg
        currentDomain = host
        currentDomainAt = now
    }

    fun confirmCurrentDomain(pkg: String, host: String, now: Long) {
        updateCurrentDomain(pkg, host, now)
        confirmedPkg = pkg
        confirmedDomain = host
    }

    fun currentConfirmedDomain(pkg: String): String? {
        if (confirmedPkg != pkg) {
            return null
        }
        return confirmedDomain
    }

    fun hasPendingUnconfirmedDomain(pkg: String): Boolean {
        if (currentPkg != pkg) {
            return false
        }
        val candidate = candidateDomain ?: return false
        return confirmedPkg != pkg || confirmedDomain != candidate
    }

    fun addressEditingRecently(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        return (addressEditGraceUntilByPkg[pkg] ?: 0L) > now
    }

    fun noteAddressEditing(pkg: String, now: Long = System.currentTimeMillis()) {
        addressEditGraceUntilByPkg[pkg] = now + ADDRESS_EDIT_GRACE_MS
    }

    fun notePendingDomain(pkg: String, host: String?, now: Long = System.currentTimeMillis()) {
        val normalized = host?.let { DomainBlockStore.normalize(it) }
        if (normalized.isNullOrBlank()) {
            return
        }

        pendingDomainByPkg[pkg] = normalized
        pendingDomainAtByPkg[pkg] = now
    }

    fun currentPendingDomain(pkg: String, now: Long = System.currentTimeMillis()): String? {
        val host = pendingDomainByPkg[pkg] ?: return null
        val at = pendingDomainAtByPkg[pkg] ?: 0L

        if (now - at > FIREFOX_PENDING_DOMAIN_TTL_MS) {
            pendingDomainByPkg.remove(pkg)
            pendingDomainAtByPkg.remove(pkg)
            return null
        }

        return host
    }

    fun clearPendingDomain(pkg: String) {
        pendingDomainByPkg.remove(pkg)
        pendingDomainAtByPkg.remove(pkg)
    }

    fun resetCandidate() {
        candidateDomain = null
        candidateSince = 0L
    }

    fun noteCandidate(host: String, now: Long): Boolean {
        if (candidateDomain == host) {
            return false
        }

        candidateDomain = host
        candidateSince = now
        return true
    }

    fun candidateAgeMs(now: Long): Long {
        return now - candidateSince
    }

    fun resetAfterRedirect(pkg: String) {
        currentPkg = pkg
        currentDomain = null
        currentDomainAt = 0L
        confirmedPkg = null
        confirmedDomain = null
        resetCandidate()
        clearPendingDomain(pkg)
        noteAddressEditing(pkg)
    }

    companion object {
        const val DOMAIN_CONFIRM_MS = 700L

        private const val ADDRESS_EDIT_GRACE_MS = 1_400L
        private const val FIREFOX_LAST_DOMAIN_TTL_MS = 12_000L
        private const val FIREFOX_PENDING_DOMAIN_TTL_MS = 8_000L
        private const val STICKY_DOMAIN_SIGNAL_TTL_MS = 6_000L
    }
}
