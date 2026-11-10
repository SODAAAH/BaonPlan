package com.baonplan.finance.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.ocr.ImageScanPreprocessor
import com.baonplan.finance.data.ocr.PdfDocumentProcessor
import com.baonplan.finance.domain.ocr.DebtImportDraft
import com.baonplan.finance.domain.ocr.ImportSeverity
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanViewModel
import com.baonplan.finance.ui.ScanDebtUiState
import com.baonplan.finance.ui.StagedScreenshotUiItem
import com.baonplan.finance.ui.theme.LocalBaonColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanDebtSheet(
  scanState: ScanDebtUiState,
  onSuppressNextRelock: () -> Unit,
  onAddPickedUris: (List<Uri>) -> Unit,
  onPrepareCameraUri: () -> Uri?,
  onCameraCaptureResult: (Boolean) -> Unit,
  onRotateItem: (String) -> Unit,
  onRemoveItem: (String) -> Unit,
  onAnalyze: () -> Unit,
  onCombineAllIntoOneLoan: () -> Unit = {},
  onSplitIntoSeparateLoans: () -> Unit = {},
  onResetAutoGrouping: () -> Unit = {},
  onAssignScreenshotToGroup: (Int, Int) -> Unit = { _, _ -> },
  onSelectCandidateDraft: (DebtImportDraft) -> Unit,
  onEnterManually: () -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val semantic = LocalBaonColors.current

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(BaonPlanViewModel.MAX_SCAN_SCREENSHOTS)
  ) { uris ->
    if (uris.isNotEmpty()) {
      onAddPickedUris(uris)
    }
  }

  val cameraLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
  ) { success ->
    onCameraCaptureResult(success)
  }

  val documentPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    if (uris.isNotEmpty()) {
      onAddPickedUris(uris)
    }
  }

  val canAddMore = scanState.stagedItems.size < BaonPlanViewModel.MAX_SCAN_SCREENSHOTS &&
    !scanState.isAnalyzing &&
    !scanState.isImportingImages

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "Scan debt or loan document",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "Upload screenshots or loan PDFs to prefill your debt details.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        FilledTonalButton(
          onClick = {
            onSuppressNextRelock()
            runCatching {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            }
          },
          enabled = canAddMore,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (scanState.stagedItems.isEmpty()) "Pick screenshots" else "Add more",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        OutlinedButton(
          onClick = {
            val uri = onPrepareCameraUri() ?: return@OutlinedButton
            onSuppressNextRelock()
            runCatching {
              cameraLauncher.launch(uri)
            }
          },
          enabled = canAddMore,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Take photo",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      OutlinedButton(
        onClick = {
          onSuppressNextRelock()
          runCatching {
            documentPickerLauncher.launch(arrayOf("application/pdf", "image/*"))
          }
        },
        enabled = canAddMore,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp)
      ) {
        Icon(
          imageVector = Icons.Outlined.Description,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Upload PDF / loan document",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (scanState.isImportingImages) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
          )
          Text(
            text = "Preparing selected images…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (scanState.stagedItems.isNotEmpty()) {
        CalmSectionLabel(
          text = "Selected (${scanState.stagedItems.size}/${BaonPlanViewModel.MAX_SCAN_SCREENSHOTS})",
          modifier = Modifier.padding(top = 2.dp)
        )
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(
            items = scanState.stagedItems,
            key = { it.id }
          ) { item ->
            StagedScreenshotTile(
              item = item,
              enabled = !scanState.isAnalyzing && !scanState.isImportingImages,
              onRotate = { onRotateItem(item.id) },
              onRemove = { onRemoveItem(item.id) }
            )
          }
        }
      }

      if (scanState.partialWarnings.isNotEmpty()) {
        InfoCallout(
          icon = Icons.Outlined.WarningAmber,
          title = "Some images had unreadable text",
          body = scanState.partialWarnings.joinToString("\n"),
          tone = BadgeTone.WARNING
        )
      }

      if (scanState.errorMessage != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          InfoCallout(
            icon = Icons.Outlined.ErrorOutline,
            title = "Couldn't read text clearly",
            body = scanState.errorMessage,
            tone = BadgeTone.DANGER
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
          ) {
            TextButton(onClick = onEnterManually) {
              Text("Enter details manually")
            }
          }
        }
      }

      if (scanState.extractedPages.size >= 2 && scanState.candidateDrafts.isNotEmpty()) {
        val uniqueGroupCount = scanState.groupAssignments.values.distinct().size.coerceAtLeast(1)
        val groupLabels = listOf("Loan A", "Loan B", "Loan C", "Loan D", "Loan E", "Loan F")
        val allSameGroup = uniqueGroupCount == 1
        val allSeparate = uniqueGroupCount >= scanState.extractedPages.size

        BaonCard(contentPadding = 14.dp) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "Confirm screenshot grouping (${scanState.extractedPages.size} pages)",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "Combine pages from the same loan into one record, or separate distinct loans.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              FilterChip(
                selected = allSameGroup,
                onClick = onCombineAllIntoOneLoan,
                label = { Text("Same loan") }
              )
              FilterChip(
                selected = allSeparate,
                onClick = onSplitIntoSeparateLoans,
                label = { Text("Separate loans") }
              )
              FilterChip(
                selected = false,
                onClick = onResetAutoGrouping,
                label = { Text("Auto-detect") }
              )
            }

            scanState.extractedPages.sortedBy { it.screenshotIndex }.forEach { page ->
              val currentGroup = scanState.groupAssignments[page.screenshotIndex] ?: 0
              val snippetBal = page.outstandingBalanceCents.value
                ?: page.amountCurrentlyDueCents.value
                ?: page.originalPrincipalCents.value
                ?: page.installmentAmountCents.value
              val labelPreview = page.lender.value
                ?: page.loanName.value
                ?: "Page"
              val amtPreview = snippetBal?.let { " · ${MoneyFormatter.formatPeso(it)}" }.orEmpty()
              val rowCountPreview = page.installmentRows.size.takeIf { it > 0 }?.let { " · $it rows" }.orEmpty()
              val sourceTag = if (page.isPdfPage) {
                "${page.documentTitle ?: "PDF"} p.${page.pageNumber}"
              } else {
                "#${page.screenshotIndex + 1}"
              }

              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = "$sourceTag · $labelPreview$amtPreview$rowCountPreview",
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  val choicesCount = (uniqueGroupCount + 1).coerceAtMost(scanState.extractedPages.size)
                  for (g in 0 until choicesCount) {
                    val label = if (g == uniqueGroupCount) {
                      "+ ${groupLabels.getOrElse(g) { "Loan ${g + 1}" }}"
                    } else {
                      groupLabels.getOrElse(g) { "Loan ${g + 1}" }
                    }
                    FilterChip(
                      selected = currentGroup == g,
                      onClick = { onAssignScreenshotToGroup(page.screenshotIndex, g) },
                      label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                  }
                }
              }
            }
          }
        }
      }

      if (scanState.candidateDrafts.isNotEmpty()) {
        if (scanState.candidateDrafts.size > 1) {
          InfoCallout(
            icon = Icons.Outlined.AccountBalance,
            title = "${scanState.candidateDrafts.size} separate loans ready for review",
            body = scanState.multipleLoansExplanation
              ?: "Review each loan separately so balances and schedules never mix.",
            tone = BadgeTone.PRIMARY
          )
        } else if (scanState.extractedPages.size >= 2) {
          val draft = scanState.candidateDrafts.single()
          val mergedPages = draft.sourceScreenshotIndices.joinToString(", ") { "#${it + 1}" }
          InfoCallout(
            icon = Icons.Outlined.AccountBalance,
            title = "Combined screenshots $mergedPages into 1 loan",
            body = "Tap Review & save to check details and save.",
            tone = BadgeTone.POSITIVE
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          scanState.candidateDrafts.forEachIndexed { idx, draft ->
            val isSaved = draft.candidateId in scanState.completedCandidateIds
            val title = draft.loanName.value?.takeIf { it.isNotBlank() }
              ?: draft.lender.value?.takeIf { it.isNotBlank() }
              ?: "Detected loan #${idx + 1}"
            val balCents = draft.outstandingBalanceCents.value
              ?: draft.remainingInstallmentTotalCents.value
              ?: draft.calculatedUnpaidScheduleSumCents.value
              ?: draft.amountCurrentlyDueCents.value
            val balanceText = balCents?.let { MoneyFormatter.formatPeso(it) } ?: "Balance not detected"
            val pagesText = draft.sourceScreenshotIndices.joinToString(", ") { "#${it + 1}" }
            val warningCount = draft.validationIssues.count { it.severity == ImportSeverity.WARNING }

            BaonCard(contentPadding = 14.dp) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                  Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = "$balanceText · From $pagesText" +
                      if (warningCount > 0) " · $warningCount check(s)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                if (isSaved) {
                  StatusBadge(text = "Saved", tone = BadgeTone.POSITIVE)
                } else {
                  FilledTonalButton(
                    onClick = { onSelectCandidateDraft(draft) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                  ) {
                    Text("Review & save")
                  }
                }
              }
            }
          }
        }
      }

      if (scanState.candidateDrafts.isNotEmpty()) {
        val anySaved = scanState.completedCandidateIds.isNotEmpty()
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onEnterManually,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text("Enter manually")
          }
          Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text(if (anySaved) "Done" else "Close")
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onDismiss,
            enabled = !scanState.isAnalyzing,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
          ) {
            Text("Cancel")
          }
          Button(
            onClick = onAnalyze,
            enabled = scanState.stagedItems.isNotEmpty() && !scanState.isAnalyzing && !scanState.isImportingImages,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1.3f)
          ) {
            if (scanState.isAnalyzing) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Reading…")
            } else {
              Text("Analyze")
            }
          }
        }

        if (scanState.errorMessage == null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
          ) {
            TextButton(
              onClick = onEnterManually,
              enabled = !scanState.isAnalyzing
            ) {
              Text(
                text = "Enter debt manually instead",
                color = semantic.muted
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StagedScreenshotTile(
  item: StagedScreenshotUiItem,
  enabled: Boolean,
  onRotate: () -> Unit,
  onRemove: () -> Unit
) {
  val semantic = LocalBaonColors.current
  val previewBitmap by produceState<ImageBitmap?>(
    initialValue = null,
    key1 = item.tempFile.absolutePath,
    key2 = item.rotationDegrees,
    key3 = item.isPdf
  ) {
    val decoded = if (item.isPdf) {
      PdfDocumentProcessor.renderPdfPageThumbnail(item.tempFile, pageIndex = 0)
    } else {
      ImageScanPreprocessor.decodeThumbnail(
        file = item.tempFile,
        rotationDegrees = item.rotationDegrees
      )
    }
    value = decoded?.asImageBitmap()
  }

  Surface(
    modifier = Modifier.width(118.dp),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.divider)
  ) {
    Column {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(140.dp)
          .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
          .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center
      ) {
        val img = previewBitmap
        if (img != null) {
          Image(
            bitmap = img,
            contentDescription = item.displayLabel,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else if (item.isPdf) {
          Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = item.displayLabel,
            modifier = Modifier.size(34.dp),
            tint = MaterialTheme.colorScheme.primary
          )
        } else {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
          )
        }

        Surface(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ) {
          Text(
            text = if (item.isPdf) "PDF · ${item.pageCount}p" else "#${item.index + 1}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
          )
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (!item.isPdf) {
          IconButton(
            onClick = onRotate,
            enabled = enabled,
            modifier = Modifier.size(34.dp)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Outlined.RotateRight,
              contentDescription = "Rotate #${item.index + 1} right",
              modifier = Modifier.size(18.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        } else {
          Text(
            text = if (item.hasSelectableText) "Text PDF" else "Scanned",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
          )
        }
        IconButton(
          onClick = onRemove,
          enabled = enabled,
          modifier = Modifier.size(34.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove #${item.index + 1}",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error
          )
        }
      }
    }
  }
}
