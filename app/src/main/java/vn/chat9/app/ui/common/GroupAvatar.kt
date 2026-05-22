package vn.chat9.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import vn.chat9.app.util.UrlUtils

/**
 * Member preview cho mosaic — chỉ giữ 2 field tối thiểu để render avatar
 * (URL + initial cho letter fallback) + màu nền tuỳ chọn.
 */
data class GroupAvatarMember(
    val avatarUrl: String?,
    val initial: String,
    val bgColor: Color = Color(0xFF3E1F91),
)

/**
 * Hiển thị avatar nhóm theo Zalo style:
 *
 *   - Có `avatarUrl` (room.avatar đã set) → render thẳng AsyncImage
 *   - 1-3 thành viên → 3 vòng tròn xếp tam giác, 1 trên-trái, 1 trên-phải, 1 dưới-giữa
 *   - 4 thành viên → 4 vòng tròn 2×2 (đủ 4 ảnh)
 *   - >4 thành viên → 3 ảnh + 1 ô đếm "+N" 2×2
 *
 * Tất cả vòng tròn con đều clip CircleShape, có thể có ảnh hoặc letter
 * fallback nếu avatarUrl của member null. Layout tự fit `size` truyền vào.
 */
@Composable
fun GroupAvatar(
    avatarUrl: String?,
    members: List<GroupAvatarMember>,
    memberCount: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Nếu nhóm đã set avatar tường minh → ưu tiên cái đó
    if (!avatarUrl.isNullOrBlank()) {
        val full = UrlUtils.toFullUrl(avatarUrl)
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center,
        ) {
            if (full != null) {
                AsyncImage(
                    model = full,
                    contentDescription = "Avatar nhóm",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        return
    }

    // Mosaic — phụ thuộc số lượng thành viên
    Box(modifier = modifier.size(size)) {
        when {
            memberCount <= 3 -> Mosaic3(members, size)
            memberCount == 4 -> Mosaic4(members, size, plusN = 0)
            else -> Mosaic4(members, size, plusN = memberCount)
        }
    }
}

/** 3 vòng tròn tam giác — 1 trên-trái, 1 trên-phải, 1 dưới-giữa.
 *  Đường kính sub = 0.51× size (= 0.6 × 0.85, giảm 15% so với mặc định cũ
 *  để 3 vòng tròn không sát nhau quá khi xếp tam giác). */
@Composable
private fun Mosaic3(members: List<GroupAvatarMember>, size: Dp) {
    val sub = size * 0.51f
    Box(modifier = Modifier.size(size)) {
        // Top-left
        SubAvatar(
            member = members.getOrNull(0),
            size = sub,
            modifier = Modifier.align(Alignment.TopStart),
        )
        // Top-right
        SubAvatar(
            member = members.getOrNull(1),
            size = sub,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        // Bottom-center
        SubAvatar(
            member = members.getOrNull(2),
            size = sub,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Grid 2×2 — đủ 4 ảnh nếu plusN = 0, hoặc 3 ảnh + ô "+N" nếu plusN > 0
 * (truyền memberCount → ô thứ 4 hiện số tổng).
 */
@Composable
private fun Mosaic4(members: List<GroupAvatarMember>, size: Dp, plusN: Int) {
    val sub = size * 0.5f - 1.dp
    Column(modifier = Modifier.size(size), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SubAvatar(member = members.getOrNull(0), size = sub)
            SubAvatar(member = members.getOrNull(1), size = sub)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SubAvatar(member = members.getOrNull(2), size = sub)
            if (plusN > 0) {
                Box(
                    modifier = Modifier
                        .size(sub)
                        .clip(CircleShape)
                        .background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = plusN.toString(),
                        color = Color(0xFF555555),
                        fontWeight = FontWeight.Bold,
                        fontSize = (sub.value * 0.4f).coerceIn(11f, 18f).sp,
                    )
                }
            } else {
                SubAvatar(member = members.getOrNull(3), size = sub)
            }
        }
    }
}

@Composable
private fun SubAvatar(
    member: GroupAvatarMember?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(member?.bgColor ?: Color(0xFFBDBDBD))
            .border(width = 1.dp, color = Color.White, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val url = member?.avatarUrl?.let { UrlUtils.toFullUrl(it) }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else if (member != null && member.initial.isNotEmpty()) {
            Text(
                member.initial.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).coerceIn(10f, 22f).sp,
            )
        }
    }
}
