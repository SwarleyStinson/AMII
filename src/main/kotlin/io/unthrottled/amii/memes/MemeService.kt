package io.unthrottled.amii.memes

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import io.unthrottled.amii.assets.MemeAssetCategory
import io.unthrottled.amii.assets.MemeAssetService
import io.unthrottled.amii.events.UserEvent
import io.unthrottled.amii.onboarding.UpdateNotification
import io.unthrottled.amii.services.ExecutionService
import io.unthrottled.amii.tools.BalloonTools.getIDEFrame
import io.unthrottled.amii.tools.PluginMessageBundle
import io.unthrottled.amii.tools.doOrElse
import io.unthrottled.amii.tools.toOptional

class MemeService(private val project: Project) {

  fun createMeme(
    userEvent: UserEvent,
    memeAssetCategory: MemeAssetCategory,
    memeDecorator: (Meme.Builder) -> Meme
  ) {
    ExecutionService.executeAsynchronously {
      UIUtil.getRootPane(
        getIDEFrame(project).component
      )?.layeredPane
        .toOptional()
        .flatMap { rootPane ->
          MemeAssetService.getFromCategory(memeAssetCategory)
            .map { memeAssets ->
              memeDecorator(Meme.Builder(memeAssets.visualMemeAsset, userEvent, rootPane))
            }
        }.doOrElse({
          attemptToDisplayMeme(it)
        }) {
          UpdateNotification.sendMessage(
            PluginMessageBundle.message("notification.no-memes.title", userEvent.eventName),
            PluginMessageBundle.message("notification.no-memes.body"),
            project
          )
        }
    }
  }

  private var displayedMeme: Meme? = null
  private fun attemptToDisplayMeme(meme: Meme) {
    val comparison = displayedMeme?.compareTo(meme) ?: Comparison.UNKNOWN
    if (comparison == Comparison.GREATER || comparison == Comparison.UNKNOWN) {
      displayedMeme?.dismiss()
      showMeme(meme)
    } else {
      meme.dispose()
    }
  }

  private fun showMeme(meme: Meme) {
    displayedMeme = meme
    meme.addListener {
      displayedMeme = null
    }
    ApplicationManager.getApplication().invokeLater {
      meme.display()
    }
  }
}