package com.assist.service

/** An installed, launchable app: its [packageName] and user-visible [label]. */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Resolves a free-text `open_app` argument (a package name or a human label) to a
 * concrete package. Pure/testable — the framework `PackageManager` lookup lives in
 * [DeviceController].
 *
 * Precedence: exact package id > exact label (case-insensitive) > label starts-with
 * > label contains > package contains. Ties break on shortest label (most specific).
 */
class AppResolver {
    fun resolve(
        query: String,
        apps: List<InstalledApp>,
    ): String? {
        val q = query.trim()
        if (q.isEmpty() || apps.isEmpty()) return null
        val ql = q.lowercase()

        apps
            .firstOrNull {
                it.packageName.equals(
                    q,
                    ignoreCase = true,
                )
            }?.let { return it.packageName }

        val exactLabel = apps.filter { it.label.equals(q, ignoreCase = true) }
        if (exactLabel.isNotEmpty()) return exactLabel.minBy { it.label.length }.packageName

        val startsWith = apps.filter { it.label.lowercase().startsWith(ql) }
        if (startsWith.isNotEmpty()) return startsWith.minBy { it.label.length }.packageName

        val contains = apps.filter { it.label.lowercase().contains(ql) }
        if (contains.isNotEmpty()) return contains.minBy { it.label.length }.packageName

        val pkgContains = apps.filter { it.packageName.lowercase().contains(ql) }
        if (pkgContains.isNotEmpty()) return pkgContains.minBy { it.packageName.length }.packageName

        return null
    }
}
