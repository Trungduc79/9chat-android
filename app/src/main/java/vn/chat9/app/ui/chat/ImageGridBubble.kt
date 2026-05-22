package vn.chat9.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import vn.chat9.app.data.model.MessageImage
import vn.chat9.app.util.UrlUtils

/**
 * Multi-image album layout — full algorithm xem CLAUDE-IMAGE-LAYOUT-ALGORITHM.md.
 *
 * Khác layout cũ (equal-width 1:1 cells):
 *   - Per-cell width theo ratio gốc (Modifier.weight(ratio_i / sumR))
 *     → ô landscape rộng hơn ô portrait trong cùng row.
 *   - Per-row height = availW / sumRatios, clamp [80, 220dp] (260 nếu
 *     row portrait dominant) → row portrait cao hơn để ko crop quá nhiều.
 *   - 3 ảnh portrait/mixed → MOSAIC: ảnh đầu chiếm 2/3, 2 ảnh sau stack 1/3.
 *   - 7→3+4, 8→4+4, 9→3+3+3 (case riêng thay vì chunked(3) đều).
 *   - 10+ row cuối lẻ → left-align, cell fixed 1/3 bubble width.
 *   - Default ratio = 1.0 nếu ảnh chưa có w/h (message cũ trước migration).
 */

private const val IL_MIN_RATIO = 0.3f
private const val IL_MAX_RATIO = 3.0f
private val IL_GAP = 3.dp
private val IL_MIN_ROW_H = 80.dp
private val IL_MAX_ROW_H = 220.dp
private val IL_MAX_ROW_H_PORTRAIT = 260.dp
private val IL_MAX_BIG_H = 320.dp

private fun ratioOf(im: MessageImage): Float {
    val w = im.w ?: 0
    val h = im.h ?: 0
    if (w <= 0 || h <= 0) return 1f
    return (w.toFloat() / h.toFloat()).coerceIn(IL_MIN_RATIO, IL_MAX_RATIO)
}

private enum class Group { LANDSCAPE, PORTRAIT, MIXED }

private fun groupOf(images: List<MessageImage>): Group {
    val avg = images.map { ratioOf(it) }.average().toFloat()
    return when {
        avg > 1.4f -> Group.LANDSCAPE
        avg < 0.7f -> Group.PORTRAIT
        else       -> Group.MIXED
    }
}

@Composable
fun ImageGridBubble(
    images: List<MessageImage>,
    onImageClick: (Int) -> Unit,
) {
    val n = images.size
    if (n <= 1) return

    val configuration = LocalConfiguration.current
    val bubbleWidth: Dp = (configuration.screenWidthDp * 0.80f).toInt().dp
        .coerceAtMost(420.dp)

    val group = groupOf(images)
    val maxRowH = if (group == Group.PORTRAIT) IL_MAX_ROW_H_PORTRAIT else IL_MAX_ROW_H

    // Mosaic 3 — portrait/mixed: ảnh đầu lớn 2/3 + 2 ảnh nhỏ stack 1/3
    if (n == 3 && group != Group.LANDSCAPE) {
        Mosaic3(images, bubbleWidth, onImageClick)
        return
    }

    // Phân chia rows theo count + group (LANDSCAPE 3 vẫn rơi vào nhánh standard)
    val rowDefs: List<List<Int>> = when (n) {
        2 -> listOf(listOf(0, 1))
        3 -> listOf(listOf(0, 1, 2))
        4 -> listOf(listOf(0, 1), listOf(2, 3))
        5 -> listOf(listOf(0, 1), listOf(2, 3, 4))
        6 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5))
        7 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6))
        8 -> listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7))
        9 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8))
        else -> {
            val rs = mutableListOf<List<Int>>()
            var i = 0
            while (i < n) {
                val cols = minOf(3, n - i)
                rs.add((i until i + cols).toList())
                i += cols
            }
            rs
        }
    }

    Column(
        modifier = Modifier
            .width(bubbleWidth)
            .clip(RoundedCornerShape(8.dp)),
        verticalArrangement = Arrangement.spacedBy(IL_GAP),
    ) {
        rowDefs.forEachIndexed { rowIdx, indices ->
            val ncol = indices.size
            val ratios = indices.map { ratioOf(images[it]) }
            val sumR = ratios.sum()
            val isLastPartial = (n >= 10 && rowIdx == rowDefs.lastIndex && ncol < 3)

            if (isLastPartial) {
                // 10+ row cuối: cell fixed (bubble - 2*gap) / 3, height fit
                // theo ảnh portrait nhất (minRatio) trong row.
                val cellW = (bubbleWidth - IL_GAP * 2) / 3
                val minRatio = ratios.min()
                val idealH = cellW / minRatio
                val rowH = idealH.coerceIn(IL_MIN_ROW_H, maxRowH)
                Row(horizontalArrangement = Arrangement.spacedBy(IL_GAP)) {
                    indices.forEach { imgIdx ->
                        ImageCell(
                            img = images[imgIdx],
                            modifier = Modifier.size(width = cellW, height = rowH),
                            onClick = { onImageClick(imgIdx) },
                        )
                    }
                    // KHÔNG fill spacer — left-align tự nhiên
                }
            } else {
                // Standard row: height = availW / sumR clamp; cell weight = ratio_i
                val availW = bubbleWidth - IL_GAP * (ncol - 1)
                val idealH = availW / sumR
                val rowH = idealH.coerceIn(IL_MIN_ROW_H, maxRowH)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowH),
                    horizontalArrangement = Arrangement.spacedBy(IL_GAP),
                ) {
                    indices.forEachIndexed { col, imgIdx ->
                        ImageCell(
                            img = images[imgIdx],
                            modifier = Modifier
                                .weight(ratios[col])
                                .fillMaxHeight(),
                            onClick = { onImageClick(imgIdx) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mosaic 3 ảnh — chỉ dùng cho portrait/mixed group (3 ảnh landscape sẽ
 * vào nhánh standard 3-cột).
 *
 * Big: ảnh[0] chiếm 2/3 width. Height = (containerW * 2/3) / bigRatio,
 * clamp [120, 320]. Right column = 1/3 width, 2 boxes stack với gap.
 */
@Composable
private fun Mosaic3(
    images: List<MessageImage>,
    bubbleWidth: Dp,
    onImageClick: (Int) -> Unit,
) {
    val bigRatio = ratioOf(images[0])
    val bigW = (bubbleWidth - IL_GAP) * (2f / 3f)
    val idealH = bigW / bigRatio
    val rowH = idealH.coerceIn(120.dp, IL_MAX_BIG_H)

    Row(
        modifier = Modifier
            .width(bubbleWidth)
            .height(rowH)
            .clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(IL_GAP),
    ) {
        ImageCell(
            img = images[0],
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight(),
            onClick = { onImageClick(0) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(IL_GAP),
        ) {
            ImageCell(
                img = images[1],
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = { onImageClick(1) },
            )
            ImageCell(
                img = images[2],
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = { onImageClick(2) },
            )
        }
    }
}

@Composable
private fun ImageCell(
    img: MessageImage,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFECECEC))
            .clickable(onClick = onClick),
    ) {
        val full = UrlUtils.toFullUrl(img.url)
        if (full != null) {
            AsyncImage(
                model = full,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
