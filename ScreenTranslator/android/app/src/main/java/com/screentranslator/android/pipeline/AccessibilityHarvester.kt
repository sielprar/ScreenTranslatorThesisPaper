package com.screentranslator.android.pipeline

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

data class A11yTextNode(
    val text: String,
    val bounds: Rect,
    val isVirtualLine: Boolean = false,
    val fullWidth: Boolean = false,
    val bold: Boolean = false,
    val isLauncherLabel: Boolean = false,
)

object AccessibilityHarvester {
    private const val TAG = "ScreenTranslatorPipe"
    private const val OWN_PACKAGE = "com.screentranslator.android"
    private const val MAX_VISITED = 500
    private const val MAX_DEPTH = 30

    private const val MAX_BOUNDS_FRACTION = 0.30f

    fun harvest(root: AccessibilityNodeInfo?): List<A11yTextNode> {
        if (root == null) return emptyList()
        val display = Resources.getSystem().displayMetrics
        val screenArea = display.widthPixels.toLong() * display.heightPixels.toLong()
        val maxNodeArea = (screenArea * MAX_BOUNDS_FRACTION).toLong()

        val out = mutableListOf<A11yTextNode>()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, 0))
        var visited = 0
        var oversizeDropped = 0

        while (stack.isNotEmpty() && visited < MAX_VISITED) {
            val frame = stack.removeLast()
            val node = frame.node
            visited++

            if (frame.depth > MAX_DEPTH) continue
            if (node.packageName?.toString() == OWN_PACKAGE) continue

            var collectedRealText = false
            if (node.isVisibleToUser) {
                val realText = node.text?.toString()?.trim().orEmpty()
                val packageName = node.packageName?.toString()
                val resourceId = node.viewIdResourceName.orEmpty()
                val isLauncherLabel = packageName == LAUNCHER_PACKAGE &&
                    resourceId == "$LAUNCHER_PACKAGE:id/icon"
                val description = if (realText.isEmpty() && packageName == "com.android.vending") {
                    node.contentDescription?.toString()?.trim().orEmpty()
                } else ""
                val descriptionLines = description.lines().map(String::trim)
                    .filter(String::isNotEmpty)
                val text = when {
                    packageName == LAUNCHER_PACKAGE &&
                        resourceId == "$LAUNCHER_PACKAGE:id/input" -> ""
                    realText.isNotEmpty() -> realText
                    descriptionLines.size >= 3 -> description
                    else -> ""
                }
                if (text.length >= 2) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        if (isLauncherLabel) {
                            val height = bounds.height()
                            bounds.top += height * 70 / 100
                            bounds.bottom = bounds.top + height * 23 / 100
                        }
                        val area = bounds.width().toLong() * bounds.height().toLong()
                        if (area <= maxNodeArea) {
                            val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
                            if (packageName == "com.android.vending" &&
                                realText.isEmpty() && lines.size >= 3 &&
                                bounds.height() >= MIN_LINE_HEIGHT_PX * 3) {
                                val ratingIndex = lines.indexOfFirst {
                                    it.startsWith("Средняя оценка:") ||
                                        it.startsWith("Average rating:", ignoreCase = true)
                                }.takeIf { it >= 0 } ?: lines.size
                                val titleBottom = bounds.top + bounds.height() * 40 / 100
                                val categoryBottom = bounds.top + bounds.height() * 73 / 100
                                out.add(A11yTextNode(
                                    lines.first(),
                                    Rect(bounds.left, bounds.top, bounds.right, titleBottom),
                                    isVirtualLine = true,
                                    fullWidth = true,
                                    bold = true,
                                ))
                                val category = lines.subList(1, ratingIndex.coerceAtLeast(1))
                                    .joinToString(" • ")
                                if (category.isNotBlank()) {
                                    out.add(A11yTextNode(
                                        category,
                                        Rect(bounds.left, titleBottom, bounds.right, categoryBottom),
                                        isVirtualLine = true,
                                        fullWidth = true,
                                    ))
                                }
                            } else if (lines.size > 1 && bounds.height() / lines.size >= MIN_LINE_HEIGHT_PX) {
                                lines.forEachIndexed { index, line ->
                                    if (line.startsWith("Средняя оценка:") ||
                                        line.startsWith("Average rating:", ignoreCase = true)) {
                                        return@forEachIndexed
                                    }
                                    val lineTop = bounds.top + bounds.height() * index / lines.size
                                    val lineBottom = bounds.top + bounds.height() * (index + 1) / lines.size
                                    out.add(A11yTextNode(
                                        line,
                                        Rect(bounds.left, lineTop, bounds.right, lineBottom),
                                        isVirtualLine = true,
                                    ))
                                }
                            } else if (lines.size <= 1) {
                                out.add(A11yTextNode(text, bounds, isLauncherLabel = isLauncherLabel))
                            }
                            collectedRealText = realText.isNotEmpty()
                        } else {
                            oversizeDropped++
                        }
                    }
                }
            }
            if (!collectedRealText) {
                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (t: Throwable) {
                        null
                    } ?: continue
                    stack.addLast(Frame(child, frame.depth + 1))
                }
            }
        }
        if (visited >= MAX_VISITED) {
            Log.i(TAG, "a11y walk capped at $MAX_VISITED nodes; ${out.size} text nodes collected")
        }
        if (oversizeDropped > 0) {
            Log.i(TAG, "a11y walk dropped $oversizeDropped oversize container node(s)")
        }
        return out
    }

    private data class Frame(val node: AccessibilityNodeInfo, val depth: Int)

    private const val MIN_LINE_HEIGHT_PX = 14
    private const val LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
}
