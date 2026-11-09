package com.baonplan.finance.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.ui.theme.LocalBaonColors

enum class BadgeTone {
  NEUTRAL,
  POSITIVE,
  WARNING,
  DANGER,
  PRIMARY
}

@Composable
fun BaonCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  containerColor: Color = MaterialTheme.colorScheme.surface,
  contentPadding: Dp = 18.dp,
  content: @Composable ColumnScope.() -> Unit
) {
  val semantic = LocalBaonColors.current
  val shape = RoundedCornerShape(16.dp)
  val border = BorderStroke(1.dp, semantic.divider)
  val colors = CardDefaults.cardColors(containerColor = containerColor)
  val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

  if (onClick != null) {
    Card(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = shape,
      colors = colors,
      border = border,
      elevation = elevation
    ) {
      Column(
        modifier = Modifier.padding(contentPadding),
        content = content
      )
    }
  } else {
    Card(
      modifier = modifier.fillMaxWidth(),
      shape = shape,
      colors = colors,
      border = border,
      elevation = elevation
    ) {
      Column(
        modifier = Modifier.padding(contentPadding),
        content = content
      )
    }
  }
}

@Composable
fun CalmDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(
    modifier = modifier,
    thickness = 1.dp,
    color = LocalBaonColors.current.divider
  )
}

@Composable
fun CalmSectionLabel(
  text: String,
  modifier: Modifier = Modifier
) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    color = LocalBaonColors.current.muted,
    modifier = modifier.padding(horizontal = 4.dp)
  )
}

@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    if (actionLabel != null && onAction != null) {
      TextButton(
        onClick = onAction,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
      ) {
        Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

@Composable
fun BaonListRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  trailingValue: String? = null,
  trailingValueColor: Color = MaterialTheme.colorScheme.onSurface,
  icon: ImageVector? = null,
  showChevron: Boolean = true
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.width(14.dp))
    }
    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    if (trailingValue != null) {
      Text(
        text = trailingValue,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = trailingValueColor,
        maxLines = 1
      )
    }
    if (showChevron) {
      if (trailingValue != null) Spacer(modifier = Modifier.width(6.dp))
      Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

@Composable
fun ExpandableSection(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  initiallyExpanded: Boolean = false,
  content: @Composable ColumnScope.() -> Unit
) {
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  BaonCard(modifier = modifier, contentPadding = 0.dp) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(horizontal = 18.dp, vertical = 15.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface
        )
        if (!subtitle.isNullOrBlank()) {
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      Icon(
        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut()
    ) {
      Column {
        CalmDivider()
        Column(
          modifier = Modifier.padding(18.dp),
          content = content
        )
      }
    }
  }
}

@Composable
fun <T> CalmSegmentedToggle(
  options: List<Pair<T, String>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow
  ) {
    Row(
      modifier = Modifier.padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      options.forEach { (value, label) ->
        val isSelected = value == selected
        Box(
          modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(9.dp))
            .background(
              if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
            )
            .clickable { onSelect(value) }
            .padding(vertical = 9.dp, horizontal = 8.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isSelected) {
              MaterialTheme.colorScheme.onSurface
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
          )
        }
      }
    }
  }
}

@Composable
fun StatusBadge(
  text: String,
  tone: BadgeTone,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val (bg, fg) = when (tone) {
    BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
    BadgeTone.POSITIVE -> semantic.positiveContainer to semantic.onPositiveContainer
    BadgeTone.WARNING -> semantic.warningContainer to semantic.onWarningContainer
    BadgeTone.DANGER -> semantic.dangerContainer to semantic.onDangerContainer
    BadgeTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
  }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(6.dp),
    color = bg
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = fg,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )
  }
}

@Composable
fun DueDateStatusBadge(status: DueDateStatus, modifier: Modifier = Modifier) {
  val (label, tone) = when (status) {
    DueDateStatus.CONFIRMED -> "Confirmed" to BadgeTone.POSITIVE
    DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION -> "Estimated" to BadgeTone.WARNING
    DueDateStatus.UNCONFIRMED -> "Unconfirmed" to BadgeTone.WARNING
    DueDateStatus.UNKNOWN -> "Unknown" to BadgeTone.NEUTRAL
  }
  StatusBadge(text = label, tone = tone, modifier = modifier)
}

@Composable
fun UrgencyBadge(urgency: PaymentUrgency, modifier: Modifier = Modifier) {
  val tone = when (urgency) {
    PaymentUrgency.OVERDUE -> BadgeTone.DANGER
    PaymentUrgency.DUE_TODAY -> BadgeTone.DANGER
    PaymentUrgency.DUE_TOMORROW -> BadgeTone.WARNING
    PaymentUrgency.UPCOMING -> BadgeTone.POSITIVE
    PaymentUrgency.UNCONFIRMED -> BadgeTone.WARNING
    PaymentUrgency.UNKNOWN -> BadgeTone.NEUTRAL
  }
  StatusBadge(text = urgency.label, tone = tone, modifier = modifier)
}

@Composable
fun MetricRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
  boldValue: Boolean = true
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(1.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = LocalBaonColors.current.muted
        )
      }
    }
    Text(
      text = value,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = if (boldValue) FontWeight.SemiBold else FontWeight.Normal,
      color = valueColor,
      maxLines = 1
    )
  }
}

@Composable
fun EmptyStateCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  secondaryActionLabel: String? = null,
  onSecondaryAction: (() -> Unit)? = null
) {
  BaonCard(modifier = modifier) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .background(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp)
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp)
        )
      }
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
      if ((actionLabel != null && onAction != null) || (secondaryActionLabel != null && onSecondaryAction != null)) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
              Text(actionLabel)
            }
          }
          if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) {
              Text(secondaryActionLabel)
            }
          }
        }
      }
    }
  }
}

@Composable
fun InfoCallout(
  icon: ImageVector,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  tone: BadgeTone = BadgeTone.NEUTRAL
) {
  val semantic = LocalBaonColors.current
  val (bg, fg) = when (tone) {
    BadgeTone.WARNING -> semantic.warningContainer to semantic.onWarningContainer
    BadgeTone.DANGER -> semantic.dangerContainer to semantic.onDangerContainer
    BadgeTone.POSITIVE -> semantic.positiveContainer to semantic.onPositiveContainer
    BadgeTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = bg
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.Top
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = fg,
        modifier = Modifier.size(18.dp).padding(top = 2.dp)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          color = fg
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = body,
          style = MaterialTheme.typography.bodySmall,
          color = fg.copy(alpha = 0.88f)
        )
      }
    }
  }
}
